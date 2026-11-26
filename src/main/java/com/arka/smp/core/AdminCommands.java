package com.arka.smp.core;

import com.arka.smp.core.claims.ClaimManager;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** /arka admin ... (permission arka.admin). */
public final class AdminCommands {
    public static final List<String> SUBS = List.of("claimblocks", "homelimit", "claims", "deleteclaim",
            "deleteclaims", "claimtp", "homes", "home", "delhome", "resetcooldown", "leaderboard", "afk", "bypass", "kit", "errors", "get", "set", "help");

    private final ArkaSMPCore plugin;

    public AdminCommands(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    private void say(CommandSender s, String mm, String... kv) {
        plugin.messages().sendRaw(s, mm, kv);
    }

    private OfflinePlayer target(CommandSender s, String name) {
        OfflinePlayer op = plugin.players().find(name);
        if (op == null) plugin.messages().send(s, "unknown-player");
        return op;
    }

    private static Long number(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void handle(CommandSender s, String[] a) {
        if (!s.hasPermission("arka.admin")) {
            plugin.messages().send(s, "no-permission");
            return;
        }
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "bypass" -> {
                if (!(s instanceof Player p)) {
                    say(s, "<red>Players only.");
                    return;
                }
                if (!p.hasPermission("arka.claims.bypass")) {
                    plugin.messages().send(p, "no-permission");
                    return;
                }
                boolean on = plugin.claims().toggleBypass(p);
                plugin.messages().send(p, on ? "claim-bypass-on" : "claim-bypass-off");
            }
            case "claimblocks" -> claimBlocks(s, a);
            case "homelimit" -> homeLimit(s, a);
            case "claims" -> {
                if (a.length < 2) {
                    say(s, "<red>/arka admin claims <player>");
                    return;
                }
                OfflinePlayer t = target(s, a[1]);
                if (t == null) return;
                var list = plugin.claims().claimsOf(t.getUniqueId());
                say(s, "<gold>{p} <gray>has <white>{n} <gray>claims:", "p", a[1], "n", String.valueOf(list.size()));
                for (var c : list) {
                    say(s, "<gray> #{id} <white>{world} <gray>({x1},{z1}) to ({x2},{z2}) = <white>{area} <gray>blocks",
                            "id", String.valueOf(c.id), "world", c.world, "x1", String.valueOf(c.minX),
                            "z1", String.valueOf(c.minZ), "x2", String.valueOf(c.maxX), "z2", String.valueOf(c.maxZ),
                            "area", String.valueOf(c.area()));
                }
            }
            case "deleteclaim" -> {
                ClaimManager.Claim c = null;
                if (a.length >= 2) {
                    Long id = number(a[1]);
                    if (id != null) c = plugin.claims().byId(id.intValue());
                } else if (s instanceof Player p) {
                    c = plugin.claims().claimAt(p.getLocation());
                }
                if (c == null) {
                    say(s, "<red>No claim found. Stand in one or use /arka admin deleteclaim <id>.");
                    return;
                }
                plugin.claims().delete(c);
                say(s, "<green>Deleted claim #{id} of {owner}.", "id", String.valueOf(c.id),
                        "owner", plugin.claims().ownerName(c.owner));
            }
            case "deleteclaims" -> {
                if (a.length < 2) {
                    say(s, "<red>/arka admin deleteclaims <player>");
                    return;
                }
                OfflinePlayer t = target(s, a[1]);
                if (t == null) return;
                int n = plugin.claims().deleteAll(t.getUniqueId());
                say(s, "<green>Deleted <white>{n} <green>claims of {p}.", "n", String.valueOf(n), "p", a[1]);
            }
            case "claimtp" -> {
                if (!(s instanceof Player p) || a.length < 2 || number(a[1]) == null) {
                    say(s, "<red>/arka admin claimtp <id> (players only)");
                    return;
                }
                var c = plugin.claims().byId(number(a[1]).intValue());
                World w = c == null ? null : Bukkit.getWorld(c.world);
                if (w == null) {
                    say(s, "<red>Claim not found.");
                    return;
                }
                int x = (c.minX + c.maxX) / 2, z = (c.minZ + c.maxZ) / 2;
                p.teleportAsync(new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5));
            }
            case "homes" -> {
                if (a.length < 2) {
                    say(s, "<red>/arka admin homes <player>");
                    return;
                }
                OfflinePlayer t = target(s, a[1]);
                if (t == null) return;
                Set<String> names = plugin.homes().names(t.getUniqueId());
                say(s, "<gold>{p} <gray>homes (<white>{n}<gray>/<white>{limit}<gray>): <white>{list}", "p", a[1],
                        "n", String.valueOf(names.size()), "limit", String.valueOf(plugin.homes().limitFor(t.getUniqueId())),
                        "list", names.isEmpty() ? "-" : String.join(", ", names));
            }
            case "home" -> {
                if (!(s instanceof Player p) || a.length < 3) {
                    say(s, "<red>/arka admin home <player> <name> (players only)");
                    return;
                }
                OfflinePlayer t = target(s, a[1]);
                if (t == null) return;
                Location loc = plugin.homes().get(t.getUniqueId(), a[2].toLowerCase(Locale.ROOT));
                if (loc == null) say(s, "<red>No such home.");
                else p.teleportAsync(loc);
            }
            case "delhome" -> {
                if (a.length < 3) {
                    say(s, "<red>/arka admin delhome <player> <name>");
                    return;
                }
                OfflinePlayer t = target(s, a[1]);
                if (t == null) return;
                boolean ok = plugin.homes().delete(t.getUniqueId(), a[2].toLowerCase(Locale.ROOT));
                say(s, ok ? "<green>Home deleted." : "<red>No such home.");
            }
            case "resetcooldown" -> {
                if (a.length < 2) {
                    say(s, "<red>/arka admin resetcooldown <player>");
                    return;
                }
                OfflinePlayer t = target(s, a[1]);
                if (t == null) return;
                plugin.cooldowns().clear(t.getUniqueId());
                say(s, "<green>Cooldowns reset for {p}.", "p", a[1]);
            }
            case "get" -> {
                if (a.length < 2) {
                    say(s, "<red>/arka admin get <config.path>");
                    return;
                }
                Object v = plugin.getConfig().get(a[1]);
                say(s, "<gray>{path} = <white>{v}", "path", a[1], "v", String.valueOf(v));
            }
            case "errors" -> {
                var log = plugin.errors();
                if (a.length > 1 && a[1].equalsIgnoreCase("test")) {
                    String id = log.warn("errors.test", "Test entry written by /arka admin errors test", "by", s.getName());
                    say(s, "<green>Wrote test entry <white>{id}<green> to <white>logs/errors.log<green>.", "id", id);
                    return;
                }
                var list = log.recent(8);
                say(s, "<gold>Error log <gray>({n} recent) - file: <white>plugins/ArkaSMPCore/logs/errors.log", "n", String.valueOf(list.size()));
                if (list.isEmpty()) say(s, "<green>No problems recorded since the server started.");
                for (String line : list) say(s, "<gray>{l}", "l", line);
                say(s, "<gray>/arka admin errors test <dark_gray>writes a test entry to check the file works.");
            }
            case "kit" -> {
                if (a.length < 2) {
                    say(s, "<red>/arka admin kit <player> <gray>(gives the starter kit again)");
                    return;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) {
                    say(s, "<red>{p} must be online.", "p", a[1]);
                    return;
                }
                int n = plugin.starterKit().give(t);
                plugin.messages().send(t, "starter-kit-given");
                say(s, "<green>Gave the starter kit ({n} items) to {p}.", "n", String.valueOf(n), "p", t.getName());
            }
            case "afk" -> {
                if (a.length < 2) {
                    say(s, "<red>/arka admin afk <player>");
                    return;
                }
                OfflinePlayer t = target(s, a[1]);
                if (t == null) return;
                var afk = plugin.afk();
                if (afk.isAfk(t.getUniqueId())) {
                    say(s, "<gold>{p} <gray>is <red>AFK <gray>({why}) for <white>{sec}s", "p", a[1],
                            "why", String.valueOf(afk.reason(t.getUniqueId())), "sec", String.valueOf(afk.afkSeconds(t.getUniqueId())));
                } else {
                    say(s, "<gold>{p} <gray>is <green>active<gray>.", "p", a[1]);
                }
            }
            case "leaderboard" -> {
                if (a.length >= 2 && a[1].equalsIgnoreCase("reseed")) {
                    plugin.leaderboards().reseed();
                    say(s, "<green>Re-importing all known players into the leaderboards...");
                } else {
                    plugin.leaderboards().refresh();
                    say(s, "<green>Leaderboards refreshed. <gray>(/arka admin leaderboard reseed re-imports old players)");
                }
            }
            case "set" -> setConfig(s, a);
            default -> {
                say(s, "<gold>Admin commands <gray>(/arka admin ...)");
                say(s, "<white>claimblocks <get|add|set|take> <player> [amount]");
                say(s, "<white>homelimit <get|set|reset> <player> [amount]");
                say(s, "<white>claims <player> <gray>| <white>deleteclaim [id] <gray>| <white>deleteclaims <player> <gray>| <white>claimtp <id>");
                say(s, "<white>homes <player> <gray>| <white>home <player> <name> <gray>| <white>delhome <player> <name>");
                say(s, "<white>resetcooldown <player> <gray>| <white>leaderboard <refresh|reseed>");
                say(s, "<white>kit <player> <gray>(give the starter kit again)");
                say(s, "<white>errors [test] <gray>(recent problems from logs/errors.log)");
                say(s, "<white>get <path> <gray>| <white>set <path> <value> <gray>(edit config.yml, e.g. claims.blocks-per-hour 150)");
            }
        }
    }

    private void claimBlocks(CommandSender s, String[] a) {
        if (a.length < 3) {
            say(s, "<red>/arka admin claimblocks <get|add|set|take> <player> [amount]");
            return;
        }
        OfflinePlayer t = target(s, a[2]);
        if (t == null) return;
        UUID id = t.getUniqueId();
        var pl = plugin.players();
        String action = a[1].toLowerCase(Locale.ROOT);
        if (!action.equals("get")) {
            Long n = a.length > 3 ? number(a[3]) : null;
            if (n == null) {
                say(s, "<red>Give an amount, e.g. /arka admin claimblocks {a} {p} 500", "a", action, "p", a[2]);
                return;
            }
            if (Math.abs(n) > 1_000_000_000L) {
                say(s, "<red>Amount too large (max 1,000,000,000).");
                return;
            }
            switch (action) {
                case "add" -> pl.addBonus(id, n);
                case "take" -> pl.addBonus(id, -n);
                case "set" -> pl.setTotal(id, n);
                default -> {
                    say(s, "<red>Use get, add, set or take.");
                    return;
                }
            }
        }
        say(s, "<gold>{p}<gray>: <yellow>{rem} <gray>left of <yellow>{total} <gray>(used {used}, admin bonus {bonus})",
                "p", a[2], "rem", String.valueOf(pl.remaining(id)), "total", String.valueOf(pl.total(id)),
                "used", String.valueOf(pl.used(id)), "bonus", String.valueOf(pl.bonus(id)));
    }

    private void homeLimit(CommandSender s, String[] a) {
        if (a.length < 3) {
            say(s, "<red>/arka admin homelimit <get|set|reset> <player> [amount]");
            return;
        }
        OfflinePlayer t = target(s, a[2]);
        if (t == null) return;
        UUID id = t.getUniqueId();
        String action = a[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "set" -> {
                Long n = a.length > 3 ? number(a[3]) : null;
                if (n == null || n < 0 || n > 1000) {
                    say(s, "<red>Give a number, e.g. /arka admin homelimit set {p} 10", "p", a[2]);
                    return;
                }
                plugin.players().setHomeLimit(id, n.intValue());
            }
            case "reset" -> plugin.players().setHomeLimit(id, null);
            case "get" -> {
            }
            default -> {
                say(s, "<red>Use get, set or reset.");
                return;
            }
        }
        Integer o = plugin.players().homeLimitOverride(id);
        say(s, "<gold>{p}<gray> home limit: <yellow>{limit} <gray>({how})", "p", a[2],
                "limit", String.valueOf(plugin.homes().limitFor(id)), "how", o != null ? "admin override" : "default");
    }

    private void setConfig(CommandSender s, String[] a) {
        if (a.length < 3) {
            say(s, "<red>/arka admin set <config.path> <value>");
            return;
        }
        FileConfiguration c = plugin.getConfig();
        String path = a[1];
        Object cur = c.get(path);
        if (path.equals("config-version") || cur == null || c.isConfigurationSection(path) || cur instanceof List) {
            say(s, "<red>That setting cannot be changed here (must be an existing number, true/false or text).");
            return;
        }
        String raw = String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length));
        try {
            Object value;
            if (cur instanceof Boolean) value = Boolean.parseBoolean(raw);
            else if (cur instanceof Integer) value = Integer.parseInt(raw);
            else if (cur instanceof Long) value = Long.parseLong(raw);
            else if (cur instanceof Double) value = Double.parseDouble(raw);
            else value = raw;
            c.set(path, value);
            plugin.saveConfig();
            plugin.reload();
            say(s, "<green>{path} = <white>{v}", "path", path, "v", String.valueOf(value));
        } catch (NumberFormatException e) {
            say(s, "<red>'{v}' is not a valid number for {path}.", "v", raw, "path", path);
        }
    }
}
