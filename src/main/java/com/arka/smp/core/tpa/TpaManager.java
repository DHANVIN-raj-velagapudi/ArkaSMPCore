package com.arka.smp.core.tpa;

import com.arka.smp.core.ArkaSMPCore;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TpaManager implements Listener {
    public enum Type { TPA, TPAHERE }

    public record Request(UUID from, UUID to, Type type, long expires) {
    }

    private final ArkaSMPCore plugin;
    /** target -> (sender -> request). Expired entries are pruned lazily, no repeating task needed. */
    private final Map<UUID, Map<UUID, Request>> byTarget = new HashMap<>();

    public TpaManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    private long timeoutMillis() {
        return plugin.getConfig().getInt("tpa.timeout", 60) * 1000L;
    }

    public void send(Player from, Player to, Type type) {
        if (from.getUniqueId().equals(to.getUniqueId())) {
            plugin.messages().send(from, "tpa-self");
            return;
        }
        List<Request> existing = incoming(to.getUniqueId());
        for (Request r : existing) {
            if (r.from().equals(from.getUniqueId())) {
                plugin.messages().send(from, "tpa-pending", "player", to.getName());
                return;
            }
        }
        if (plugin.onCooldown(from, "tpa")) return;
        plugin.startCooldown(from, "tpa", plugin.getConfig().getInt("tpa.cooldown", 10));
        byTarget.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>()).put(from.getUniqueId(),
                new Request(from.getUniqueId(), to.getUniqueId(), type, System.currentTimeMillis() + timeoutMillis()));
        plugin.messages().send(from, type == Type.TPA ? "tpa-sent" : "tpa-here-sent", "player", to.getName());
        plugin.messages().send(to, type == Type.TPA ? "tpa-received" : "tpa-here-received", "player", from.getName());
    }

    /** Pending requests for a target, newest first, expired ones removed. */
    public List<Request> incoming(UUID target) {
        Map<UUID, Request> map = byTarget.get(target);
        if (map == null) return List.of();
        long now = System.currentTimeMillis();
        map.values().removeIf(r -> r.expires() < now);
        if (map.isEmpty()) {
            byTarget.remove(target);
            return List.of();
        }
        List<Request> list = new ArrayList<>(map.values());
        list.sort((a, b) -> Long.compare(b.expires(), a.expires()));
        return list;
    }

    private Request find(Player target, UUID senderOrNull) {
        for (Request r : incoming(target.getUniqueId())) {
            if (senderOrNull == null || r.from().equals(senderOrNull)) return r;
        }
        return null;
    }

    private void remove(Request r) {
        Map<UUID, Request> map = byTarget.get(r.to());
        if (map != null) {
            map.remove(r.from());
            if (map.isEmpty()) byTarget.remove(r.to());
        }
    }

    public void accept(Player target, UUID senderOrNull) {
        Request r = find(target, senderOrNull);
        if (r == null) {
            plugin.messages().send(target, "tpa-none");
            return;
        }
        remove(r);
        Player sender = Bukkit.getPlayer(r.from());
        if (sender == null) {
            plugin.messages().send(target, "unknown-player");
            return;
        }
        plugin.messages().send(target, "tpa-accepted");
        plugin.messages().send(sender, "tpa-accepted-you", "player", target.getName());
        if (r.type() == Type.TPA) {
            plugin.teleports().teleport(sender, target.getLocation(), null);
        } else {
            plugin.teleports().teleport(target, sender.getLocation(), null);
        }
    }

    public void deny(Player target, UUID senderOrNull) {
        Request r = find(target, senderOrNull);
        if (r == null) {
            plugin.messages().send(target, "tpa-none");
            return;
        }
        remove(r);
        plugin.messages().send(target, "tpa-denied");
        Player sender = Bukkit.getPlayer(r.from());
        if (sender != null) plugin.messages().send(sender, "tpa-denied-you", "player", target.getName());
    }

    public void cancel(Player from) {
        boolean any = false;
        for (Map<UUID, Request> map : byTarget.values()) {
            if (map.remove(from.getUniqueId()) != null) any = true;
        }
        byTarget.values().removeIf(Map::isEmpty);
        plugin.messages().send(from, any ? "tpa-cancelled" : "tpa-none");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        byTarget.remove(id);
        for (Map<UUID, Request> map : byTarget.values()) map.remove(id);
        byTarget.values().removeIf(Map::isEmpty);
    }
}
