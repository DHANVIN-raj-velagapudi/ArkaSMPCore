package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.claims.ClaimManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ClaimsMenu {
    private ClaimsMenu() {
    }

    public static void open(ArkaSMPCore plugin, Player p) {
        ClaimManager claims = plugin.claims();
        UUID id = p.getUniqueId();
        var players = plugin.players();
        ClaimManager.Claim here = claims.claimAt(p.getLocation());
        Set<UUID> trusted = here != null && here.owner.equals(id) ? here.trusted : Set.of();

        Menu menu = new Menu(plugin.messages().mm(plugin.getConfig().getString("menus.claims.title", "Claims")), 3);
        menu.set(4, Items.cfg(plugin, "menus.claims.info",
                "remaining", String.valueOf(players.remaining(id)), "total", String.valueOf(players.total(id)),
                "used", String.valueOf(players.used(id)),
                "perhour", String.valueOf(plugin.getConfig().getInt("claims.blocks-per-hour", 100))), null);

        menu.set(10, Items.cfg(plugin, "menus.claims.tool"), pl -> {
            pl.closeInventory();
            plugin.claimTool().give(pl);
        });
        if (here != null) {
            menu.set(11, Items.cfg(plugin, "menus.claims.show"), pl -> {
                pl.closeInventory();
                claims.show(pl, here);
            });
            if (here.owner.equals(id)) {
                menu.set(12, Items.cfg(plugin, "menus.claims.delete"), pl -> confirmDelete(plugin, pl, here, () -> open(plugin, pl)));
            }
        }
        menu.set(13, Items.of(org.bukkit.Material.GOLD_INGOT, plugin.messages().mm("<gold><bold>Buy claim blocks"), List.of(
                plugin.messages().mm("<gray>" + plugin.claimBlocks().price() + " rupees per block"))),
                pl -> ClaimBlocksMenu.buy(plugin, pl));
        menu.set(16, Items.of(org.bukkit.Material.EMERALD, plugin.messages().mm("<green><bold>Send claim blocks"), List.of(
                plugin.messages().mm("<gray>Give unused blocks to a player"))),
                pl -> ClaimBlocksMenu.send(plugin, pl));
        menu.set(14, Items.cfg(plugin, "menus.claims.trust"), pl -> pickTrust(plugin, pl));
        menu.set(15, Items.cfg(plugin, "menus.claims.untrust", "trusted", String.valueOf(trusted.size())),
                pl -> pickUntrust(plugin, pl));
        menu.set(22, Items.cfg(plugin, "menus.claims.back"), pl -> MainMenu.open(plugin, pl));
        menu.fill(Items.filler());
        menu.open(p);
    }

    /** Asks "are you sure?" and then removes exactly this claim (checked again, in case it changed meanwhile). */
    public static void confirmDelete(ArkaSMPCore plugin, Player p, ClaimManager.Claim claim, Runnable after) {
        var msg = plugin.messages();
        if (claim == null) {
            msg.send(p, "claim-none-here");
            return;
        }
        if (!claim.owner.equals(p.getUniqueId())) {
            msg.send(p, "claim-not-yours");
            return;
        }
        ConfirmMenu.open(plugin, p, msg.mm("<dark_gray>Delete this claim? (" + claim.area() + " blocks)"), () -> {
            p.closeInventory();
            if (!plugin.claims().claimsOf(p.getUniqueId()).contains(claim)) {
                msg.send(p, "claim-none-here");
            } else {
                plugin.claims().delete(claim);
                msg.send(p, "claim-removed", "area", String.valueOf(claim.area()));
            }
            if (after != null) after.run();
        }, () -> {
            p.closeInventory();
            msg.send(p, "claim-delete-cancelled");
            if (after != null) after.run();
        });
    }

    public static void confirmDeleteAll(ArkaSMPCore plugin, Player p) {
        var msg = plugin.messages();
        int count = plugin.claims().claimsOf(p.getUniqueId()).size();
        if (count == 0) {
            msg.send(p, "claim-none-yours");
            return;
        }
        ConfirmMenu.open(plugin, p, msg.mm("<dark_gray>Delete ALL " + count + " claims?"), () -> {
            p.closeInventory();
            msg.send(p, "claim-removed-all", "count", String.valueOf(plugin.claims().deleteAll(p.getUniqueId())));
        }, () -> {
            p.closeInventory();
            msg.send(p, "claim-delete-cancelled");
        });
    }

    private static void pickTrust(ArkaSMPCore plugin, Player viewer) {
        ClaimManager.Claim here = plugin.claims().claimAt(viewer.getLocation());
        if (here == null) {
            plugin.messages().send(viewer, "trust-not-in-claim");
            return;
        }
        if (!here.owner.equals(viewer.getUniqueId())) {
            plugin.messages().send(viewer, "claim-not-yours");
            return;
        }
        Set<UUID> already = here.trusted;
        List<OfflinePlayer> list = Bukkit.getOnlinePlayers().stream()
                .filter(o -> !o.getUniqueId().equals(viewer.getUniqueId()) && !already.contains(o.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(o -> (OfflinePlayer) o).toList();
        PlayerPicker.open(plugin, viewer, "menus.claims.trust-title", list, 0, (v, target) -> {
            plugin.claims().trust(v, target, false);
            open(plugin, v);
        }, pl -> open(plugin, pl));
    }

    private static void pickUntrust(ArkaSMPCore plugin, Player viewer) {
        ClaimManager.Claim here = plugin.claims().claimAt(viewer.getLocation());
        if (here == null || !here.owner.equals(viewer.getUniqueId())) {
            plugin.messages().send(viewer, here == null ? "trust-not-in-claim" : "claim-not-yours");
            return;
        }
        List<OfflinePlayer> list = here.trusted.stream().map(Bukkit::getOfflinePlayer).toList();
        PlayerPicker.open(plugin, viewer, "menus.claims.untrust-title", list, 0, (v, target) -> {
            plugin.claims().untrust(v, target, false);
            open(plugin, v);
        }, pl -> open(plugin, pl));
    }
}
