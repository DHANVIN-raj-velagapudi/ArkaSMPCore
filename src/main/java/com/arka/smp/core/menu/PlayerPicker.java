package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import net.kyori.adventure.text.Component;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Paged grid of player heads. One tap selects, so mobile players never type a name. */
public final class PlayerPicker {
    private static final int PER_PAGE = 28;

    private PlayerPicker() {
    }

    public static void open(ArkaSMPCore plugin, Player viewer, String titlePath, List<OfflinePlayer> list, int page,
                            BiConsumer<Player, OfflinePlayer> onPick, Consumer<Player> back) {
        int pages = Math.max(1, (list.size() + PER_PAGE - 1) / PER_PAGE);
        final int pg = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(plugin.messages().mm(plugin.getConfig().getString(titlePath, "Players")), 6);
        List<Component> lore = plugin.getConfig().getStringList("menus.picker.head-lore").stream()
                .map(l -> plugin.messages().mm(l)).toList();

        for (int i = 0; i < PER_PAGE; i++) {
            int idx = pg * PER_PAGE + i;
            if (idx >= list.size()) break;
            OfflinePlayer target = list.get(idx);
            String name = target.getName() != null ? target.getName() : "?";
            int slot = (1 + i / 7) * 9 + 1 + i % 7;
            menu.set(slot, Items.head(target, plugin.messages().mm("<yellow>{name}", "name", name), lore),
                    p -> onPick.accept(p, target));
        }
        if (list.isEmpty()) menu.set(22, Items.cfg(plugin, "menus.picker.empty"), null);
        if (pg > 0) {
            menu.set(48, Items.cfg(plugin, "menus.picker.prev"),
                    p -> open(plugin, p, titlePath, list, pg - 1, onPick, back));
        }
        if (pg < pages - 1) {
            menu.set(50, Items.cfg(plugin, "menus.picker.next"),
                    p -> open(plugin, p, titlePath, list, pg + 1, onPick, back));
        }
        if (back != null) menu.set(49, Items.cfg(plugin, "menus.picker.back"), back);
        menu.fill(Items.filler());
        menu.open(viewer);
    }
}
