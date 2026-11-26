package com.arka.smp.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Single place for warmup + teleport. Only pending teleports are tracked, so idle cost is zero. */
public final class TeleportService implements Listener {
    private final ArkaSMPCore plugin;
    private final Map<UUID, BukkitTask> pending = new HashMap<>();

    public TeleportService(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    public void teleport(Player p, Location dest, Runnable onSuccess) {
        int warmup = plugin.getConfig().getInt("teleport.warmup", 3);
        UUID id = p.getUniqueId();
        if (plugin.getConfig().getBoolean("teleport.block-in-combat", true)
                && !p.hasPermission("arka.bypass.combat") && plugin.afk().inCombat(id)) {
            plugin.messages().send(p, "teleport-combat");
            return;
        }
        if (warmup <= 0 || p.hasPermission("arka.bypass.warmup")) {
            go(p, dest, onSuccess);
            return;
        }
        if (pending.containsKey(id)) {
            plugin.messages().send(p, "already-teleporting");
            return;
        }
        plugin.messages().send(p, "teleport-warmup", "seconds", String.valueOf(warmup));
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(id);
            if (p.isOnline()) go(p, dest, onSuccess);
        }, warmup * 20L);
        pending.put(id, task);
    }

    public boolean isPending(UUID id) {
        return pending.containsKey(id);
    }

    private void go(Player p, Location dest, Runnable onSuccess) {
        p.teleportAsync(dest).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ok && p.isOnline()) {
                plugin.messages().send(p, "teleported");
                if (onSuccess != null) onSuccess.run();
            } else if (!ok) {
                // Another plugin or the server refused the move: say so instead of leaving the player wondering.
                plugin.errors().warn("teleport", "Teleport did not happen (cancelled by the server or another plugin)",
                        "player", p.getName(), "to", dest.getWorld() == null ? "?" : dest.getWorld().getName() + " "
                                + dest.getBlockX() + " " + dest.getBlockY() + " " + dest.getBlockZ());
            }
        })).exceptionally(t -> {
            plugin.errors().error("teleport", "Teleport failed", t, "player", p.getName());
            return null;
        });
    }

    private void cancel(Player p, boolean notify) {
        BukkitTask t = pending.remove(p.getUniqueId());
        if (t != null) {
            t.cancel();
            if (notify) plugin.messages().send(p, "teleport-cancelled");
        }
    }

    public void shutdown() {
        pending.values().forEach(BukkitTask::cancel);
        pending.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (pending.isEmpty() || !e.hasChangedBlock()) return;
        if (plugin.getConfig().getBoolean("teleport.cancel-on-move", true)) cancel(e.getPlayer(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (pending.isEmpty() || !(e.getEntity() instanceof Player p)) return;
        if (plugin.getConfig().getBoolean("teleport.cancel-on-damage", true)) cancel(p, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cancel(e.getPlayer(), false);   // cooldowns are kept on purpose: relogging must not reset them
    }
}
