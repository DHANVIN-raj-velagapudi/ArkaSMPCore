package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public final class Items {
    private Items() {
    }

    private static Component plainText(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static ItemStack of(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (name != null) meta.displayName(plainText(name));
        if (lore != null && !lore.isEmpty()) meta.lore(lore.stream().map(Items::plainText).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack filler() {
        return of(org.bukkit.Material.BLACK_STAINED_GLASS_PANE, Component.space(), null);
    }

    /** Build an item from config section "path" (material, name, lore) with {placeholder} values. */
    public static ItemStack cfg(ArkaSMPCore plugin, String path, String... kv) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
        if (s == null) return of(Material.BARRIER, Component.text("Missing config: " + path), null);
        Material material = Material.matchMaterial(s.getString("material", "STONE"));
        if (material == null || !material.isItem()) material = Material.STONE;
        Messages ms = plugin.messages();
        Component name = ms.mm(s.getString("name", " "), kv);
        List<Component> lore = s.getStringList("lore").stream().map(l -> ms.mm(l, kv)).toList();
        return of(material, name, lore);
    }

    public static ItemStack head(OfflinePlayer owner, Component name, List<Component> lore) {
        ItemStack item = of(Material.PLAYER_HEAD, name, lore);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(owner);
        item.setItemMeta(meta);
        return item;
    }
}
