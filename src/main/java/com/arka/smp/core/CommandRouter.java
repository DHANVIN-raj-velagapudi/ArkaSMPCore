package com.arka.smp.core;

import com.arka.smp.core.menu.ClaimBlocksMenu;
import com.arka.smp.core.menu.ClaimsMenu;
import com.arka.smp.core.menu.HomesMenu;
import com.arka.smp.core.menu.MainMenu;
import com.arka.smp.core.menu.StatsMenu;
import com.arka.smp.core.menu.TpaMenu;
import com.arka.smp.core.menu.WarpMenu;
import com.arka.smp.core.tpa.TpaManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class CommandRouter implements CommandExecutor, TabCompleter {
    private static final List<String> CLAIM_SUBS = List.of("tool", "info", "blocks", "list", "show", "delete",
            "deleteall", "trust", "untrust", "trustlist", "buy", "send");
    private static final List<String> ARKA_SUBS = List.of("menu", "reload", "clear", "admin", "version", "stats");

    private final ArkaSMPCore plugin;
    private final AdminCommands admin;

    public CommandRouter(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.admin = new AdminCommands(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Messages msg = plugin.messages();
        String name = cmd.getName().toLowerCase(Locale.ROOT);

        // Commands usable from console
        switch (name) {
            case "lag" -> {
                if (!sender.hasPermission("arka.lag")) {
                    msg.send(sender, "no-permission");
                    return true;
                }
                lag(sender);
                return true;
            }
            case "arka" -> {
                if (args.length == 0 && sender instanceof Player p) {
                    MainMenu.open(plugin, p);
                    return true;
                }
                arka(sender, args);
                return true;
            }
            default -> {
            }
        }

        if (!(sender instanceof Player p)) {
            msg.send(sender, "player-only");
            return true;
        }

        switch (name) {
            case "sethome" -> plugin.homes().setHome(p, args.length > 0 ? args[0] : "home");
            case "home" -> {
                if (args.length > 0) {
                    plugin.homes().teleportHome(p, args[0]);
                } else {
                    HomesMenu.open(plugin, p, false);
                }
            }
            case "homes" -> HomesMenu.open(plugin, p, false);
            case "delhome" -> {
                if (args.length == 0) HomesMenu.open(plugin, p, true);
                else plugin.homes().deleteHome(p, args[0]);
            }
            case "afk" -> plugin.afk().toggle(p);
            case "spawn" -> plugin.spawn().teleport(p);
            case "setspawn" -> {
                if (!p.hasPermission("arka.admin")) {
                    msg.send(p, "no-permission");
                } else {
                    plugin.spawn().set(p.getLocation());
                    msg.send(p, "spawn-set");
                }
            }
            case "tpa", "tpahere" -> {
                if (args.length == 0) {
                    TpaMenu.open(plugin, p);
                    break;
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) msg.send(p, "unknown-player");
                else plugin.tpa().send(p, target, name.equals("tpa") ? TpaManager.Type.TPA : TpaManager.Type.TPAHERE);
            }
            case "tpaccept", "tpdeny" -> {
                UUID from = null;
                if (args.length > 0) {
                    Player s = Bukkit.getPlayerExact(args[0]);
                    if (s == null) {
                        msg.send(p, "unknown-player");
                        break;
                    }
                    from = s.getUniqueId();
                }
                if (name.equals("tpaccept")) plugin.tpa().accept(p, from);
                else plugin.tpa().deny(p, from);
            }
            case "tpcancel" -> plugin.tpa().cancel(p);
            case "rtp" -> plugin.rtp().start(p);
            case "trapped" -> plugin.trapped().start(p);
            case "warp" -> {
                if (args.length == 0) {
                    WarpMenu.openServer(plugin, p, 0);
                    break;
                }
                var w = plugin.warps().serverWarp(args[0]);
                if (w == null) msg.send(p, "warp-missing", "name", args[0]);
                else plugin.warps().teleport(p, w);
            }
            case "setwarp", "delwarp" -> {
                if (!p.hasPermission("arka.warp.admin")) {
                    msg.send(p, "no-permission");
                } else if (args.length == 0) {
                    msg.send(p, "warp-usage-admin");
                } else if (name.equals("delwarp")) {
                    plugin.warps().deleteServerWarp(p, args[0]);
                } else {
                    plugin.warps().createServerWarp(p, args[0], args.length > 1 && args[1].equalsIgnoreCase("force"));
                }
            }
            case "pwarp" -> pwarp(p, args);
            case "unclaim" -> confirmUnclaim(p);
            case "claim" -> claim(p, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void pwarp(Player p, String[] args) {
        Messages msg = plugin.messages();
        var warps = plugin.warps();
        if (args.length == 0) {
            WarpMenu.openPlayers(plugin, p, 0);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set", "create" -> {
                if (args.length < 2) msg.send(p, "pwarp-usage");
                else warps.createPlayerWarp(p, args[1]);
            }
            case "delete", "del", "remove" -> {
                if (args.length < 2) msg.send(p, "pwarp-usage");
                else warps.deletePlayerWarp(p, args[1]);
            }
            case "list" -> {
                StringBuilder sb = new StringBuilder();
                for (var w : warps.warpsOf(p.getUniqueId())) sb.append(w.name).append(' ');
                msg.send(p, "pwarp-list", "used", String.valueOf(warps.warpsOf(p.getUniqueId()).size()),
                        "limit", String.valueOf(warps.limit(p)), "list", sb.length() == 0 ? "-" : sb.toString().trim());
            }
            case "info" -> {
                var w = args.length > 1 ? warps.playerWarp(args[1]) : null;
                if (w == null) {
                    msg.send(p, "warp-missing", "name", args.length > 1 ? args[1] : "?");
                } else {
                    msg.send(p, "pwarp-info", "name", w.name, "owner", w.ownerName, "visits", String.valueOf(w.visits),
                            "state", w.unsafeReason == null ? "ok" : "unsafe (" + w.unsafeReason.toLowerCase(Locale.ROOT) + ")");
                }
            }
            case "help" -> msg.send(p, "pwarp-usage");
            default -> {
                var w = warps.playerWarp(args[0]);
                if (w == null) msg.send(p, "warp-missing", "name", args[0]);
                else warps.teleport(p, w);
            }
        }
    }

    private void claim(Player p, String[] args) {
        Messages msg = plugin.messages();
        var claims = plugin.claims();
        var players = plugin.players();
        if (args.length == 0) {
            ClaimsMenu.open(plugin, p);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "tool" -> plugin.claimTool().give(p);
            case "delete", "remove", "unclaim" -> confirmUnclaim(p);
            case "deleteall" -> ClaimsMenu.confirmDeleteAll(plugin, p);
            case "buy" -> {
                if (args.length < 2) {
                    ClaimBlocksMenu.buy(plugin, p);
                } else {
                    Long n = parseNumber(args[1]);
                    if (n == null) msg.send(p, "cb-usage");
                    else plugin.claimBlocks().buy(p, n);
                }
            }
            case "send" -> {
                if (args.length < 3) {
                    ClaimBlocksMenu.send(plugin, p);
                } else {
                    Long n = parseNumber(args[2]);
                    OfflinePlayer target = lookup(args[1]);
                    if (n == null) msg.send(p, "cb-usage");
                    else if (target == null) msg.send(p, "unknown-player");
                    else plugin.claimBlocks().send(p, target, n);
                }
            }
            case "show" -> {
                var c = claims.claimAt(p.getLocation());
                if (c == null) msg.send(p, "claim-none-here");
                else claims.show(p, c);
            }
            case "blocks" -> {
                UUID id = p.getUniqueId();
                msg.send(p, "claim-blocks", "remaining", String.valueOf(players.remaining(id)),
                        "total", String.valueOf(players.total(id)), "used", String.valueOf(players.used(id)),
                        "perhour", String.valueOf(plugin.getConfig().getInt("claims.blocks-per-hour", 100)));
            }
            case "info" -> {
                var c = claims.claimAt(p.getLocation());
                if (c == null) msg.send(p, "claim-none-here");
                else msg.send(p, "claim-info", "owner", claims.ownerName(c.owner), "w", String.valueOf(c.width()),
                        "l", String.valueOf(c.length()), "area", String.valueOf(c.area()));
            }
            case "list" -> {
                StringBuilder sb = new StringBuilder();
                for (var c : claims.claimsOf(p.getUniqueId())) {
                    sb.append(c.world).append(" (").append(c.minX).append(",").append(c.minZ).append(")-(")
                            .append(c.maxX).append(",").append(c.maxZ).append(")  ");
                }
                msg.sendRaw(p, "<gray>{list}", "list", sb.length() == 0 ? "-" : sb.toString());
            }
            case "trust", "untrust" -> {
                if (args.length < 2) {
                    ClaimsMenu.open(plugin, p);
                    return;
                }
                OfflinePlayer target = lookup(args[1]);
                if (target == null) {
                    msg.send(p, "unknown-player");
                    return;
                }
                boolean all = args.length > 2 && args[2].equalsIgnoreCase("all");
                if (args[0].equalsIgnoreCase("trust")) claims.trust(p, target, all);
                else claims.untrust(p, target, all);
            }
            case "trustlist" -> {
                var here = claims.claimAt(p.getLocation());
                if (here == null) {
                    msg.send(p, "trust-not-in-claim");
                    break;
                }
                StringBuilder sb = new StringBuilder();
                for (UUID id : here.trusted) sb.append(claims.ownerName(id)).append(' ');
                msg.sendRaw(p, "<gray>Trusted in this claim: <white>{list}", "list", sb.length() == 0 ? "-" : sb.toString());
            }
            default -> ClaimsMenu.open(plugin, p);
        }
    }

    private static Long parseNumber(String raw) {
        try {
            long v = Long.parseLong(raw);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** /unclaim and /claim delete: show a yes/no screen first (config claims.confirm-delete). */
    private void confirmUnclaim(Player p) {
        var claim = plugin.claims().claimAt(p.getLocation());
        if (!plugin.getConfig().getBoolean("claims.confirm-delete", true)) {
            plugin.claims().deleteHere(p, false);
            return;
        }
        ClaimsMenu.confirmDelete(plugin, p, claim, null);
    }

    private OfflinePlayer lookup(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    private void arka(CommandSender sender, String[] args) {
        Messages msg = plugin.messages();
        String sub = args.length == 0 ? "version" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("arka.admin")) {
                    msg.send(sender, "no-permission");
                    return;
                }
                plugin.reload();
                msg.send(sender, "reload-done");
            }
            case "clear" -> {
                if (!sender.hasPermission("arka.admin")) {
                    msg.send(sender, "no-permission");
                    return;
                }
                plugin.cleaner().clear(true);
            }
            case "stats" -> {
                if (!sender.hasPermission("arka.stats")) {
                    msg.send(sender, "no-permission");
                    return;
                }
                if (sender instanceof Player viewer && !(args.length > 1 && args[1].equalsIgnoreCase("text"))) {
                    StatsMenu.open(plugin, viewer);       // the dashboard; "/arka stats text" prints the same numbers in chat
                    return;
                }
                var st = plugin.stats().snapshot();
                msg.sendRaw(sender, "<gold><bold>Server statistics");
                msg.sendRaw(sender, "<gray>Players ever joined: <white>{a}<gray> | online now: <white>{b}<gray> | record: <white>{c}",
                        "a", String.valueOf(st.totalJoined()), "b", String.valueOf(st.online()), "c", String.valueOf(st.peak()));
                msg.sendRaw(sender, "<gray>Active this week (over {m} min): <white>{a}<gray> | last 24h: <white>{b}<gray> | last 30 days: <white>{c}",
                        "m", String.valueOf(plugin.getConfig().getInt("stats.weekly-active-minutes", 60)),
                        "a", String.valueOf(st.weeklyActive()), "b", String.valueOf(st.dailyActive()),
                        "c", String.valueOf(st.monthlyActive()));
                msg.sendRaw(sender, "<gray>New players this week: <white>{a}<gray> | hours played this week: <white>{b}<gray> | avg per active player: <white>{c}h",
                        "a", String.valueOf(st.newThisWeek()), "b", String.format(Locale.ROOT, "%.0f", st.weekHours()),
                        "c", String.format(Locale.ROOT, "%.1f", st.avgWeekHours()));
                msg.sendRaw(sender, "<gray>Returning after 7+ days away (this week): <white>{a}<gray> | 7-day retention: <white>{b}<gray> (of {c} new players)",
                        "a", String.valueOf(st.returning()),
                        "b", st.retention() < 0 ? "not enough data yet" : String.format(Locale.ROOT, "%.0f%%", st.retention()),
                        "c", String.valueOf(st.cohort()));
                msg.sendRaw(sender, "<gray>Busiest hour: <white>{a}<gray> | quietest hour (best for restarts): <white>{b}<gray> | peak today: <white>{c}",
                        "a", st.busiestHour(), "b", st.quietestHour(), "c", String.valueOf(st.peakToday()));
                StringBuilder sb = new StringBuilder();
                for (var d : plugin.stats().recentPeaks(7).entrySet()) {
                    sb.append(java.time.LocalDate.ofEpochDay(d.getKey()).getDayOfWeek().name(), 0, 3).append(' ')
                            .append(d.getValue()).append("  ");
                }
                msg.sendRaw(sender, "<gray>Peak online, last days: <white>{a}", "a", sb.length() == 0 ? "-" : sb.toString().trim());
            }
            case "admin" -> admin.handle(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
            case "menu" -> {
                if (sender instanceof Player p) MainMenu.open(plugin, p);
                else msg.send(sender, "player-only");
            }
            default -> sender.sendMessage(plugin.messages().mm("<gold>ArkaSMPCore <white>{v}", "v",
                    plugin.getPluginMeta().getVersion()));
        }
    }

    private void lag(CommandSender sender) {
        Messages msg = plugin.messages();
        double[] tps = Bukkit.getTPS();
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory() >> 20;
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        msg.send(sender, "lag-line", "tps", String.format(Locale.ROOT, "%.1f", Math.min(20.0, tps[0])),
                "mspt", String.format(Locale.ROOT, "%.1f", Bukkit.getAverageTickTime()),
                "used", String.valueOf(used), "max", String.valueOf(max));
        for (World w : Bukkit.getWorlds()) {
            msg.send(sender, "lag-world", "world", w.getName(),
                    "chunks", String.valueOf(w.getLoadedChunks().length),
                    "entities", String.valueOf(w.getEntityCount()),
                    "items", String.valueOf(w.getEntitiesByClass(Item.class).size()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            switch (name) {
                case "home", "delhome" -> {
                    if (sender instanceof Player p) out.addAll(plugin.homes().names(p.getUniqueId()));
                }
                case "tpa", "tpahere", "tpaccept", "tpdeny" -> Bukkit.getOnlinePlayers().forEach(o -> out.add(o.getName()));
                case "claim" -> out.addAll(CLAIM_SUBS);
                case "warp" -> out.addAll(plugin.warps().serverNames());
                case "delwarp" -> out.addAll(plugin.warps().serverNames());
                case "pwarp" -> {
                    out.addAll(List.of("set", "delete", "list", "info"));
                    out.addAll(plugin.warps().playerNames());
                }
                case "arka" -> out.addAll(ARKA_SUBS);
                default -> {
                }
            }
        } else if (name.equals("arka") && args[0].equalsIgnoreCase("admin") && sender.hasPermission("arka.admin")) {
            if (args.length == 2) out.addAll(AdminCommands.SUBS);
            else if (args.length == 3 && args[1].equalsIgnoreCase("claimblocks")) out.addAll(List.of("get", "add", "set", "take"));
            else if (args.length == 3 && args[1].equalsIgnoreCase("homelimit")) out.addAll(List.of("get", "set", "reset"));
            else Bukkit.getOnlinePlayers().forEach(o -> out.add(o.getName()));
        } else if (args.length == 2 && name.equals("arka") && args[0].equalsIgnoreCase("stats")) {
            out.add("text");
        } else if (args.length == 2 && name.equals("pwarp") && (args[0].equalsIgnoreCase("delete")
                || args[0].equalsIgnoreCase("info"))) {
            out.addAll(plugin.warps().playerNames());
        } else if (args.length == 2 && name.equals("setwarp")) {
            out.add("force");
        } else if (args.length == 2 && name.equals("claim")
                && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust")
                || args[0].equalsIgnoreCase("send"))) {
            Bukkit.getOnlinePlayers().forEach(o -> out.add(o.getName()));
        }
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
