package com.arka.smp.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Per-player data: claim-block bonus, home-limit override, last known name and play time.
 * Claim blocks = starting-blocks + (hours played x blocks-per-hour) + admin bonus.
 * Play time comes from the vanilla statistic, so no ticking task is needed.
 *
 * Memory: one small {@link Rec} per player. The file is read once at start and written as text from these objects (see
 * {@link TextFile}); a YamlConfiguration tree would hold the same data again at about 6 KB per player.
 */
public final class Players implements Listener {
    private static final double TICKS_PER_HOUR = 72000.0;

    /** What is kept for one player. A field is null until it was ever set, so the saved file keeps its old shape. */
    static final class Rec {
        String name;
        Long activeTicks;       // saved active (non-AFK) play time
        Long legacyPlay;        // "play-ticks" written by very old versions: read only, but kept
        Long bonus, bought, sentDay, sentAmount;
        Integer homeLimit;

        boolean empty() {
            return name == null && activeTicks == null && legacyPlay == null && bonus == null && bought == null
                    && sentDay == null && sentAmount == null && homeLimit == null;
        }
    }

    private final ArkaSMPCore plugin;
    private final TextFile data;
    private final Map<UUID, Rec> recs = new java.util.concurrent.ConcurrentHashMap<>();   // placeholders read it from other threads
    private final Statistic playStat;
    /** Active (non-AFK) play time in ticks for online players; saved on quit and every few minutes. */
    private final Map<UUID, Long> active = new java.util.concurrent.ConcurrentHashMap<>();

    public Players(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.data = new TextFile(plugin, "players.yml", this::render);
        load(data.read());
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

    // ---- file --------------------------------------------------------------------------------

    /** Copies the file into small objects. */
    void load(YamlConfiguration yaml) {
        ConfigurationSection all = yaml.getConfigurationSection("players");
        if (all == null) return;
        for (String key : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(key);
            if (s == null) continue;
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            Rec r = new Rec();
            r.name = s.getString("name");
            r.activeTicks = s.isSet("active-ticks") ? s.getLong("active-ticks") : null;
            r.legacyPlay = s.isSet("play-ticks") ? s.getLong("play-ticks") : null;
            r.bonus = s.isSet("bonus") ? s.getLong("bonus") : null;
            r.bought = s.isSet("bought") ? s.getLong("bought") : null;
            r.sentDay = s.isSet("sent-day") ? s.getLong("sent-day") : null;
            r.sentAmount = s.isSet("sent-amount") ? s.getLong("sent-amount") : null;
            r.homeLimit = s.isSet("home-limit") ? s.getInt("home-limit") : null;
            recs.put(id, r);
        }
    }

    /** The whole file as YAML text, written straight from the objects. */
    String render() {
        if (recs.isEmpty()) return "players: {}\n";
        StringBuilder sb = new StringBuilder(32 + recs.size() * 160);
        sb.append("players:\n");
        for (Map.Entry<UUID, Rec> e : recs.entrySet()) {
            Rec r = e.getValue();
            sb.append("  ").append(e.getKey());
            if (r.empty()) {
                sb.append(": {}\n");
                continue;
            }
            sb.append(":\n");
            if (r.name != null) sb.append("    name: ").append(TextFile.quote(r.name)).append('\n');
            line(sb, "active-ticks", r.activeTicks);
            line(sb, "play-ticks", r.legacyPlay);
            line(sb, "bonus", r.bonus);
            line(sb, "bought", r.bought);
            line(sb, "sent-day", r.sentDay);
            line(sb, "sent-amount", r.sentAmount);
            line(sb, "home-limit", r.homeLimit);
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String key, Number value) {
        if (value != null) sb.append("    ").append(key).append(": ").append(value).append('\n');
    }

    private Rec rec(UUID id) {
        return recs.computeIfAbsent(id, k -> new Rec());
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
        Rec r = recs.get(id);
        // First time: start from the vanilla play time so nobody loses what they already earned.
        long v = r != null && r.activeTicks != null ? r.activeTicks
                : (playStat != null ? p.getStatistic(playStat) : 0);
        active.put(id, v);
    }

    /** Write active time of online players into the data (the disk write is debounced). */
    public void flush() {
        for (var e : active.entrySet()) rec(e.getKey()).activeTicks = e.getValue();
        data.markDirty();
        if (plugin.stats() != null) plugin.stats().persist();
    }

    /** Called once per second for each non-AFK player. */
    public void addActive(UUID id, long ticks) {
        active.merge(id, ticks, Long::sum);
        if (plugin.stats() != null) plugin.stats().addActive(id, ticks);
    }

    // ---- names ------------------------------------------------------------------------------

    public String name(UUID id) {
        Rec r = recs.get(id);
        if (r != null && r.name != null) return r.name;
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
        Rec r = recs.get(id);
        if (r == null) return 0;
        if (r.activeTicks != null) return r.activeTicks;
        return r.legacyPlay != null ? r.legacyPlay : 0;
    }

    public long earned(UUID id) {
        double perHour = plugin.getConfig().getDouble("claims.blocks-per-hour", 100);
        long v = (long) Math.floor(playTicks(id) / TICKS_PER_HOUR * perHour);
        long cap = plugin.getConfig().getLong("claims.max-earned-blocks", 0);
        return cap > 0 ? Math.min(v, cap) : v;
    }

    public long bonus(UUID id) {
        Rec r = recs.get(id);
        return r != null && r.bonus != null ? r.bonus : 0;
    }

    public void addBonus(UUID id, long amount) {
        rec(id).bonus = bonus(id) + amount;
        data.markDirty();
    }

    /** Claim blocks this player bought with rupees (already included in the bonus). */
    public long bought(UUID id) {
        Rec r = recs.get(id);
        return r != null && r.bought != null ? r.bought : 0;
    }

    public void addBought(UUID id, long amount) {
        rec(id).bought = bought(id) + amount;
        addBonus(id, amount);
    }

    private static long today() {
        return System.currentTimeMillis() / 86_400_000L;
    }

    /** Claim blocks this player has sent to others today (resets at midnight UTC). */
    public long sentToday(UUID id) {
        Rec r = recs.get(id);
        if (r == null) return 0;
        long day = r.sentDay != null ? r.sentDay : -1;
        return day == today() ? (r.sentAmount != null ? r.sentAmount : 0) : 0;
    }

    public void addSent(UUID id, long amount) {
        long now = sentToday(id) + amount;
        Rec r = rec(id);
        r.sentDay = today();
        r.sentAmount = now;
        data.markDirty();
    }

    public long total(UUID id) {
        long start = plugin.getConfig().getLong("claims.starting-blocks", 300);
        return Math.max(0, start + earned(id) + bonus(id));
    }

    /** Make the player's total exactly "total" by adjusting the admin bonus. */
    public void setTotal(UUID id, long total) {
        long start = plugin.getConfig().getLong("claims.starting-blocks", 300);
        rec(id).bonus = total - start - earned(id);
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
        Rec r = recs.get(id);
        return r == null ? null : r.homeLimit;
    }

    public void setHomeLimit(UUID id, Integer limit) {
        rec(id).homeLimit = limit;
        data.markDirty();
    }

    // ---- events -----------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        rec(e.getPlayer().getUniqueId()).name = e.getPlayer().getName();
        loadActive(e.getPlayer());
        data.markDirty();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Long v = active.remove(p.getUniqueId());
        if (v != null) rec(p.getUniqueId()).activeTicks = v;
        data.markDirty();
    }
}
