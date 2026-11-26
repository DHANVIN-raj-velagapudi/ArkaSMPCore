package com.arka.smp.core.api;

/**
 * Registered through Bukkit's ServicesManager by ArkaSMPCore. Turns a play time in ticks into the text the server
 * shows everywhere: under 1 hour "45M", from 1 hour "3HR 20M", from 24 hours "2 day 5HRS" (no minutes). The
 * wording is set in Core's config.yml under "playtime".
 */
public interface PlayTimeFormat {
    /** Formats ticks (20 per second), for example {@code PlayTimeProvider.activeTicks(id)}. */
    String format(long ticks);
}
