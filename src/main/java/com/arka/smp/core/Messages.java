package com.arka.smp.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/** Config-driven MiniMessage messages. Placeholder values are escaped so players cannot inject tags. */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final ArkaSMPCore plugin;

    public Messages(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    private String template(String key, String[] kv) {
        String raw = plugin.getConfig().getString("messages." + key);
        if (raw == null) raw = "<red>Missing message: " + key;
        return fill(raw, kv);
    }

    private static String fill(String raw, String[] kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", MM.escapeTags(kv[i + 1]));
        }
        return raw;
    }

    /** Parse any MiniMessage string with placeholders. */
    public Component mm(String raw, String... kv) {
        return MM.deserialize(fill(raw, kv));
    }

    /** Message without prefix (action bars, menus). */
    public Component plain(String key, String... kv) {
        return MM.deserialize(template(key, kv));
    }

    /** Message with prefix. */
    public Component get(String key, String... kv) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        return MM.deserialize(prefix + template(key, kv));
    }

    public void send(CommandSender to, String key, String... kv) {
        to.sendMessage(get(key, kv));
    }

    /** Prefixed message from a literal MiniMessage string (admin output). */
    public void sendRaw(CommandSender to, String miniMessage, String... kv) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        to.sendMessage(MM.deserialize(prefix + fill(miniMessage, kv)));
    }

    public void broadcast(String key, String... kv) {
        Bukkit.broadcast(get(key, kv));
    }
}
