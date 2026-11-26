package com.arka.smp.core.api;

/**
 * Registered through Bukkit's ServicesManager by ArkaSMPEconomy. Turns an amount of rupees into the text the server
 * shows for money: the currency symbol and the short form, without paise ("₹1,250", "₹1.25k", "₹2.5L", "₹1Cr").
 * Paise still exist in balances and shop prices; this text just never shows them. It is the same text as the
 * placeholder %arkaeco_balance_formatted%. Returns null when no economy plugin is installed (the service is missing).
 */
public interface MoneyFormat {
    /** Formats rupees (for example {@code MoneyProvider.balance(id)}); paise are cut off, never rounded up. */
    String format(double rupees);
}
