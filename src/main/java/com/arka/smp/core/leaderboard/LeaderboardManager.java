package com.arka.smp.core.leaderboard;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.DataFile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks kills, deaths, money and play time for every player and keeps ranked snapshots in memory.
 * Placeholders only read the snapshot, so a hologram asking every second costs almost nothing.
 */
public final class LeaderboardManager implements Listener {
    public enum Category {
        KILLS("kills"), DEATHS("deaths"), MONEY("money"), PLAYTIME("playtime");

        public final String id;

        Category(String id) {
            this.id = id;
        }

        public static Category of(String s) {
            for (Category c : values()) if (c.id.equalsIgnoreCase(s)) return c;
            return null;
        }
    }

    private static final class Stat {
        final UUID id;
        String name = "?";
        long kills, deaths, playTicks;
        double money;
        boolean hidden;

        Stat(UUID id) {
            this.id = id;
        }

        double value(Category c) {
            return switch (c) {
                case KILLS -> kills;
                case DEATHS -> deaths;
                case MONEY -> money;
                case PLAYTIME -> playTicks;
            };
        }
    }

    public record Entry(UUID id, String name, double value) {
    }

    private record Ranking(List<Entry> entries, Map<UUID, Integer> rank) {
    }

    private final ArkaSMPCore plugin;
    private final DataFile data;
    private final VaultEconomy economy;
    private final Map<UUID, Stat> stats = new HashMap<>();
    private volatile Map<Category, Ranking> rankings = new EnumMap<>(Category.class);
    private final Statistic killStat = stat("PLAYER_KILLS");
    private final Statistic deathStat = stat("DEATHS");
    private final Statistic playStat = stat("PLAY_ONE_MINUTE", "PLAY_TIME");
    private BukkitTask refreshTask;
    private BukkitTask saveTask;
    private BukkitTask seedTask;
    private boolean dirty;
    private final Map<String, Long> recentKills = new HashMap<>();

    public LeaderboardManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.data = new DataFile(plugin, "stats.yml");
        this.economy = new VaultEconomy(plugin);
        load();
        rebuild();
    }

    private static Statistic stat(String... names) {
        for (String n : names) {
            try {
                return Statistic.valueOf(n);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    // ---- lifecycle --------------------------------------------------------------------------

    public void start() {
        stop();
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("leaderboards.enabled", true)) return;
        long refresh = Math.max(1, cfg.getInt("leaderboards.refresh-seconds", 10)) * 20L;
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 40L, refresh);
        long save = Math.max(1, cfg.getInt("leaderboards.save-minutes", 5)) * 1200L;
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::persist, save, save);
        if (cfg.getBoolean("leaderboards.seed-existing", true) && !data.yaml().getBoolean("seeded", false)) {
            Bukkit.getScheduler().runTaskLater(plugin, this::seedExisting, 100L);
        }
    }

    public void stop() {
        for (BukkitTask t : new BukkitTask[]{refreshTask, saveTask, seedTask}) if (t != null) t.cancel();
        refreshTask = saveTask = seedTask = null;
    }

    public void shutdown() {
        stop();
        for (Player p : Bukkit.getOnlinePlayers()) capture(p, stat(p));
        persist();
        data.saveNow();
    }

    /** Admin: scan all known players again. */
    public void reseed() {
        data.yaml().set("seeded", false);
        seedExisting();
    }

    // ---- data -------------------------------------------------------------------------------

    private void load() {
        ConfigurationSection cs = data.yaml().getConfigurationSection("stats");
        if (cs == null) return;
        for (String key : cs.getKeys(false)) {
            ConfigurationSection s = cs.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Stat st = new Stat(UUID.fromString(key));
                st.name = s.getString("name", "?");
                st.kills = s.getLong("kills");
                st.deaths = s.getLong("deaths");
                st.playTicks = s.getLong("play");
                st.money = s.getDouble("money");
                st.hidden = s.getBoolean("hidden");
                stats.put(st.id, st);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void persist() {
        if (!dirty) return;
        dirty = false;
        var y = data.yaml();
        y.set("stats", null);
        for (Stat s : stats.values()) {
            String p = "stats." + s.id;
            y.set(p + ".name", s.name);
            y.set(p + ".kills", s.kills);
            y.set(p + ".deaths", s.deaths);
            y.set(p + ".play", s.playTicks);
            y.set(p + ".money", s.money);
            y.set(p + ".hidden", s.hidden);
        }
        data.markDirty();
    }

    private Stat stat(Player p) {
        Stat s = stats.get(p.getUniqueId());
        if (s == null) {
            s = new Stat(p.getUniqueId());
            if (killStat != null) s.kills = p.getStatistic(killStat);
            if (deathStat != null) s.deaths = p.getStatistic(deathStat);
            stats.put(s.id, s);
        }
        return s;
    }

    private void capture(Player p, Stat s) {
        s.name = p.getName();
        s.playTicks = plugin.players().playTicks(p.getUniqueId());   // active (non-AFK) time
        if (economy.available()) s.money = economy.balance(p);
        s.hidden = p.hasPermission(plugin.getConfig().getString("leaderboards.hide-permission", "arka.leaderboard.hide"));
        dirty = true;
    }

    /** Update online players and rebuild the ranked snapshots. */
    public void refresh() {
        for (Player p : Bukkit.getOnlinePlayers()) capture(p, stat(p));
        rebuild();
    }

    private void rebuild() {
        Map<Category, Ranking> next = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            List<Stat> visible = new ArrayList<>();
            for (Stat s : stats.values()) if (!s.hidden) visible.add(s);
            visible.sort(Comparator.<Stat>comparingDouble(s -> -s.value(c)).thenComparing(s -> s.name.toLowerCase(Locale.ROOT)));
            List<Entry> entries = new ArrayList<>(visible.size());
            Map<UUID, Integer> rank = new HashMap<>();
            for (int i = 0; i < visible.size(); i++) {
                Stat s = visible.get(i);
                entries.add(new Entry(s.id, s.name, s.value(c)));
                rank.put(s.id, i + 1);
            }
            next.put(c, new Ranking(entries, rank));
        }
        rankings = next;
    }

    /** One-time scan of players who joined before the plugin was installed, a few per tick. */
    private void seedExisting() {
        List<OfflinePlayer> todo = Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(op -> op.getName() != null && !stats.containsKey(op.getUniqueId())).toList();
        Iterator<OfflinePlayer> it = todo.iterator();
        plugin.getLogger().info("Leaderboards: importing " + todo.size() + " existing players...");
        seedTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < 5 && it.hasNext(); i++) {
                    OfflinePlayer op = it.next();
                    Stat s = new Stat(op.getUniqueId());
                    s.name = op.getName();
                    try {
                        if (killStat != null) s.kills = op.getStatistic(killStat);
                        if (deathStat != null) s.deaths = op.getStatistic(deathStat);
                        if (playStat != null) s.playTicks = op.getStatistic(playStat);
                    } catch (RuntimeException ignored) {
                    }
                    if (economy.available()) s.money = economy.balance(op);
                    stats.put(s.id, s);
                }
                if (!it.hasNext()) {
                    cancel();
                    data.yaml().set("seeded", true);
                    dirty = true;
                    rebuild();
                    plugin.getLogger().info("Leaderboards: import finished.");
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    // ---- events -----------------------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        capture(e.getPlayer(), stat(e.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        capture(e.getPlayer(), stat(e.getPlayer()));
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        stat(victim).deaths++;
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            // Anti kill-boosting: the same killer/victim pair only counts once per cooldown.
            long now = System.currentTimeMillis();
            long cd = plugin.getConfig().getInt("leaderboards.same-victim-cooldown-seconds", 300) * 1000L;
            String key = killer.getUniqueId() + ":" + victim.getUniqueId();
            Long last = recentKills.get(key);
            if (last == null || now - last >= cd) {
                stat(killer).kills++;
                recentKills.put(key, now);
                if (recentKills.size() > 500) recentKills.values().removeIf(t -> now - t >= cd);
            }
        }
        dirty = true;
    }

    // ---- reading (used by placeholders) -----------------------------------------------------

    private Entry top(Category c, int n) {
        Ranking r = rankings.get(c);
        if (r == null || n < 1 || n > r.entries().size()) return null;
        Entry e = r.entries().get(n - 1);
        if (e.value() <= 0 && !plugin.getConfig().getBoolean("leaderboards.show-zero", false)) return null;
        return e;
    }

    public String format(Category c, double v) {
        var cfg = plugin.getConfig();
        switch (c) {
            case MONEY:
                return String.format(Locale.US, cfg.getString("leaderboards.money-format", "$%,.0f"), v);
            case PLAYTIME: {
                long minutes = (long) (v / 1200.0);
                long d = minutes / 1440, h = (minutes % 1440) / 60, m = minutes % 60;
                String f = d > 0 ? cfg.getString("leaderboards.playtime-days-format", "{d}d {h}h")
                        : cfg.getString("leaderboards.playtime-format", "{h}h {m}m");
                return f.replace("{d}", String.valueOf(d)).replace("{h}", String.valueOf(h))
                        .replace("{m}", String.valueOf(m));
            }
            default:
                return String.format(Locale.US, "%,d", (long) v);
        }
    }

    /**
     * Handles everything after "top_":
     * top_kills_1_name, top_kills_1_value, top_kills_1_raw, top_position_kills, top_value_kills
     */
    public String placeholder(OfflinePlayer viewer, String params) {
        var cfg = plugin.getConfig();
        String[] p = params.toLowerCase(Locale.ROOT).split("_");
        if (p.length == 3 && (p[1].equals("position") || p[1].equals("value"))) {
            Category c = Category.of(p[2]);
            if (c == null || viewer == null) return null;
            Ranking r = rankings.get(c);
            Integer pos = r == null ? null : r.rank().get(viewer.getUniqueId());
            if (pos == null) return cfg.getString("leaderboards.unranked", "-");
            if (p[1].equals("position")) return String.valueOf(pos);
            return format(c, r.entries().get(pos - 1).value());
        }
        if (p.length == 4) {
            Category c = Category.of(p[1]);
            if (c == null) return null;
            int n;
            try {
                n = Integer.parseInt(p[2]);
            } catch (NumberFormatException e) {
                return null;
            }
            Entry e = top(c, n);
            return switch (p[3]) {
                case "name" -> e == null ? cfg.getString("leaderboards.empty-name", "-") : e.name();
                case "value" -> e == null ? cfg.getString("leaderboards.empty-value", "-") : format(c, e.value());
                case "raw" -> e == null ? "0" : String.valueOf((long) e.value());
                default -> null;
            };
        }
        return null;
    }
}
