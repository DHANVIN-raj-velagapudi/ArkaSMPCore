package com.arka.smp.core.leaderboard;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.api.MoneyProvider;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/**
 * Reads balances from ArkaSMPEconomy (MoneyProvider service) when present, otherwise from any
 * Vault economy through reflection, so Vault is not needed at compile time.
 */
final class VaultEconomy {
    private final ArkaSMPCore plugin;
    private MoneyProvider arka;
    private Object provider;
    private Method getBalance;
    private long lastLookup;

    VaultEconomy(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    boolean available() {
        if (arka != null || provider != null) return true;
        long now = System.currentTimeMillis();
        if (now - lastLookup < 5000) return false;
        lastLookup = now;
        RegisteredServiceProvider<MoneyProvider> own =
                plugin.getServer().getServicesManager().getRegistration(MoneyProvider.class);
        if (own != null) {
            arka = own.getProvider();
            return true;
        }
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<Object> economy = (Class) Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<Object> rsp = plugin.getServer().getServicesManager().getRegistration(economy);
            if (rsp == null) return false;
            provider = rsp.getProvider();
            getBalance = economy.getMethod("getBalance", OfflinePlayer.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    double balance(OfflinePlayer p) {
        if (!available()) return 0;
        try {
            if (arka != null) return arka.balance(p.getUniqueId());
            return ((Number) getBalance.invoke(provider, p)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }
}
