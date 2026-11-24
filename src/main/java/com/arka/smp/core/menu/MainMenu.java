package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import org.bukkit.entity.Player;

public final class MainMenu {
    private MainMenu() {
    }

    public static void open(ArkaSMPCore plugin, Player p) {
        Menu menu = new Menu(plugin.messages().mm(plugin.getConfig().getString("menus.main.title", "Menu")), 3);
        menu.set(10, Items.cfg(plugin, "menus.main.homes"), pl -> HomesMenu.open(plugin, pl, false));
        menu.set(12, Items.cfg(plugin, "menus.main.spawn"), pl -> {
            pl.closeInventory();
            plugin.spawn().teleport(pl);
        });
        menu.set(14, Items.cfg(plugin, "menus.main.tpa"), pl -> TpaMenu.open(plugin, pl));
        menu.set(16, Items.cfg(plugin, "menus.main.rtp"), pl -> {
            pl.closeInventory();
            plugin.rtp().start(pl);
        });
        menu.set(22, Items.cfg(plugin, "menus.main.claims"), pl -> ClaimsMenu.open(plugin, pl));
        menu.fill(Items.filler());
        menu.open(p);
    }
}
