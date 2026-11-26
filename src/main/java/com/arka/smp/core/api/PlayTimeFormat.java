package com.arka.smp.core.api;

/**
 * Registered through Bukkit's ServicesManager by ArkaSMPCore. Turns a play time in ticks into compact text with
 * lowercase units only: under 1 hour "45m", from 1 hour "3h 20m", from 24 hours "2d 5h" (no minutes). The wording is
 * fixed; the placeholder %arka_playtime% and the leaderboard use the wording from Core's config.yml ("playtime").
 */
public interface PlayTimeFormat {
    /** Formats ticks (20 per second), for example {@code PlayTimeProvider.activeTicks(id)}. */
    String format(long ticks);
}
