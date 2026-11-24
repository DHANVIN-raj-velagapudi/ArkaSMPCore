package com.arka.smp.core.claims;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.api.MoneyCharger;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

/**
 * Buying claim blocks with rupees and sending claim blocks to other players.
 *
 * Money and blocks both change on the main thread in one step, so nothing can be half-done.
 * Anti-abuse: price per block, per-purchase and lifetime purchase caps; sending needs some play time,
 * only unused blocks can be sent, and there is a daily send cap (stops alt accounts feeding a main).
 */
public final class ClaimBlocks {
    private final ArkaSMPCore plugin;

    public ClaimBlocks(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    // ---- buying ---------------------------------------------------------------------------------

    public double price() {
        return Math.max(0.01, plugin.getConfig().getDouble("claims.buy.price-per-block", 2.0));
    }

    public long maxPerPurchase() {
        return Math.max(1, plugin.getConfig().getLong("claims.buy.max-per-purchase", 5000));
    }

    public long maxTotalBought() {
        return Math.max(0, plugin.getConfig().getLong("claims.buy.max-total", 50000));
    }

    public double cost(long amount) {
        return Math.round(amount * price() * 100.0) / 100.0;
    }

    private static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** Charges rupees and adds the blocks. Returns true when the purchase went through. */
    public boolean buy(Player p, long amount) {
        var msg = plugin.messages();
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("claims.buy.enabled", true)) {
            msg.send(p, "cb-buy-disabled");
            return false;
        }
        if (amount < 1 || amount > maxPerPurchase()) {
            msg.send(p, "cb-buy-range", "max", String.valueOf(maxPerPurchase()));
            return false;
        }
        UUID id = p.getUniqueId();
        long cap = maxTotalBought();
        if (cap > 0 && plugin.players().bought(id) + amount > cap) {
            msg.send(p, "cb-buy-cap", "left", String.valueOf(Math.max(0, cap - plugin.players().bought(id))));
            return false;
        }
        double cost = cost(amount);
        var charger = Bukkit.getServicesManager().load(MoneyCharger.class);
        if (charger == null) {
            msg.send(p, "cb-no-economy");
            return false;
        }
        var res = charger.charge(id, cost, "claim blocks x" + amount);
        if (res != MoneyCharger.Result.OK) {
            msg.send(p, res == MoneyCharger.Result.INSUFFICIENT ? "cb-buy-insufficient" : "cb-buy-failed",
                    "cost", num(cost));
            return false;
        }
        plugin.players().addBought(id, amount);
        plugin.getLogger().info(p.getName() + " bought " + amount + " claim blocks for " + num(cost));
        msg.send(p, "cb-bought", "amount", String.valueOf(amount), "cost", num(cost),
                "remaining", String.valueOf(plugin.players().remaining(id)));
        return true;
    }

    // ---- sending --------------------------------------------------------------------------------

    public long sendMin() {
        return Math.max(1, plugin.getConfig().getLong("claims.transfer.min-amount", 10));
    }

    public long dailyCap() {
        return Math.max(0, plugin.getConfig().getLong("claims.transfer.daily-cap", 3000));
    }

    public double minPlayHours() {
        return Math.max(0, plugin.getConfig().getDouble("claims.transfer.min-play-hours", 5));
    }

    /** Why a player may not send at all right now (message key), or null. */
    public String sendBlocker(Player p) {
        if (!plugin.getConfig().getBoolean("claims.transfer.enabled", true)) return "cb-send-disabled";
        double hours = plugin.players().playTicks(p.getUniqueId()) / 72000.0;
        if (hours < minPlayHours() && !p.hasPermission("arka.claims.transfer.bypass")) return "cb-send-playtime";
        return null;
    }

    /** Moves unused claim blocks from the sender to the target. Returns true on success. */
    public boolean send(Player from, OfflinePlayer to, long amount) {
        var msg = plugin.messages();
        var players = plugin.players();
        String blocker = sendBlocker(from);
        if (blocker != null) {
            msg.send(from, blocker, "hours", String.valueOf((long) minPlayHours()));
            return false;
        }
        if (to == null || (!to.isOnline() && !to.hasPlayedBefore())) {
            msg.send(from, "unknown-player");
            return false;
        }
        UUID a = from.getUniqueId(), b = to.getUniqueId();
        if (a.equals(b)) {
            msg.send(from, "cb-send-self");
            return false;
        }
        if (amount < sendMin()) {
            msg.send(from, "cb-send-min", "min", String.valueOf(sendMin()));
            return false;
        }
        long free = players.remaining(a);
        if (amount > free) {
            msg.send(from, "cb-send-notenough", "free", String.valueOf(free));
            return false;
        }
        long cap = dailyCap();
        if (cap > 0 && players.sentToday(a) + amount > cap) {
            msg.send(from, "cb-send-daily", "left", String.valueOf(Math.max(0, cap - players.sentToday(a))));
            return false;
        }
        String toName = to.getName() != null ? to.getName() : players.name(b);
        players.addBonus(a, -amount);
        players.addBonus(b, amount);
        players.addSent(a, amount);
        plugin.getLogger().info(from.getName() + " sent " + amount + " claim blocks to " + toName);
        msg.send(from, "cb-sent", "amount", String.valueOf(amount), "player", toName,
                "remaining", String.valueOf(players.remaining(a)));
        Player online = to.getPlayer();
        if (online != null) {
            msg.send(online, "cb-received", "amount", String.valueOf(amount), "player", from.getName(),
                    "remaining", String.valueOf(players.remaining(b)));
        }
        return true;
    }
}
