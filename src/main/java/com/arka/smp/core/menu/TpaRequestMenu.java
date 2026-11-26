package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.tpa.TpaManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * The window a player sees for ONE incoming teleport request: Deny on the left, the sender's world and exact
 * coordinates, who sent it and what they want, how long is left, and Accept on the right. Texts are in config.yml
 * under menus.tpa-request.
 */
public final class TpaRequestMenu {
    private TpaRequestMenu() {
    }

    private static String path(String key) {
        return "menus.tpa-request." + key;
    }

    /** Opens the window for the request from {@code sender} to {@code viewer}. Tells the viewer when there is none. */
    public static void open(ArkaSMPCore plugin, Player viewer, UUID sender) {
        TpaManager.Request r = plugin.tpa().get(viewer, sender);
        if (r == null) {
            plugin.messages().send(viewer, "tpa-none");
            return;
        }
        Player from = Bukkit.getPlayer(r.from());
        if (from == null) {
            plugin.messages().send(viewer, "unknown-player");
            return;
        }
        open(plugin, viewer, r, from);
    }

    public static void open(ArkaSMPCore plugin, Player viewer, TpaManager.Request r, Player from) {
        var cfg = plugin.getConfig();
        var ms = plugin.messages();
        boolean here = r.type() == TpaManager.Type.TPAHERE;
        Location at = from.getLocation();
        World w = at.getWorld();
        String worldName = w == null ? "?" : w.getName();
        String x = String.valueOf(at.getBlockX()), y = String.valueOf(at.getBlockY()), z = String.valueOf(at.getBlockZ());
        boolean sameWorld = w != null && w.equals(viewer.getWorld());
        String dist = sameWorld ? String.valueOf((long) Math.round(at.distance(viewer.getLocation()))) : "-";
        long secs = Math.max(0, (r.expires() - System.currentTimeMillis() + 999) / 1000);
        String[] kv = {"player", from.getName(), "world", worldName, "dimension", dimension(plugin, w),
                "x", x, "y", y, "z", z, "distance", dist, "seconds", String.valueOf(secs),
                "type", here ? "TPAHERE" : "TPA"};

        String titleKey = here ? "title-tpahere" : "title-tpa";
        Menu menu = new Menu(ms.mm(cfg.getString(path(titleKey), "Incoming " + kv[kv.length - 1] + " request"), kv), 3);

        // Is the spot the walker would land on safe? The same check /rtp and warps use; shown before they click Accept.
        Location landing = here ? at : viewer.getLocation();
        var safety = plugin.tpa().landingCheck(landing);
        String unsafeLine = safety.safe() ? "" : cfg.getString(path("unsafe-warning"), "");

        UUID sender = r.from();
        menu.set(10, Items.cfg(plugin, path("deny"), kv), v -> {
            v.closeInventory();
            plugin.tpa().deny(v, sender);
        });
        menu.set(12, item(plugin, "world", worldMaterial(w), kv, sameWorld ? "same-world" : "other-world", unsafeLine), null);
        menu.set(13, headItem(plugin, from, here ? "wants-tpahere" : "wants-tpa", kv), null);
        menu.set(14, Items.cfg(plugin, path("time"), kv), null);
        ItemStack accept = Items.cfg(plugin, path("accept"), kv);
        if (!safety.safe()) {
            var meta = accept.getItemMeta();
            var lore = new java.util.ArrayList<>(meta.lore() == null ? List.<Component>of() : meta.lore());
            lore.add(ms.mm(cfg.getString(path("unsafe-accept"), ""), kv));
            meta.lore(lore);
            accept.setItemMeta(meta);
        }
        menu.set(16, accept, v -> {
            v.closeInventory();
            plugin.tpa().accept(v, sender);
        });
        menu.fill(Items.filler());
        menu.open(viewer);
        if (!(viewer.getOpenInventory().getTopInventory().getHolder(false) instanceof Menu)) {
            plugin.errors().warn("tpa.window", "Request window did not open: another plugin cancelled the inventory open",
                    "player", viewer.getName(), "from", from.getName(), "window", String.valueOf(viewer.getOpenInventory().getType()));
        }
    }

    /** The world item: icon by dimension, name/lore from config, with the "where is that" line chosen. */
    private static ItemStack item(ArkaSMPCore plugin, String key, Material icon, String[] kv, String distKey,
                                  String unsafeLine) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path(key));
        var ms = plugin.messages();
        Component name = ms.mm(s == null ? " " : s.getString("name", " "), kv);
        List<Component> lore = (s == null ? List.<String>of() : s.getStringList("lore")).stream()
                .filter(l -> !(l.equals("{unsafe-line}") && unsafeLine.isEmpty()))
                .map(l -> ms.mm(l.replace("{distance-line}", plugin.getConfig().getString(path(distKey), ""))
                        .replace("{unsafe-line}", unsafeLine), kv)).toList();
        return Items.of(icon, name, lore);
    }

    private static ItemStack headItem(ArkaSMPCore plugin, Player from, String wantsKey, String[] kv) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path("info"));
        var ms = plugin.messages();
        String wants = plugin.getConfig().getString(path(wantsKey), "");
        Component name = ms.mm(s == null ? " " : s.getString("name", " "), kv);
        List<Component> lore = (s == null ? List.<String>of() : s.getStringList("lore")).stream()
                .map(l -> ms.mm(l.replace("{wants}", wants), kv)).toList();
        return Items.head(from, name, lore);
    }

    private static Material worldMaterial(World w) {
        if (w == null) return Material.GRASS_BLOCK;
        return switch (w.getEnvironment()) {
            case NETHER -> Material.NETHERRACK;
            case THE_END -> Material.END_STONE;
            default -> Material.GRASS_BLOCK;
        };
    }

    private static String dimension(ArkaSMPCore plugin, World w) {
        String key = w == null ? "normal" : switch (w.getEnvironment()) {
            case NETHER -> "nether";
            case THE_END -> "end";
            default -> "normal";
        };
        return plugin.getConfig().getString(path("dimension-" + key), key);
    }
}
