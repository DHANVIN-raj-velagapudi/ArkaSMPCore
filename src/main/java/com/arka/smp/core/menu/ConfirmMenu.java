package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Two big buttons: yes / no. */
public final class ConfirmMenu {
    private ConfirmMenu() {
    }

    public static void open(ArkaSMPCore plugin, Player p, Component title, Runnable yes, Runnable no) {
        Menu menu = new Menu(title, 3);
        menu.set(11, Items.cfg(plugin, "menus.confirm.yes"), pl -> yes.run());
        menu.set(15, Items.cfg(plugin, "menus.confirm.no"), pl -> no.run());
        menu.fill(Items.filler());
        menu.open(p);
    }
}
