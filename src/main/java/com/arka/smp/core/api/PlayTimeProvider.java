package com.arka.smp.core.api;

import java.util.UUID;

/**
 * Registered through Bukkit's ServicesManager by ArkaSMPCore. Other Arka plugins (ArkaSMPStyle: earned titles)
 * read active play time through it without touching Core internals.
 */
public interface PlayTimeProvider {
    /** Active (non-AFK) play time in ticks; 0 if the player was never seen. */
    long activeTicks(UUID id);
}
