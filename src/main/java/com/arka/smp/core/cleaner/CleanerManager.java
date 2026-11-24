package com.arka.smp.core.cleaner;

import com.arka.smp.core.ArkaSMPCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Tameable;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ClearLag replacement: one 1-second timer, only items/arrows/(optional) listed mobs are touched. */
public final class CleanerManager {
    private final ArkaSMPCore plugin;
    private BukkitTask task;
    private int remaining;

    public CleanerManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("cleaner.enabled", true)) return;
        final int interval = Math.max(30, c.getInt("cleaner.interval-seconds", 600));
        remaining = interval;
        final Set<Integer> warnings = new HashSet<>(c.getIntegerList("cleaner.warnings"));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remaining--;
            if (remaining <= 0) {
                clear(true);
                remaining = interval;
            } else if (warnings.contains(remaining)) {
                plugin.messages().broadcast("cleaner-warning", "seconds", String.valueOf(remaining));
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Remove clutter now. Returns {items, mobs}. */
    public int[] clear(boolean announce) {
        FileConfiguration c = plugin.getConfig();
        boolean items = c.getBoolean("cleaner.items.enabled", true);
        boolean xp = c.getBoolean("cleaner.xp-orbs", false);
        boolean arrows = c.getBoolean("cleaner.arrows", true);
        boolean mobsOn = c.getBoolean("cleaner.mobs.enabled", false);

        Set<Material> keep = EnumSet.noneOf(Material.class);
        for (String s : c.getStringList("cleaner.items.whitelist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) keep.add(m);
        }
        Set<EntityType> mobTypes = new HashSet<>();
        for (String s : c.getStringList("cleaner.mobs.types")) {
            try {
                mobTypes.add(EntityType.valueOf(s.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        int itemCount = 0;
        int mobCount = 0;
        for (World w : Bukkit.getWorlds()) {
            if (items) {
                for (Item it : w.getEntitiesByClass(Item.class)) {
                    if (it.getPickupDelay() == 32767 || keep.contains(it.getItemStack().getType())) continue;
                    it.remove();
                    itemCount++;
                }
            }
            if (xp) w.getEntitiesByClass(ExperienceOrb.class).forEach(Entity::remove);
            if (arrows) {
                for (AbstractArrow a : w.getEntitiesByClass(AbstractArrow.class)) {
                    if (a.isInBlock() || a.isOnGround()) a.remove();
                }
            }
            if (mobsOn) {
                for (LivingEntity le : w.getLivingEntities()) {
                    if (!mobTypes.contains(le.getType()) || !(le instanceof Mob mob)) continue;
                    if (mob.customName() != null || mob.isPersistent() || mob.isLeashed()
                            || !mob.getPassengers().isEmpty() || (mob instanceof Tameable t && t.isTamed())) continue;
                    mob.remove();
                    mobCount++;
                }
            }
        }
        if (announce) {
            plugin.messages().broadcast("cleaner-done", "items", String.valueOf(itemCount),
                    "mobs", String.valueOf(mobCount));
        }
        return new int[]{itemCount, mobCount};
    }
}
