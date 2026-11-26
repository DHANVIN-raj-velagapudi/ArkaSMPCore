package com.arka.smp.core.tpa;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.warps.WarpManager;
import com.arka.smp.core.warps.WarpSafety;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
        // Open the request window for them, unless they are busy in another window (the chat message is there anyway).
        if (plugin.getConfig().getBoolean("tpa.open-menu-on-request", true)
                && to.getOpenInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            Request made = find(to, from.getUniqueId());
            if (made != null) com.arka.smp.core.menu.TpaRequestMenu.open(plugin, to, made, from);
        }
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

    /** The pending request from this sender to this target, or null. */
    public Request get(Player target, UUID sender) {
        return find(target, sender);
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
        Player sender = Bukkit.getPlayer(r.from());
        if (sender == null) {
            remove(r);
            plugin.messages().send(target, "unknown-player");
            return;
        }
        // Where the walker will land, and whose feet that is: TPA walks to the target, TPAHERE to the sender.
        boolean toTarget = r.type() == Type.TPA;
        Player walker = toTarget ? sender : target;
        Player host = toTarget ? target : sender;
        Location dest = host.getLocation();
        WarpSafety.Result safe = landingCheck(dest);
        if (!safe.safe()) {
            // The request stays open: the host can move to a safe spot and it can be accepted again before it expires.
            var msg = plugin.messages();
            msg.send(host, "tpa-unsafe-yours");
            msg.send(host, WarpManager.key(safe.problem()), "detail", safe.detail());
            Player other = host == target ? sender : target;
            msg.send(other, "tpa-unsafe-theirs", "player", host.getName());
            msg.send(other, WarpManager.key(safe.problem()), "detail", safe.detail());
            plugin.getLogger().info("TPA refused (unsafe landing at " + host.getName() + "): " + safe.problem());
            return;
        }
        remove(r);
        plugin.messages().send(target, "tpa-accepted");
        plugin.messages().send(sender, "tpa-accepted-you", "player", target.getName());
        plugin.teleports().teleport(walker, dest, null);
    }

    /** Whether it is safe to land at this spot (always OK when tpa.safety-check is off). */
    public WarpSafety.Result landingCheck(Location dest) {
        if (!plugin.getConfig().getBoolean("tpa.safety-check", true)) return WarpSafety.Result.OK;
        return WarpSafety.check(WarpSafety.normalize(dest), plugin.warps().settings());
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
