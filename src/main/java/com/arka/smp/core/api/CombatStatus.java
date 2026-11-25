package com.arka.smp.core.api;

import java.util.UUID;

/**
 * Registered through Bukkit's ServicesManager by ArkaSMPCore. Other Arka plugins (ArkaSMPStyle: rank commands
 * such as /anvil and /ec are refused in combat) ask Core's own PvP combat lock instead of tracking combat twice.
 */
public interface CombatStatus {
    /** True while the player is locked in PvP combat (see afk.combat-lock-seconds). */
    boolean inCombat(UUID id);
}
