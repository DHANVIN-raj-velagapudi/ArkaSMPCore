package com.arka.smp.core.api;

import java.util.UUID;

/**
 * Registered through Bukkit's ServicesManager by ArkaSMPEconomy. ArkaSMPCore reads balances
 * through it (money leaderboard) without depending on the economy plugin.
 */
public interface MoneyProvider {
    /** Balance in rupees (may have 2 decimals); 0 if the player has no account. */
    double balance(UUID id);
}
