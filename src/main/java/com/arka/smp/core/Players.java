package com.arka.smp.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player data: claim-block bonus, home-limit override, last known name and play time.
 * Claim blocks = starting-blocks + (hours played x blocks-per-hour) + admin bonus.
 * Play time comes from the vanilla statistic, so no ticking task is needed.
 */
public final class Players implements Listener {
    private static final double TICKS_PER_HOUR = 72000.0;
    private final ArkaSMPCore plugin;
    private final DataFile data;
    private final Statistic playStat;
    /** Active (non-AFK) play time in ticks for online players; saved on quit and every few minutes. */
    private final Map<UUID, Long> active = new java.util.concurrent.ConcurrentHashMap<>();

    public Players(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.data = new DataFile(plugin, "players.yml");
        Statistic found = null;
        for (String n : new String[]{"PLAY_ONE_MINUTE", "PLAY_TIME"}) {
            try {
                found = Statistic.valueOf(n);
                break;
            } catch (IllegalArgumentException ignored) {
            }
        }
        this.playStat = found;
    }

    public void save() {
        flush();
        data.saveNow();
    }

    /** Load active time for players already online (plugin enabled while the server runs). */
    public void start() {
        for (Player p : org.bukkit.Bukkit.getOnlinePlayers()) loadActive(p);
    }

    private void loadActive(Player p) {
        UUID id = p.getUniqueId();
        String key = path(id, "active-ticks");
        // First time: start from the vanilla play time so nobody loses what they already earned.
        long v = data.yaml().isSet(key) ? data.yaml().getLong(key)
                : (playStat != null ? p.getStatistic(playStat) : 0);
        active.put(id, v);
    }

    /** Write active time of online players into the data file (disk write is debounced). */
    public void flush() {
        for (var e : active.entrySet()) data.yaml().set(path(e.getKey(), "active-ticks"), e.getValue());
        data.markDirty();
        if (plugin.stats() != null) plugin.stats().persist();
    }

    /** Called once per second for each non-AFK player. */
    public void addActive(UUID id, long ticks) {
        active.merge(id, ticks, Long::sum);
        if (plugin.stats() != null) plugin.stats().addActive(id, ticks);
    }

    private String path(UUID id, String key) {
        return "players." + id + "." + key;
    }

    // ---- names ------------------------------------------------------------------------------

    public String name(UUID id) {
        String n = data.yaml().getString(path(id, "name"));
        if (n != null) return n;
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : "?";
    }

    /** Online player, cached offline player, or null. */
    public OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    // ---- claim blocks -----------------------------------------------------------------------

    /** Active (non-AFK) play time in ticks. */
    public long playTicks(UUID id) {
        Long v = active.get(id);
        if (v != null) return v;
        return data.yaml().getLong(path(id, "active-ticks"), data.yaml().getLong(path(id, "play-ticks"), 0));
    }

    public long earned(UUID id) {
        double perHour = plugin.getConfig().getDouble("claims.blocks-per-hour", 100);
        long v = (long) Math.floor(playTicks(id) / TICKS_PER_HOUR * perHour);
        long cap = plugin.getConfig().getLong("claims.max-earned-blocks", 0);
        return cap > 0 ? Math.min(v, cap) : v;
    }

    public long bonus(UUID id) {
        return data.yaml().getLong(path(id, "bonus"), 0);
    }

    public void addBonus(UUID id, long amount) {
        data.yaml().set(path(id, "bonus"), bonus(id) + amount);
        data.markDirty();
    }

    /** Claim blocks this player bought with rupees (already included in the bonus). */
    public long bought(UUID id) {
        return data.yaml().getLong(path(id, "bought"), 0);
    }

    public void addBought(UUID id, long amount) {
        data.yaml().set(path(id, "bought"), bought(id) + amount);
        addBonus(id, amount);
    }

    private static long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    /** Claim blocks this player has sent to others today (resets at midnight UTC). */
    public long sentToday(UUID id) {
        return data.yaml().getLong(path(id, "sent-day"), -1) == today() ? data.yaml().getLong(path(id, "sent-amount"), 0) : 0;
    }

    public void addSent(UUID id, long amount) {
        long now = sentToday(id) + amount;
        data.yaml().set(path(id, "sent-day"), today());
        data.yaml().set(path(id, "sent-amount"), now);
        data.markDirty();
    }

    public long total(UUID id) {
        long start = plugin.getConfig().getLong("claims.starting-blocks", 300);
        return Math.max(0, start + earned(id) + bonus(id));
    }

    /** Make the player's total exactly "total" by adjusting the admin bonus. */
    public void setTotal(UUID id, long total) {
        long start = plugin.getConfig().getLong("claims.starting-blocks", 300);
        data.yaml().set(path(id, "bonus"), total - start - earned(id));
        data.markDirty();
    }

    public long used(UUID id) {
        return plugin.claims().usedBlocks(id);
    }

    public long remaining(UUID id) {
        return Math.max(0, total(id) - used(id));
    }

    // ---- home limit override ----------------------------------------------------------------

    public Integer homeLimitOverride(UUID id) {
        String key = path(id, "home-limit");
        return data.yaml().isSet(key) ? data.yaml().getInt(key) : null;
    }

    public void setHomeLimit(UUID id, Integer limit) {
        data.yaml().set(path(id, "home-limit"), limit);
        data.markDirty();
    }

    // ---- events -----------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        data.yaml().set(path(e.getPlayer().getUniqueId(), "name"), e.getPlayer().getName());
        loadActive(e.getPlayer());
        data.markDirty();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Long v = active.remove(p.getUniqueId());
        if (v != null) data.yaml().set(path(p.getUniqueId(), "active-ticks"), v);
        data.markDirty();
    }
}
