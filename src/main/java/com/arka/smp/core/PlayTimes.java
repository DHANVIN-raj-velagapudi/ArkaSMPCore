package com.arka.smp.core;

/**
 * Play time as text. Below 1 hour only minutes ("45M"), from 1 hour hours and minutes ("3HR 20M"), from 24 hours days
 * and hours without minutes ("2 day 5HRS"). The three wordings use {d} {h} {m}. No server needed.
 */
public final class PlayTimes {
    private PlayTimes() {
    }

    public static final String MINUTES = "{m}M";
    public static final String HOURS = "{h}HR {m}M";
    public static final String DAYS = "{d} day {h}HRS";

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
