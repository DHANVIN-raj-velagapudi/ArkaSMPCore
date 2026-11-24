package com.arka.smp.core.api;

import java.util.UUID;

/**
 * Registered through Bukkit's ServicesManager by ArkaSMPEconomy so ArkaSMPCore can charge rupees
 * (for example to create a player warp) without depending on the economy plugin.
 */
public interface MoneyCharger {
    enum Result { OK, INSUFFICIENT, FROZEN, ERROR }

    /** Takes rupees from the player's balance. Must be called on the main thread. */
    Result charge(UUID id, double rupees, String reason);
}
