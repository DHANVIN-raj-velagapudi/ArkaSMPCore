package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.Locs;
import com.arka.smp.core.warps.WarpManager.Warp;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Tap-only lists of server warps (/warp) and player warps (/pwarp), 28 per page. */
public final class WarpMenu {
    private static final int PER_PAGE = 28;

    private WarpMenu() {
    }

    public static void openServer(ArkaSMPCore plugin, Player p, int page) {
        open(plugin, p, plugin.warps().serverWarps(), page, true);
    }

    public static void openPlayers(ArkaSMPCore plugin, Player p, int page) {
        open(plugin, p, plugin.warps().playerWarps(), page, false);
    }

    private static void open(ArkaSMPCore plugin, Player p, List<Warp> all, int page, boolean server) {
        var msg = plugin.messages();
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        String title = plugin.getConfig().getString(server ? "menus.warps.title" : "menus.pwarps.title",
                server ? "Server warps" : "Player warps");
        Menu menu = new Menu(msg.mm(title), 6);
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < all.size(); i++) {
            Warp w = all.get(from + i);
            int slot = 10 + (i / 7) * 9 + i % 7;
            menu.set(slot, icon(plugin, w), pl -> {
                pl.closeInventory();
                plugin.warps().teleport(pl, w);
            });
        }
        int pg = page;
        if (page > 0) {
            menu.set(45, Items.of(Material.ARROW, msg.mm("<yellow>Previous page"), null),
                    pl -> open(plugin, pl, all, pg - 1, server));
        }
        if (page < pages - 1) {
            menu.set(53, Items.of(Material.ARROW, msg.mm("<yellow>Next page"), null),
                    pl -> open(plugin, pl, all, pg + 1, server));
        }
        List<Component> lore = new ArrayList<>();
        if (server) {
            lore.add(msg.mm("<gray>Warps set up by the staff."));
        } else {
            lore.add(msg.mm("<gray>You have <white>" + plugin.warps().warpsOf(p.getUniqueId()).size()
                    + "<gray>/<white>" + plugin.warps().limit(p) + " <gray>warps."));
            lore.add(msg.mm("<gray>Make one: <white>/pwarp set <name>"));
        }
        lore.add(msg.mm("<dark_gray>Page " + (page + 1) + "/" + pages));
        menu.set(49, Items.of(server ? Material.COMPASS : Material.PLAYER_HEAD,
                msg.mm(server ? "<aqua><bold>Server warps" : "<aqua><bold>Player warps"), lore), null);
        if (all.isEmpty()) {
            menu.set(22, Items.of(Material.BARRIER, msg.mm("<gray>No warps yet"), null), null);
        }
        menu.fill(Items.filler());
        menu.open(p);
    }

    private static ItemStack icon(ArkaSMPCore plugin, Warp w) {
        var msg = plugin.messages();
        Location l = Locs.parse(w.loc);
        List<Component> lore = new ArrayList<>();
        Component name = msg.mm((w.unsafeReason != null ? "<red>" : "<green>") + "<bold>" + w.name);
        if (!w.server()) lore.add(msg.mm("<gray>By <white>" + w.ownerName));
        if (l != null) {
            lore.add(msg.mm("<gray>" + com.arka.smp.core.warps.WarpManager.worldKind(l.getWorld()).replace("another world", l.getWorld().getName())
                    + " <dark_gray>(" + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + ")"));
        }
        if (w.visits > 0) lore.add(msg.mm("<gray>Visits: <white>" + w.visits));
        if (w.unsafeReason != null) lore.add(msg.mm("<red>Last check failed: " + w.unsafeReason.toLowerCase()));
        lore.add(msg.mm(""));
        lore.add(msg.mm("<yellow>Click to teleport"));
        if (!w.server()) return Items.head(Bukkit.getOfflinePlayer(w.owner), name, lore);
        Material m = Material.ENDER_PEARL;
        if (l != null) {
            m = switch (l.getWorld().getEnvironment()) {
                case NETHER -> Material.NETHERRACK;
                case THE_END -> Material.END_STONE;
                default -> Material.GRASS_BLOCK;
            };
        }
        return Items.of(m, name, lore);
    }
}
