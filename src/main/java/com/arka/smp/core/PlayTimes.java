package com.arka.smp.core;

/**
 * Play time as text, written the same way everywhere (placeholder, leaderboard, API): below 1 hour only minutes
 * ("45m"), from 1 hour hours and minutes ("3h 20m"), from 24 hours days and hours without minutes ("2d 5h"). The
 * wordings use {d} {h} {m}. No server needed.
 */
public final class PlayTimes {
    private PlayTimes() {
    }

    public static final String MINUTES = "{m}m";
    public static final String HOURS = "{h}h {m}m";
    public static final String DAYS = "{d}d {h}h";

    /** The one wording used by %arka_playtime%, the leaderboard and the PlayTimeFormat API. Not configurable. */
    public static String compact(long ticks) {
        return format(ticks, MINUTES, HOURS, DAYS);
    }

    public static String format(long ticks, String minutesFmt, String hoursFmt, String daysFmt) {
        long minutes = Math.max(0, ticks) / 1200;       // 20 ticks a second x 60
        long d = minutes / 1440;
        long h = (minutes % 1440) / 60;
        long m = minutes % 60;
        String f = minutes < 60 ? minutesFmt : minutes < 1440 ? hoursFmt : daysFmt;
        return f.replace("{d}", String.valueOf(d)).replace("{h}", String.valueOf(h)).replace("{m}", String.valueOf(m));
    }

    public static String format(long ticks) {
        return format(ticks, MINUTES, HOURS, DAYS);
    }
}
