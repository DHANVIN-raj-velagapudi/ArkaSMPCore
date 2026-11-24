package com.arka.smp.core.menu;

import com.arka.smp.core.ArkaSMPCore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Tap-only screens for buying claim blocks and sending them to another player. */
public final class ClaimBlocksMenu {
    private ClaimBlocksMenu() {
    }

    private static List<Long> amounts(ArkaSMPCore plugin, String path, long... def) {
        List<Long> out = new ArrayList<>();
        for (long l : plugin.getConfig().getLongList(path)) if (l > 0 && out.size() < 7) out.add(l);
        if (out.isEmpty()) for (long l : def) out.add(l);
        return out;
    }

    private static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ---- buy ------------------------------------------------------------------------------------

    public static void buy(ArkaSMPCore plugin, Player p) {
        var msg = plugin.messages();
        var cb = plugin.claimBlocks();
        var players = plugin.players();
        Menu menu = new Menu(msg.mm("<dark_gray>Buy claim blocks"), 3);
        List<Long> list = amounts(plugin, "claims.buy.menu-amounts", 100, 500, 1000, 5000);
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        for (int i = 0; i < list.size(); i++) {
            long amount = list.get(i);
            List<Component> lore = List.of(
                    msg.mm("<gray>Cost: <gold>" + num(cb.cost(amount)) + " rupees"),
                    msg.mm("<gray>Price: <white>" + num(cb.price()) + " <gray>per block"),
                    msg.mm(""), msg.mm("<yellow>Click to buy"));
            menu.set(slots[i], Items.of(Material.GOLD_NUGGET, msg.mm("<green><bold>+" + amount + " blocks"), lore),
                    pl -> confirmBuy(plugin, pl, amount));
        }
        menu.set(4, Items.of(Material.GOLDEN_SHOVEL, msg.mm("<gold><bold>Your claim blocks"), List.of(
                msg.mm("<gray>Free: <white>" + players.remaining(p.getUniqueId()) + "<gray>/<white>" + players.total(p.getUniqueId())),
                msg.mm("<gray>Bought so far: <white>" + players.bought(p.getUniqueId())))), null);
        menu.set(22, Items.cfg(plugin, "menus.claims.back"), pl -> ClaimsMenu.open(plugin, pl));
        menu.fill(Items.filler());
        menu.open(p);
    }

    private static void confirmBuy(ArkaSMPCore plugin, Player p, long amount) {
        var msg = plugin.messages();
        double cost = plugin.claimBlocks().cost(amount);
        ConfirmMenu.open(plugin, p, msg.mm("<dark_gray>Buy " + amount + " blocks for " + num(cost) + "?"),
                () -> {
                    p.closeInventory();
                    plugin.claimBlocks().buy(p, amount);
                }, () -> buy(plugin, p));
    }

    // ---- send -----------------------------------------------------------------------------------

    public static void send(ArkaSMPCore plugin, Player p) {
        var msg = plugin.messages();
        String blocker = plugin.claimBlocks().sendBlocker(p);
        if (blocker != null) {
            msg.send(p, blocker, "hours", String.valueOf((long) plugin.claimBlocks().minPlayHours()));
            return;
        }
        List<OfflinePlayer> list = Bukkit.getOnlinePlayers().stream()
                .filter(o -> !o.getUniqueId().equals(p.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .map(o -> (OfflinePlayer) o).toList();
        PlayerPicker.open(plugin, p, "menus.claims.send-title", list, 0, (v, target) -> amount(plugin, v, target),
                pl -> ClaimsMenu.open(plugin, pl));
    }

    private static void amount(ArkaSMPCore plugin, Player p, OfflinePlayer target) {
        var msg = plugin.messages();
        var players = plugin.players();
        String name = target.getName() == null ? "?" : target.getName();
        Menu menu = new Menu(msg.mm("<dark_gray>Send blocks to " + name), 3);
        List<Long> list = amounts(plugin, "claims.transfer.menu-amounts", 50, 100, 500, 1000);
        int[] slots = {10, 11, 12, 13, 14, 15, 16};
        long free = players.remaining(p.getUniqueId());
        for (int i = 0; i < list.size(); i++) {
            long amount = list.get(i);
            boolean ok = amount <= free;
            List<Component> lore = List.of(
                    msg.mm(ok ? "<yellow>Click to send" : "<red>You only have " + free + " free blocks"));
            menu.set(slots[i], Items.of(ok ? Material.LIME_DYE : Material.GRAY_DYE,
                    msg.mm((ok ? "<green><bold>" : "<gray><bold>") + amount + " blocks"), lore),
                    ok ? pl -> confirmSend(plugin, pl, target, amount) : null);
        }
        menu.set(4, Items.of(Material.PAPER, msg.mm("<white>You have <green>" + free + " <white>free blocks"), List.of(
                msg.mm("<gray>Sent today: <white>" + players.sentToday(p.getUniqueId()) + "<gray>/<white>"
                        + plugin.claimBlocks().dailyCap()))), null);
        menu.set(22, Items.cfg(plugin, "menus.claims.back"), pl -> send(plugin, pl));
        menu.fill(Items.filler());
        menu.open(p);
    }

    private static void confirmSend(ArkaSMPCore plugin, Player p, OfflinePlayer target, long amount) {
        String name = target.getName() == null ? "?" : target.getName();
        ConfirmMenu.open(plugin, p, plugin.messages().mm("<dark_gray>Send " + amount + " blocks to " + name + "?"),
                () -> {
                    p.closeInventory();
                    plugin.claimBlocks().send(p, target, amount);
                }, () -> ClaimsMenu.open(plugin, p));
    }
}
