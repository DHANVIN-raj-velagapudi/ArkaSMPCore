package com.arka.smp.core.claims;

import com.arka.smp.core.ArkaSMPCore;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /trapped: a player stuck inside someone else's claim is moved to a safe spot a set distance outside it.
 * It never helps building or breaking: it only teleports, needs the player to really be in a foreign claim,
 * is blocked in combat and has its own cooldown (which only starts after a successful escape).
 *
 * Optimised: the candidate spots are computed from the claim rectangle (no block scanning), sorted by how
 * close they are to the player, and only a handful are ever inspected. Chunks are loaded asynchronously.
 */
public final class TrappedManager {
    /** Blocks that hurt or trap a player when standing in or on them. */
    private static final Set<Material> BAD = EnumSet.of(
            Material.LAVA, Material.WATER, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
            Material.CACTUS, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.POWDER_SNOW,
            Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POINTED_DRIPSTONE, Material.COBWEB,
            Material.BEDROCK, Material.TNT, Material.BUBBLE_COLUMN, Material.END_PORTAL, Material.NETHER_PORTAL,
            Material.END_GATEWAY, Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON);

    private final ArkaSMPCore plugin;
    private final Set<UUID> busy = new HashSet<>();

    public TrappedManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    private record Spot(int x, int z, int cost) {
    }

    public void start(Player p) {
        FileConfiguration c = plugin.getConfig();
        var msg = plugin.messages();
        if (!c.getBoolean("trapped.enabled", true)) {
            msg.send(p, "trapped-disabled");
            return;
        }
        ClaimManager.Claim claim = plugin.claims().claimAt(p.getLocation());
        if (claim == null) {
            msg.send(p, "trapped-no-claim");
            return;
        }
        if (plugin.claims().isMember(p, claim)) {
            msg.send(p, "trapped-own-claim", "owner", plugin.claims().ownerName(claim.owner));
            return;
        }
        if (c.getBoolean("teleport.block-in-combat", true) && !p.hasPermission("arka.bypass.combat")
                && plugin.afk().inCombat(p.getUniqueId())) {
            msg.send(p, "teleport-combat");
            return;
        }
        if (plugin.onCooldown(p, "trapped")) return;
        if (!busy.add(p.getUniqueId())) return;

        int dist = Math.max(3, Math.min(64, c.getInt("trapped.distance", 10)));
        List<Spot> spots = candidates(p.getLocation(), claim, dist);
        int max = Math.max(1, Math.min(spots.size(), c.getInt("trapped.max-attempts", 12)));
        msg.send(p, "trapped-searching");
        attempt(p, claim, spots.subList(0, max), 0);
    }

    /** Points just outside each of the four sides of the claim, nearest to the player first. */
    private List<Spot> candidates(Location from, ClaimManager.Claim c, int dist) {
        int px = from.getBlockX(), pz = from.getBlockZ();
        int cx = Math.max(c.minX, Math.min(c.maxX, px));
        int cz = Math.max(c.minZ, Math.min(c.maxZ, pz));
        List<Spot> out = new ArrayList<>();
        for (int extra : new int[]{0, 4, 8}) {
            int d = dist + extra;
            for (int lat : new int[]{0, 3, -3}) {
                out.add(new Spot(c.minX - d, cz + lat, 0));
                out.add(new Spot(c.maxX + d, cz + lat, 0));
                out.add(new Spot(cx + lat, c.minZ - d, 0));
                out.add(new Spot(cx + lat, c.maxZ + d, 0));
            }
        }
        List<Spot> sized = new ArrayList<>();
        for (Spot s : out) sized.add(new Spot(s.x, s.z, Math.abs(s.x - px) + Math.abs(s.z - pz)));
        sized.sort(Comparator.comparingInt(Spot::cost));
        return sized;
    }

    private void done(Player p) {
        busy.remove(p.getUniqueId());
    }

    private void attempt(Player p, ClaimManager.Claim claim, List<Spot> spots, int i) {
        if (!p.isOnline()) {
            done(p);
            return;
        }
        if (i >= spots.size()) {
            done(p);
            plugin.messages().send(p, "trapped-failed");
            return;
        }
        World w = p.getWorld();
        Spot s = spots.get(i);
        Location probe = new Location(w, s.x, p.getLocation().getY(), s.z);
        // cheap checks first: world border, and never land inside another player's claim
        if (!w.getWorldBorder().isInside(probe) || !canStand(p, probe)) {
            attempt(p, claim, spots, i + 1);
            return;
        }
        w.getChunkAtAsync(s.x >> 4, s.z >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) {
                done(p);
                return;
            }
            // the player may have walked out, been invited or started fighting while the chunk loaded
            ClaimManager.Claim now = plugin.claims().claimAt(p.getLocation());
            if (now == null || plugin.claims().isMember(p, now)) {
                done(p);
                plugin.messages().send(p, "trapped-not-in-claim");
                return;
            }
            Location safe = findSafe(w, s.x, s.z, p.getLocation().getBlockY());
            if (safe == null) {
                attempt(p, claim, spots, i + 1);
                return;
            }
            escape(p, safe);
        })).exceptionally(ex -> {
            Bukkit.getScheduler().runTask(plugin, () -> attempt(p, claim, spots, i + 1));
            return null;
        });
    }

    private boolean canStand(Player p, Location l) {
        return plugin.claims().isMember(p, plugin.claims().claimAt(l));
    }

    private void escape(Player p, Location dest) {
        FileConfiguration c = plugin.getConfig();
        if (p.isInsideVehicle()) p.leaveVehicle();
        p.teleportAsync(dest).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            done(p);
            if (ok && p.isOnline()) {
                p.setFallDistance(0f);
                plugin.messages().send(p, "trapped-done", "distance", String.valueOf(c.getInt("trapped.distance", 10)));
                plugin.startCooldown(p, "trapped", c.getInt("trapped.cooldown", 60));
            }
        }));
    }

    // ---- safe spot search --------------------------------------------------------------------

    /**
     * Looks for a standing spot in this column: first close to the player's height (so a miner in a cave
     * is not thrown to the surface), then on the surface of normal worlds.
     */
    private Location findSafe(World w, int x, int z, int refY) {
        int min = w.getMinHeight() + 2;
        int max = w.getMaxHeight() - 4;
        if (w.getEnvironment() == World.Environment.NETHER) max = Math.min(max, 120);
        for (int dy = 0; dy <= 24; dy++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (dy == 0 && sign == -1) continue;
                int y = refY + dy * sign;
                if (y < min || y > max) continue;
                if (safeSpot(w, x, y, z)) return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        if (w.getEnvironment() == World.Environment.NORMAL) {
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (y >= min && y <= max && safeSpot(w, x, y, z)) return new Location(w, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    /** y is the player's feet block. */
    private boolean safeSpot(World w, int x, int y, int z) {
        Block floor = w.getBlockAt(x, y - 1, z);
        Block feet = w.getBlockAt(x, y, z);
        Block head = w.getBlockAt(x, y + 1, z);
        if (!floor.getType().isSolid() || BAD.contains(floor.getType())) return false;
        if (!free(feet) || !free(head)) return false;
        // no liquid, fire, lava, dripstone, cactus... within two blocks (and nothing hanging overhead)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -3; dy <= 2; dy++) {
                    Material m = w.getBlockAt(x + dx, y + dy, z + dz).getType();
                    if (BAD.contains(m)) return false;
                }
            }
        }
        // do not stand on a ledge over a long drop
        return w.getBlockAt(x, y - 2, z).getType().isSolid() || w.getBlockAt(x, y - 3, z).getType().isSolid();
    }

    private static boolean free(Block b) {
        return b.isPassable() && !b.isLiquid() && !BAD.contains(b.getType());
    }

    public void forget(UUID id) {
        busy.remove(id);
    }
}
