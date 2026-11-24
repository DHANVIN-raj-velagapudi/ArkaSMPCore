package com.arka.smp.core.rtp;

import com.arka.smp.core.ArkaSMPCore;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RtpManager {
    private static final Set<Material> UNSAFE = EnumSet.of(Material.LAVA, Material.WATER, Material.FIRE,
            Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.BEDROCK);

    private final ArkaSMPCore plugin;
    private final Set<UUID> busy = new HashSet<>();

    public RtpManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    public void start(Player p) {
        World w = p.getWorld();
        FileConfiguration c = plugin.getConfig();
        if (w.getEnvironment() == World.Environment.THE_END || c.getStringList("rtp.disabled-worlds").contains(w.getName())) {
            plugin.messages().send(p, "rtp-world-disabled");
            return;
        }
        if (plugin.teleports().isPending(p.getUniqueId())) {
            plugin.messages().send(p, "already-teleporting");
            return;
        }
        if (plugin.getConfig().getBoolean("teleport.block-in-combat", true)
                && !p.hasPermission("arka.bypass.combat") && plugin.afk().inCombat(p.getUniqueId())) {
            plugin.messages().send(p, "teleport-combat");
            return;
        }
        if (plugin.onCooldown(p, "rtp")) return;
        if (!busy.add(p.getUniqueId())) {
            plugin.messages().send(p, "rtp-busy");
            return;
        }
        plugin.messages().send(p, "rtp-searching");
        attempt(p, w, 0);
    }

    private void fail(Player p, String key) {
        busy.remove(p.getUniqueId());
        if (p.isOnline()) plugin.messages().send(p, key);
    }

    private void attempt(Player p, World w, int n) {
        FileConfiguration c = plugin.getConfig();
        if (!p.isOnline()) {
            busy.remove(p.getUniqueId());
            return;
        }
        if (n >= c.getInt("rtp.max-attempts", 15)) {
            fail(p, "rtp-failed");
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double min = c.getInt("rtp.min-radius", 500);
        double max = Math.max(min + 1, c.getInt("rtp.max-radius", 5000));
        double r = Math.sqrt(rnd.nextDouble() * (max * max - min * min) + min * min);
        double a = rnd.nextDouble() * Math.PI * 2;
        int x = c.getInt("rtp.center-x", 0) + (int) Math.round(Math.cos(a) * r);
        int z = c.getInt("rtp.center-z", 0) + (int) Math.round(Math.sin(a) * r);
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) {
            attempt(p, w, n + 1);
            return;
        }
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) {
                busy.remove(p.getUniqueId());
                return;
            }
            Location safe = findSafe(w, x, z);
            boolean avoid = c.getBoolean("rtp.avoid-claims", true);
            if (safe != null && !com.arka.smp.core.warps.WarpSafety.check(safe, plugin.warps().settings()).safe()) {
                safe = null;   // lava / cactus / fire nearby, a tiny pillar, a sealed pocket...
            }
            if (safe == null || (avoid && plugin.claims().isClaimed(safe))) {
                attempt(p, w, n + 1);
            } else {
                busy.remove(p.getUniqueId());
                plugin.teleports().teleport(p, safe,
                        () -> plugin.startCooldown(p, "rtp", c.getInt("rtp.cooldown", 300)));
            }
        })).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> fail(p, "rtp-failed"));
            return null;
        });
    }

    private Location findSafe(World w, int x, int z) {
        int y;
        if (w.getEnvironment() == World.Environment.NETHER) {
            y = -1;
            for (int yy = 120; yy > 32; yy--) {
                if (isGround(w.getBlockAt(x, yy, z)) && isFree(w.getBlockAt(x, yy + 1, z))
                        && isFree(w.getBlockAt(x, yy + 2, z))) {
                    y = yy;
                    break;
                }
            }
            if (y < 0) return null;
        } else {
            y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        }
        if (!isGround(w.getBlockAt(x, y, z))) return null;
        if (!isFree(w.getBlockAt(x, y + 1, z)) || !isFree(w.getBlockAt(x, y + 2, z))) return null;
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }

    private static boolean isGround(Block b) {
        Material m = b.getType();
        return m.isSolid() && !UNSAFE.contains(m);
    }

    private static boolean isFree(Block b) {
        return b.isPassable() && !b.isLiquid() && !UNSAFE.contains(b.getType());
    }
}
