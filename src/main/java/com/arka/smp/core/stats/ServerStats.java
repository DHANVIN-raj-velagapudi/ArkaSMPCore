package com.arka.smp.core.stats;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-wide player statistics (weekly active players, new players, total joined...).
 *
 * Every player has seven daily buckets of active (non-AFK) play time, so "played more than an hour in the last
 * 7 days" is an exact rolling window, not a calendar week. Numbers are rebuilt once a minute into one snapshot;
 * placeholders only read that snapshot, so a scoreboard asking every second costs nothing.
 *
 * Only players seen since this feature was installed are known. Weekly numbers are complete after 7 days.
 */
public final class ServerStats implements Listener {
    private static final long DAY_MS = 86_400_000L;
    private static final long TICKS_PER_HOUR = 72_000L;

    private static final class Rec {
        long first, last, returnedAt;
        final long[] ticks = new long[7];
        final long[] dayOf = new long[7];
    }

    public record Snapshot(int totalJoined, int weeklyActive, int dailyActive, int monthlyActive, int newThisWeek,
                           int online, int peak, double weekHours, double avgWeekHours,
                           int returning, double retention, int cohort, int peakToday,
                           String busiestHour, String quietestHour) {
    }

    private final ArkaSMPCore plugin;
    private final DataFile data;
    private final Map<UUID, Rec> recs = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot = new Snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, 0, "-", "-");
    private volatile int totalJoined;
    private int peak;
    private long installedAt;
    /** Highest number of players online per day (epoch day), last 30 days. */
    private final TreeMap<Long, Integer> dailyPeaks = new TreeMap<>();
    /** Per hour of the day: sum of "players online" sampled once a minute, and how many samples. */
    private final long[] hourSum = new long[24];
    private final long[] hourSamples = new long[24];
    private int tickCount;
    private BukkitTask task;

    public ServerStats(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.data = new DataFile(plugin, "server-stats.yml");
        this.peak = data.yaml().getInt("peak", 0);
        this.installedAt = data.yaml().getLong("installed", 0);
        if (installedAt == 0) {
            installedAt = System.currentTimeMillis();       // retention only counts players who joined after this
            data.yaml().set("installed", installedAt);
            data.markDirty();
        }
        var dp = data.yaml().getConfigurationSection("daily-peaks");
        if (dp != null) {
            for (String k : dp.getKeys(false)) {
                try {
                    dailyPeaks.put(Long.parseLong(k), dp.getInt(k));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        List<Long> hs = data.yaml().getLongList("hours.sum"), hn = data.yaml().getLongList("hours.samples");
        for (int i = 0; i < 24 && i < hs.size() && i < hn.size(); i++) {
            hourSum[i] = hs.get(i);
            hourSamples[i] = hn.get(i);
        }
        load();
    }

    private static long today() {
        return System.currentTimeMillis() / DAY_MS;
    }

    private void load() {
        ConfigurationSection s = data.yaml().getConfigurationSection("players");
        if (s == null) return;
        long cutoff = System.currentTimeMillis() - 60 * DAY_MS;
        for (String key : s.getKeys(false)) {
            ConfigurationSection p = s.getConfigurationSection(key);
            if (p == null) continue;
            try {
                UUID id = UUID.fromString(key);
                Rec r = new Rec();
                r.first = p.getLong("first");
                r.last = p.getLong("last");
                r.returnedAt = p.getLong("returned");
                if (r.last < cutoff && r.first < cutoff) continue;      // forgotten after 60 days: keeps the file small
                String[] parts = p.getString("days", "").split(",");
                for (int i = 0; i < parts.length && i < 7; i++) {
                    String[] kv = parts[i].split(":");
                    if (kv.length == 2) {
                        r.dayOf[i] = Long.parseLong(kv[0]);
                        r.ticks[i] = Long.parseLong(kv[1]);
                    }
                }
                recs.put(id, r);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // ---- lifecycle --------------------------------------------------------------------------

    public void start() {
        stop();
        for (Player p : Bukkit.getOnlinePlayers()) touch(p);
        countJoined();
        rebuild();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            rebuild();
            if (++tickCount % 5 == 0) countJoined();       // the playerdata folder is listed every 5 minutes
        }, 1200L, 1200L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void shutdown() {
        stop();
        persist();
        data.saveNow();
    }

    /** Called from Players.flush() (every 5 minutes) and on shutdown. */
    public void persist() {
        var y = data.yaml();
        y.set("players", null);
        y.set("peak", peak);
        y.set("daily-peaks", null);
        for (var d : dailyPeaks.entrySet()) y.set("daily-peaks." + d.getKey(), d.getValue());
        y.set("hours.sum", java.util.Arrays.stream(hourSum).boxed().toList());
        y.set("hours.samples", java.util.Arrays.stream(hourSamples).boxed().toList());
        for (var e : recs.entrySet()) {
            Rec r = e.getValue();
            StringBuilder days = new StringBuilder();
            for (int i = 0; i < 7; i++) {
                if (i > 0) days.append(',');
                days.append(r.dayOf[i]).append(':').append(r.ticks[i]);
            }
            String p = "players." + e.getKey();
            y.set(p + ".first", r.first);
            y.set(p + ".last", r.last);
            y.set(p + ".returned", r.returnedAt == 0 ? null : r.returnedAt);
            y.set(p + ".days", days.toString());
        }
        data.markDirty();
    }

    // ---- recording --------------------------------------------------------------------------

    private Rec touch(Player p) {
        return recs.computeIfAbsent(p.getUniqueId(), k -> {
            Rec r = new Rec();
            r.first = p.getFirstPlayed() > 0 ? p.getFirstPlayed() : System.currentTimeMillis();
            r.last = System.currentTimeMillis();
            return r;
        });
    }

    /** Called every second for each player who is really playing (not AFK). */
    public void addActive(UUID id, long ticks) {
        Rec r = recs.get(id);
        if (r == null) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) return;
            r = touch(p);
        }
        long day = today();
        int i = (int) (day % 7);
        synchronized (r) {
            if (r.dayOf[i] != day) {
                r.dayOf[i] = day;
                r.ticks[i] = 0;
            }
            r.ticks[i] += ticks;
            r.last = System.currentTimeMillis();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        boolean known = recs.containsKey(e.getPlayer().getUniqueId());
        Rec r = touch(e.getPlayer());
        long now = System.currentTimeMillis();
        // coming back after a week or more away counts as a returning player
        if (known && r.last > 0 && now - r.last >= 7 * DAY_MS) r.returnedAt = now;
        r.last = now;
        notePeak(Bukkit.getOnlinePlayers().size());
    }

    /** Remembers the highest online count of today (called on every join and once a minute). */
    private void notePeak(int online) {
        long day = today();
        dailyPeaks.merge(day, online, Math::max);
        while (dailyPeaks.size() > 30) dailyPeaks.pollFirstEntry();
        if (online > peak) {
            peak = online;
        }
    }

    private ZoneId zone() {
        try {
            String id = plugin.getConfig().getString("stats.timezone", "");
            return id == null || id.isBlank() ? ZoneId.systemDefault() : ZoneId.of(id);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private static String hourLabel(int h) {
        return String.format(Locale.ROOT, "%02d:00-%02d:00", h, (h + 1) % 24);
    }

    /** Players-online peak for each of the last days, oldest first (for /arka stats). */
    public Map<Long, Integer> recentPeaks(int days) {
        long from = today() - days + 1;
        Map<Long, Integer> out = new TreeMap<>();
        for (var e : dailyPeaks.entrySet()) if (e.getKey() >= from) out.put(e.getKey(), e.getValue());
        return out;
    }

    // ---- reading ----------------------------------------------------------------------------

    private static long weekTicks(Rec r, long today) {
        long sum = 0;
        synchronized (r) {
            for (int i = 0; i < 7; i++) if (today - r.dayOf[i] < 7 && r.dayOf[i] <= today) sum += r.ticks[i];
        }
        return sum;
    }

    /** Active play time of this player in the last 7 days, in hours. */
    public double weekHours(UUID id) {
        Rec r = recs.get(id);
        return r == null ? 0 : weekTicks(r, today()) / (double) TICKS_PER_HOUR;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    private void countJoined() {
        try {
            File dir = new File(Bukkit.getWorlds().get(0).getWorldFolder(), "playerdata");
            String[] names = dir.list((d, n) -> n.endsWith(".dat"));
            if (names != null) totalJoined = Math.max(names.length, recs.size());
            else totalJoined = recs.size();
        } catch (RuntimeException e) {
            totalJoined = recs.size();
        }
    }

    private void rebuild() {
        long now = System.currentTimeMillis();
        long today = today();
        double minHours = Math.max(0, plugin.getConfig().getDouble("stats.weekly-active-minutes", 60)) / 60.0;
        long minTicks = (long) (minHours * TICKS_PER_HOUR);
        int weekly = 0, daily = 0, monthly = 0, fresh = 0;
        long weekSum = 0, activeSum = 0;
        for (Rec r : recs.values()) {
            long t = weekTicks(r, today);
            weekSum += t;
            if (t > minTicks) {
                weekly++;
                activeSum += t;
            }
            if (now - r.last <= DAY_MS) daily++;
            if (now - r.last <= 30 * DAY_MS) monthly++;
            if (now - r.first <= 7 * DAY_MS) fresh++;
        }
        int returning = 0, cohort = 0, retained = 0;
        for (Rec r : recs.values()) {
            if (r.returnedAt > 0 && now - r.returnedAt <= 7 * DAY_MS) returning++;
            // retention: of players who first joined 7-30 days ago (and after this feature was installed),
            // how many were seen again at least a week after their first join
            if (r.first >= installedAt && now - r.first >= 7 * DAY_MS && now - r.first <= 30 * DAY_MS) {
                cohort++;
                if (r.last - r.first >= 7 * DAY_MS) retained++;
            }
        }
        int online = Bukkit.getOnlinePlayers().size();
        notePeak(online);
        int hour = ZonedDateTime.now(zone()).getHour();
        hourSum[hour] += online;
        hourSamples[hour]++;
        int best = -1, worst = -1;
        double bestAvg = -1, worstAvg = Double.MAX_VALUE;
        for (int h = 0; h < 24; h++) {
            if (hourSamples[h] < 30) continue;               // needs about half an hour of data for that hour
            double avg = hourSum[h] / (double) hourSamples[h];
            if (avg > bestAvg) {
                bestAvg = avg;
                best = h;
            }
            if (avg < worstAvg) {
                worstAvg = avg;
                worst = h;
            }
        }
        snapshot = new Snapshot(Math.max(totalJoined, recs.size()), weekly, daily, monthly, fresh, online, peak,
                weekSum / (double) TICKS_PER_HOUR,
                weekly == 0 ? 0 : activeSum / (double) TICKS_PER_HOUR / weekly,
                returning, cohort == 0 ? -1 : retained * 100.0 / cohort, cohort,
                dailyPeaks.getOrDefault(today, online),
                best < 0 ? "-" : hourLabel(best), worst < 0 || worst == best ? "-" : hourLabel(worst));
    }

    /** Placeholder value for %arka_stats_<name>%, or null if unknown. */
    public String placeholder(String name) {
        Snapshot s = snapshot;
        return switch (name) {
            case "total", "total_joined" -> String.valueOf(s.totalJoined());
            case "weekly", "weekly_active" -> String.valueOf(s.weeklyActive());
            case "daily", "daily_active" -> String.valueOf(s.dailyActive());
            case "monthly", "monthly_active" -> String.valueOf(s.monthlyActive());
            case "new_week", "new" -> String.valueOf(s.newThisWeek());
            case "online" -> String.valueOf(s.online());
            case "peak" -> String.valueOf(s.peak());
            case "week_hours" -> String.format(java.util.Locale.ROOT, "%.0f", s.weekHours());
            case "returning" -> String.valueOf(s.returning());
            case "retention" -> s.retention() < 0 ? "-" : String.format(Locale.ROOT, "%.0f", s.retention());
            case "peak_today" -> String.valueOf(s.peakToday());
            case "busiest_hour" -> s.busiestHour();
            case "quietest_hour" -> s.quietestHour();
            case "avg_week_hours" -> String.format(java.util.Locale.ROOT, "%.1f", s.avgWeekHours());
            default -> null;
        };
    }
}
