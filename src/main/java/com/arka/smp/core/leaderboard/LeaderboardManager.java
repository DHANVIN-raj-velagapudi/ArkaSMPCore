package com.arka.smp.core.leaderboard;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.TextFile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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
        /** Lower-case name, kept so sorting never has to build a new string for each comparison. */
        String lower = "?";
        long kills, deaths, playTicks;
        double money;
        boolean hidden;

        Stat(UUID id) {
            this.id = id;
        }

        void rename(String n) {
            name = n;
            lower = n.toLowerCase(Locale.ROOT);
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
    private final TextFile data;
    /** The one-time import of players from before this plugin was installed has run. */
    private boolean seeded;
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
    /** Per category: its ranking is out of date. Kills and deaths rarely change, so most refreshes sort only play time. */
    private final boolean[] stale = new boolean[Category.values().length];
    private final Map<String, Long> recentKills = new HashMap<>();

    public LeaderboardManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.data = new TextFile(plugin, "stats.yml", this::render);
        this.economy = new VaultEconomy(plugin);
        load(data.read());
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
        if (cfg.getBoolean("leaderboards.seed-existing", true) && !seeded) {
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
        seeded = false;
        seedExisting();
    }

    // ---- data -------------------------------------------------------------------------------

    /** Copies the file into Stat objects. The YAML tree is only a temporary: it is garbage as soon as this returns. */
    void load(YamlConfiguration yaml) {
        seeded = yaml.getBoolean("seeded", false);
        ConfigurationSection cs = yaml.getConfigurationSection("stats");
        if (cs == null) return;
        for (String key : cs.getKeys(false)) {
            ConfigurationSection s = cs.getConfigurationSection(key);
            if (s == null) continue;
            try {
                Stat st = new Stat(UUID.fromString(key));
                st.rename(s.getString("name", "?"));
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
        data.markDirty();
    }

    /** The whole file as YAML text, written straight from the Stat objects (same keys as before). */
    String render() {
        StringBuilder sb = new StringBuilder(64 + stats.size() * 130);
        sb.append("seeded: ").append(seeded).append('\n');
        if (stats.isEmpty()) return sb.append("stats: {}\n").toString();
        sb.append("stats:\n");
        for (Stat s : stats.values()) {
            sb.append("  ").append(s.id).append(":\n")
                    .append("    name: ").append(TextFile.quote(s.name)).append('\n')
                    .append("    kills: ").append(s.kills).append('\n')
                    .append("    deaths: ").append(s.deaths).append('\n')
                    .append("    play: ").append(s.playTicks).append('\n')
                    .append("    money: ").append(Double.isFinite(s.money) ? Double.toString(s.money) : "0.0").append('\n')
                    .append("    hidden: ").append(s.hidden).append('\n');
        }
        return sb.toString();
    }

    private Stat stat(Player p) {
        Stat s = stats.get(p.getUniqueId());
        if (s == null) {
            s = new Stat(p.getUniqueId());
            if (killStat != null) s.kills = p.getStatistic(killStat);
            if (deathStat != null) s.deaths = p.getStatistic(deathStat);
            stats.put(s.id, s);
            java.util.Arrays.fill(stale, true);
        }
        return s;
    }

    /** Copies the live values of an online player. Only a real change marks the player and the rankings as out of date. */
    private void capture(Player p, Stat s) {
        String name = p.getName();
        if (!name.equals(s.name)) {
            s.rename(name);
            dirty = true;
            java.util.Arrays.fill(stale, true);       // the name breaks ties in every ranking
        }
        long play = plugin.players().playTicks(p.getUniqueId());   // active (non-AFK) time
        if (play != s.playTicks) {
            s.playTicks = play;
            dirty = true;
            stale[Category.PLAYTIME.ordinal()] = true;
        }
        if (economy.available()) {
            double money = economy.balance(p);
            if (money != s.money) {
                s.money = money;
                dirty = true;
                stale[Category.MONEY.ordinal()] = true;
            }
        }
        boolean hidden = p.hasPermission(plugin.getConfig().getString("leaderboards.hide-permission", "arka.leaderboard.hide"));
        if (hidden != s.hidden) {
            s.hidden = hidden;
            dirty = true;
            java.util.Arrays.fill(stale, true);       // hiding or showing a player moves everyone below
        }
    }

    /** Update online players and rebuild the ranked snapshots that changed. */
    public void refresh() {
        for (Player p : Bukkit.getOnlinePlayers()) capture(p, stat(p));
        rebuild();
    }

    private void rebuild() {
        Map<Category, Ranking> previous = rankings;
        Map<Category, Ranking> next = new EnumMap<>(Category.class);
        for (Category c : Category.values()) {
            Ranking old = previous.get(c);
            if (old != null && !stale[c.ordinal()]) {
                next.put(c, old);                      // nothing changed for this category: keep the sorted list
                continue;
            }
            stale[c.ordinal()] = false;
            List<Stat> visible = new ArrayList<>();
            for (Stat s : stats.values()) if (!s.hidden) visible.add(s);
            visible.sort(Comparator.<Stat>comparingDouble(s -> -s.value(c)).thenComparing(s -> s.lower));
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
                    s.rename(op.getName());
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
                    seeded = true;
                    dirty = true;
                    java.util.Arrays.fill(stale, true);
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
        Stat dead = stat(victim);
        dead.deaths++;
        stale[Category.DEATHS.ordinal()] = true;
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            // Anti kill-boosting: the same killer/victim pair only counts once per cooldown.
            long now = System.currentTimeMillis();
            long cd = plugin.getConfig().getInt("leaderboards.same-victim-cooldown-seconds", 300) * 1000L;
            String key = killer.getUniqueId() + ":" + victim.getUniqueId();
            Long last = recentKills.get(key);
            if (last == null || now - last >= cd) {
                Stat killing = stat(killer);
                killing.kills++;
                stale[Category.KILLS.ordinal()] = true;
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
            case PLAYTIME:
                return plugin.formatPlayTime((long) v);   // 45m, then 3h 20m, then 2d 5h
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
