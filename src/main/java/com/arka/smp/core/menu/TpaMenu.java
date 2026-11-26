package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.tpa.TpaManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class TpaMenu {
    private TpaMenu() {
    }

    public static void open(ArkaSMPCore plugin, Player p) {
        Menu menu = new Menu(plugin.messages().mm(plugin.getConfig().getString("menus.tpa.title", "TPA")), 3);
        int count = plugin.tpa().incoming(p.getUniqueId()).size();
        menu.set(11, Items.cfg(plugin, "menus.tpa.players"), pl -> pickTarget(plugin, pl));
        menu.set(15, Items.cfg(plugin, "menus.tpa.requests", "count", String.valueOf(count)),
                pl -> requests(plugin, pl));
        menu.set(22, Items.cfg(plugin, "menus.homes.back"), pl -> MainMenu.open(plugin, pl));
        menu.fill(Items.filler());
        menu.open(p);
    }

    private static void pickTarget(ArkaSMPCore plugin, Player viewer) {
        List<OfflinePlayer> list = Bukkit.getOnlinePlayers().stream()
                .filter(o -> !o.getUniqueId().equals(viewer.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(o -> (OfflinePlayer) o).toList();
        PlayerPicker.open(plugin, viewer, "menus.tpa.pick-title", list, 0,
                (v, target) -> choose(plugin, v, target), pl -> open(plugin, pl));
    }

    private static void choose(ArkaSMPCore plugin, Player viewer, OfflinePlayer target) {
        String name = target.getName() != null ? target.getName() : "?";
        Menu menu = new Menu(plugin.messages().mm(plugin.getConfig().getString("menus.tpa.choose-title", "{player}"),
                "player", name), 3);
        menu.set(11, Items.cfg(plugin, "menus.tpa.tpto", "player", name), v -> send(plugin, v, target, TpaManager.Type.TPA));
        menu.set(15, Items.cfg(plugin, "menus.tpa.tphere", "player", name), v -> send(plugin, v, target, TpaManager.Type.TPAHERE));
        menu.set(22, Items.cfg(plugin, "menus.homes.back"), v -> pickTarget(plugin, v));
        menu.fill(Items.filler());
        menu.open(viewer);
    }

    private static void send(ArkaSMPCore plugin, Player viewer, OfflinePlayer target, TpaManager.Type type) {
        viewer.closeInventory();
        Player online = Bukkit.getPlayer(target.getUniqueId());
        if (online == null) {
            plugin.messages().send(viewer, "unknown-player");
            return;
        }
        plugin.tpa().send(viewer, online, type);
    }

    private static void requests(ArkaSMPCore plugin, Player viewer) {
        List<OfflinePlayer> senders = plugin.tpa().incoming(viewer.getUniqueId()).stream()
                .map(r -> Bukkit.getOfflinePlayer(r.from())).toList();
        PlayerPicker.open(plugin, viewer, "menus.tpa.requests-title", senders, 0,
                (v, sender) -> TpaRequestMenu.open(plugin, v, sender.getUniqueId()), pl -> open(plugin, pl));
    }

}
