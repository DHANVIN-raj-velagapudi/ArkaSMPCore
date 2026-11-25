package com.arka.smp.core.api;

import java.util.UUID;

/**
 * Registered through Bukkit's ServicesManager by ArkaSMPCore. ArkaSMPStyle uses it to hand out the monthly
 * bonus claim blocks of the paid ranks. Bonus blocks are permanent, like blocks sent by another player.
 */
public interface ClaimBlockBonus {
    /** Adds permanent bonus claim blocks. Must be called on the main thread. */
    void add(UUID id, long blocks);
}
