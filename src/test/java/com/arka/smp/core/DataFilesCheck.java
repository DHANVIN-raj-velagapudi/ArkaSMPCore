package com.arka.smp.core;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Checks that players.yml, stats.yml and server-stats.yml written from objects (no YAML tree in memory) read back to
 * exactly the same values, including tricky names, missing values and empty sections, and that text written by the
 * old code (a YamlConfiguration dump) loads fine. No server needed.
 * Run: java -cp target/classes;target/test-classes;&lt;paper-api and libraries&gt; com.arka.smp.core.DataFilesCheck
 */
public final class DataFilesCheck {
    static int failed;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (!ok) failed++;
    }

    // ---- no-server helpers --------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static <T> T alloc(Class<T> c) throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object u = f.get(null);
        return (T) u.getClass().getMethod("allocateInstance", Class.class).invoke(u, c);
    }

    static void set(Object o, String field, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, v);
    }

    static Object get(Object o, String field) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }

    static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    static YamlConfiguration parse(String text) throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString(text);
        return y;
    }

    /** Every leaf of a (path -> value as text), so two files can be compared value by value. */
    static Map<String, String> leaves(ConfigurationSection s) {
        Map<String, String> out = new TreeMap<>();
        for (String k : s.getKeys(true)) {
            Object v = s.get(k);
            if (v instanceof ConfigurationSection c) {
                if (c.getKeys(false).isEmpty()) out.put(k, "{}");
            } else {
                out.put(k, v instanceof Number n && !(v instanceof Double) ? "number " + n.longValue()
                        : v + " (" + (v == null ? "null" : v.getClass().getSimpleName()) + ")");
            }
        }
        return out;
    }

    // ---- players.yml --------------------------------------------------------------------------

    static void players() throws Exception {
        System.out.println("\n== players.yml");
        UUID a = UUID.fromString("be648cb6-8ce1-3f0d-adc2-1b3257bfa1a5");
        UUID b = UUID.fromString("00000000-0000-0000-0009-01f9bd04b4c1");     // a Bedrock (Floodgate) id
        UUID c = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID d = UUID.fromString("22222222-2222-3333-4444-555555555555");
        UUID e = UUID.fromString("33333333-2222-3333-4444-555555555555");

        // the file as the OLD code wrote it (YamlConfiguration.set per key)
        YamlConfiguration old = new YamlConfiguration();
        String p = "players." + a;
        old.set(p + ".name", "krishna7628");
        old.set(p + ".active-ticks", 1234567L);
        old.set(p + ".bonus", 250L);
        old.set(p + ".bought", 100L);
        old.set(p + ".sent-day", 20500L);
        old.set(p + ".sent-amount", 40L);
        old.set(p + ".home-limit", 7);
        old.set("players." + b + ".name", ".Steve Bedrock");
        old.set("players." + c + ".name", "true");              // must stay text
        old.set("players." + c + ".active-ticks", 0L);
        old.set("players." + d + ".name", "O'Neil");            // a quote inside a name
        old.set("players." + d + ".play-ticks", 999L);          // very old key
        old.set("players." + e + ".bonus", 5L);
        old.set("players." + e + ".home-limit", 3);
        old.set("players." + e + ".home-limit", null);          // removed again: an empty section is left
        String oldText = old.saveToString();

        Players pl = alloc(Players.class);
        set(pl, "recs", new ConcurrentHashMap<UUID, Object>());
        set(pl, "active", new ConcurrentHashMap<UUID, Long>());
        call(pl, "load", new Class<?>[]{YamlConfiguration.class}, parse(oldText));
        String text = (String) call(pl, "render", new Class<?>[0]);
        YamlConfiguration back = parse(text);
        check("every value reads back exactly as the old code wrote it", leaves(old).equals(leaves(back)));
        if (!leaves(old).equals(leaves(back))) {
            System.out.println("    old:  " + leaves(old));
            System.out.println("    new:  " + leaves(back));
        }
        check("the name 'true' stays a string", "true".equals(back.getString("players." + c + ".name")));
        check("a quote in a name survives", "O'Neil".equals(back.getString("players." + d + ".name")));
        check("saving twice gives the same text", text.equals(call(pl, "render", new Class<?>[0])));
        check("reading the new text and saving again gives the same text", roundTrip(Players.class, text).equals(text));

        // behaviour of the accessors
        check("bonus", pl.bonus(a) == 250 && pl.bonus(b) == 0 && pl.bonus(UUID.randomUUID()) == 0);
        check("bought", pl.bought(a) == 100 && pl.bought(c) == 0);
        check("play time: saved value, then the very old key, then zero",
                pl.playTicks(a) == 1234567L && pl.playTicks(d) == 999L && pl.playTicks(b) == 0 && pl.playTicks(UUID.randomUUID()) == 0);
        check("home limit override: set, removed, never set", Integer.valueOf(7).equals(pl.homeLimitOverride(a))
                && pl.homeLimitOverride(e) == null && pl.homeLimitOverride(b) == null);
        check("name", "krishna7628".equals(pl.name(a)));
    }

    @SuppressWarnings("unchecked")
    static String roundTrip(Class<?> type, String text) throws Exception {
        Object o = alloc(type);
        set(o, "recs", new ConcurrentHashMap<UUID, Object>());
        call(o, "load", new Class<?>[]{YamlConfiguration.class}, parse(text));
        return (String) call(o, "render", new Class<?>[0]);
    }

    // ---- server-stats.yml ---------------------------------------------------------------------

    static void serverStats() throws Exception {
        System.out.println("\n== server-stats.yml");
        long now = System.currentTimeMillis();
        UUID a = UUID.fromString("be648cb6-8ce1-3f0d-adc2-1b3257bfa1a5");
        UUID b = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID stale = UUID.fromString("22222222-2222-3333-4444-555555555555");
        // as the OLD code wrote it
        YamlConfiguration old = new YamlConfiguration();
        old.set("installed", now - 5 * 86_400_000L);
        old.set("peak", 12);
        old.set("daily-peaks." + (now / 86_400_000L), 9);
        old.set("daily-peaks." + (now / 86_400_000L - 1), 11);
        old.set("hours.sum", java.util.Arrays.asList(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L, 21L, 22L, 23L));
        old.set("hours.samples", java.util.Arrays.asList(5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L, 5L));
        old.set("players." + a + ".first", now - 3 * 86_400_000L);
        old.set("players." + a + ".last", now - 1000L);
        old.set("players." + a + ".days", "20500:100,20501:200,0:0,0:0,0:0,0:0,0:0");
        old.set("players." + b + ".first", now - 40 * 86_400_000L);
        old.set("players." + b + ".last", now - 2 * 86_400_000L);
        old.set("players." + b + ".returned", now - 86_400_000L);
        old.set("players." + b + ".days", "0:0,0:0,0:0,0:0,0:0,0:0,0:0");
        old.set("players." + stale + ".first", now - 90 * 86_400_000L);          // forgotten after 60 days
        old.set("players." + stale + ".last", now - 80 * 86_400_000L);
        old.set("players." + stale + ".days", "0:0,0:0,0:0,0:0,0:0,0:0,0:0");

        Object st = fresh(com.arka.smp.core.stats.ServerStats.class);
        call(st, "load", new Class<?>[]{YamlConfiguration.class}, parse(old.saveToString()));
        String text = (String) call(st, "render", new Class<?>[0]);
        YamlConfiguration back = parse(text);
        YamlConfiguration expect = parse(old.saveToString());
        expect.set("players." + stale, null);                                    // dropped on load, as before
        check("every value reads back exactly as the old code wrote it", leaves(expect).equals(leaves(back)));
        if (!leaves(expect).equals(leaves(back))) {
            System.out.println("    old:  " + leaves(expect));
            System.out.println("    new:  " + leaves(back));
        }
        check("a player not seen for 60 days is dropped", !back.isSet("players." + stale));
        check("'returned' is only written when set", !back.isSet("players." + a + ".returned") && back.isSet("players." + b + ".returned"));
        check("saving twice gives the same text", text.equals(call(st, "render", new Class<?>[0])));
        Object st2 = fresh(com.arka.smp.core.stats.ServerStats.class);
        call(st2, "load", new Class<?>[]{YamlConfiguration.class}, parse(text));
        check("reading the new text and saving again gives the same text", text.equals(call(st2, "render", new Class<?>[0])));
    }

    static Object fresh(Class<?> type) throws Exception {
        Object o = alloc(type);
        set(o, "recs", new ConcurrentHashMap<UUID, Object>());
        set(o, "dailyPeaks", new TreeMap<Long, Integer>());
        set(o, "hourSum", new long[24]);
        set(o, "hourSamples", new long[24]);
        return o;
    }

    // ---- stats.yml ----------------------------------------------------------------------------

    static void leaderboard() throws Exception {
        System.out.println("\n== stats.yml");
        UUID a = UUID.fromString("be648cb6-8ce1-3f0d-adc2-1b3257bfa1a5");
        UUID b = UUID.fromString("11111111-2222-3333-4444-555555555555");
        YamlConfiguration old = new YamlConfiguration();
        old.set("seeded", true);
        old.set("stats." + a + ".name", "krishna7628");
        old.set("stats." + a + ".kills", 3L);
        old.set("stats." + a + ".deaths", 5L);
        old.set("stats." + a + ".play", 123456L);
        old.set("stats." + a + ".money", 1250.5);
        old.set("stats." + a + ".hidden", false);
        old.set("stats." + b + ".name", "null");
        old.set("stats." + b + ".kills", 0L);
        old.set("stats." + b + ".deaths", 0L);
        old.set("stats." + b + ".play", 0L);
        old.set("stats." + b + ".money", 0.0);
        old.set("stats." + b + ".hidden", true);

        Object lb = alloc(com.arka.smp.core.leaderboard.LeaderboardManager.class);
        set(lb, "stats", new java.util.HashMap<UUID, Object>());
        call(lb, "load", new Class<?>[]{YamlConfiguration.class}, parse(old.saveToString()));
        String text = (String) call(lb, "render", new Class<?>[0]);
        YamlConfiguration back = parse(text);
        check("every value reads back exactly as the old code wrote it", leaves(old).equals(leaves(back)));
        if (!leaves(old).equals(leaves(back))) {
            System.out.println("    old:  " + leaves(old));
            System.out.println("    new:  " + leaves(back));
        }
        check("the name 'null' stays a string", "null".equals(back.getString("stats." + b + ".name")));
        check("saving twice gives the same text", text.equals(call(lb, "render", new Class<?>[0])));
        Object lb2 = alloc(com.arka.smp.core.leaderboard.LeaderboardManager.class);
        set(lb2, "stats", new java.util.HashMap<UUID, Object>());
        call(lb2, "load", new Class<?>[]{YamlConfiguration.class}, parse(text));
        check("reading the new text and saving again gives the same text", text.equals(call(lb2, "render", new Class<?>[0])));
    }

    public static void main(String[] args) throws Exception {
        players();
        serverStats();
        leaderboard();
        System.out.println(failed == 0 ? "\nALL PASSED" : "\n" + failed + " FAILED");
        if (failed != 0) System.exit(1);
    }
}
