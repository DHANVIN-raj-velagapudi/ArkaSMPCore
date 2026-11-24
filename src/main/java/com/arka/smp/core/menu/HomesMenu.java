package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.homes.HomeManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** One tap to teleport, one tap on an empty slot to set a home. No typing needed. */
public final class HomesMenu {
    private static final int MAX_SLOTS = 28;

    private HomesMenu() {
    }

    public static void open(ArkaSMPCore plugin, Player p, boolean deleteMode) {
        HomeManager homes = plugin.homes();
        List<String> names = new ArrayList<>(homes.names(p.getUniqueId()));
        int limit = homes.limit(p);
        int slots = Math.max(1, Math.min(Math.max(limit, names.size()), MAX_SLOTS));
        int inner = (slots + 6) / 7;
        int rows = inner + 2;

        Menu menu = new Menu(plugin.messages().mm(plugin.getConfig().getString("menus.homes.title", "Homes")), rows);
        for (int i = 0; i < slots; i++) {
            int slot = (1 + i / 7) * 9 + 1 + i % 7;
            if (i < names.size()) {
                String name = names.get(i);
                Location l = homes.get(p.getUniqueId(), name);
                String world = l == null ? "?" : l.getWorld().getName();
                String[] kv = {"name", name, "world", world,
                        "x", l == null ? "?" : String.valueOf(l.getBlockX()),
                        "y", l == null ? "?" : String.valueOf(l.getBlockY()),
                        "z", l == null ? "?" : String.valueOf(l.getBlockZ())};
                if (deleteMode) {
                    menu.set(slot, Items.cfg(plugin, "menus.homes.home-delete", kv), pl -> ConfirmMenu.open(plugin, pl,
                            plugin.messages().mm(plugin.getConfig().getString("menus.homes.confirm-title", "Delete?"),
                                    "name", name),
                            () -> {
                                homes.deleteHome(pl, name);
                                open(plugin, pl, true);
                            }, () -> open(plugin, pl, true)));
                } else {
                    menu.set(slot, Items.cfg(plugin, "menus.homes.home", kv), pl -> {
                        pl.closeInventory();
                        homes.teleportHome(pl, name);
                    });
                }
            } else if (!deleteMode) {
                menu.set(slot, Items.cfg(plugin, "menus.homes.empty"), pl -> {
                    Set<String> existing = homes.names(pl.getUniqueId());
                    int n = 1;
                    while (existing.contains("home" + n)) n++;
                    homes.setHome(pl, "home" + n);
                    open(plugin, pl, false);
                });
            }
        }

        int last = (rows - 1) * 9;
        menu.set(last + 1, Items.cfg(plugin, "menus.homes.info",
                "used", String.valueOf(names.size()), "limit", String.valueOf(limit)), null);
        menu.set(last + 4, Items.cfg(plugin, "menus.homes.back"), pl -> MainMenu.open(plugin, pl));
        if (!names.isEmpty()) {
            menu.set(last + 7, Items.cfg(plugin, deleteMode ? "menus.homes.delete-off" : "menus.homes.delete-on"),
                    pl -> open(plugin, pl, !deleteMode));
        }
        menu.fill(Items.filler());
        menu.open(p);
    }
}
