package com.arka.smp.core.warps;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.DataFile;
import com.arka.smp.core.Locs;
import com.arka.smp.core.api.MoneyCharger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Server warps (/warp, made by admins) and player warps (/pwarp, made by players, limited per rank).
 * Every warp is checked with {@link WarpSafety} when it is made and again right before each teleport.
 */
public final class WarpManager {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,16}");
    private static final Set<String> RESERVED = Set.of("set", "delete", "del", "remove", "list", "info", "help",
            "menu", "create", "setwarp", "delwarp");

    public static final class Warp {
        public final String name;
        public final UUID owner;           // null = server warp
        public String ownerName;
        public String loc;
        public int visits;
        /** Admin warp made with "force": the safety check is skipped (the admin takes responsibility). */
        public boolean forced;
        public final long created;
        /** Last safety result seen at teleport time (memory only, shown in menus). */
        public volatile String unsafeReason;

        Warp(String name, UUID owner, String ownerName, String loc, int visits, long created) {
            this.name = name;
            this.owner = owner;
            this.ownerName = ownerName;
            this.loc = loc;
            this.visits = visits;
            this.created = created;
        }

        public boolean server() {
            return owner == null;
        }
    }

    private final ArkaSMPCore plugin;
    private final DataFile data;
    private final Map<String, Warp> server = new TreeMap<>();
    private final Map<String, Warp> players = new TreeMap<>();
    private final Set<UUID> busy = new HashSet<>();

    public WarpManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.data = new DataFile(plugin, "warps.yml");
        load();
    }

    private void load() {
        read("server", server, false);
        read("player", players, true);
    }

    private void read(String root, Map<String, Warp> into, boolean player) {
        ConfigurationSection s = data.yaml().getConfigurationSection(root);
        if (s == null) return;
        for (String name : s.getKeys(false)) {
            ConfigurationSection w = s.getConfigurationSection(name);
            if (w == null || w.getString("loc") == null) continue;
            UUID owner = null;
            if (player) {
                try {
                    owner = UUID.fromString(w.getString("owner", ""));
                } catch (IllegalArgumentException e) {
                    continue;
                }
            }
            Warp loaded = new Warp(name, owner, w.getString("owner-name", "?"), w.getString("loc"),
                    w.getInt("visits", 0), w.getLong("created", 0));
            loaded.forced = w.getBoolean("forced", false);
            into.put(name, loaded);
        }
    }

    private void write(Warp w) {
        String path = (w.server() ? "server." : "player.") + w.name;
        data.yaml().set(path + ".loc", w.loc);
        data.yaml().set(path + ".visits", w.visits);
        data.yaml().set(path + ".created", w.created);
        data.yaml().set(path + ".forced", w.forced ? true : null);
        if (!w.server()) {
            data.yaml().set(path + ".owner", w.owner.toString());
            data.yaml().set(path + ".owner-name", w.ownerName);
        }
        data.markDirty();
    }

    private void erase(Warp w) {
        data.yaml().set((w.server() ? "server." : "player.") + w.name, null);
        data.markDirty();
    }

    public void save() {
        data.saveNow();
    }

    public List<Warp> serverWarps() {
        return new ArrayList<>(server.values());
    }

    /** Most visited first, then by name. */
    public List<Warp> playerWarps() {
        List<Warp> l = new ArrayList<>(players.values());
        l.sort(Comparator.comparingInt((Warp w) -> -w.visits).thenComparing(w -> w.name));
        return l;
    }

    public List<Warp> warpsOf(UUID owner) {
        List<Warp> l = new ArrayList<>();
        for (Warp w : players.values()) if (w.owner.equals(owner)) l.add(w);
        return l;
    }

    public Warp serverWarp(String name) {
        return name == null ? null : server.get(name.toLowerCase(Locale.ROOT));
    }

    public Warp playerWarp(String name) {
        return name == null ? null : players.get(name.toLowerCase(Locale.ROOT));
    }

    public Set<String> serverNames() {
        return server.keySet();
    }

    public Set<String> playerNames() {
        return players.keySet();
    }

    public WarpSafety.Settings settings() {
        var c = plugin.getConfig();
        return new WarpSafety.Settings(
                Math.max(3, Math.min(64, c.getInt("warps.safety.max-height-above-ground", 12))),
                Math.max(1, Math.min(9, c.getInt("warps.safety.min-platform-blocks", 4))),
                Math.max(1, Math.min(32, c.getInt("warps.safety.platform-drop", 4))),
                Math.max(8, Math.min(500, c.getInt("warps.safety.room-cells", 120))));
    }

    // ---- limits ---------------------------------------------------------------------------------

    public int limit(Player p) {
        var c = plugin.getConfig();
        int best = plugin.permissionLimit(p, "arka.pwarps.limit.", c.getInt("pwarps.default-limit", 1));
        var ranks = c.getConfigurationSection("pwarps.ranks");
        if (ranks != null) {
            for (String rank : ranks.getKeys(false)) {
                if (p.hasPermission("group." + rank)) best = Math.max(best, ranks.getInt(rank));
            }
        }
        return best;
    }

    public double createCost() {
        return Math.max(0, plugin.getConfig().getDouble("pwarps.create-cost", 500));
    }

    // ---- creating / deleting --------------------------------------------------------------------

    private static String clean(String raw) {
        return raw == null ? null : raw.toLowerCase(Locale.ROOT);
    }

    private boolean validName(Player p, String name) {
        if (name == null || !VALID.matcher(name).matches() || RESERVED.contains(name)) {
            plugin.messages().send(p, "warp-invalid-name");
            return false;
        }
        return true;
    }

    private boolean worldAllowed(Player p) {
        if (plugin.getConfig().getStringList("warps.disabled-worlds").contains(p.getWorld().getName())) {
            plugin.messages().send(p, "warp-world-disabled");
            return false;
        }
        return true;
    }

    /** Safety message key for a problem. */
    static String key(WarpSafety.Problem p) {
        return "warp-unsafe-" + p.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public void createServerWarp(Player p, String rawName, boolean force) {
        var msg = plugin.messages();
        String name = clean(rawName);
        if (!validName(p, name)) return;
        Location at = WarpSafety.normalize(p.getLocation());
        if (!force) {
            var r = WarpSafety.check(at, settings());
            if (!r.safe()) {
                msg.send(p, key(r.problem()), "detail", r.detail());
                msg.send(p, "warp-force-hint");
                return;
            }
        }
        Warp old = server.get(name);
        Warp w = new Warp(name, null, "", Locs.str(at), old == null ? 0 : old.visits,
                old == null ? System.currentTimeMillis() : old.created);
        w.forced = force;
        server.put(name, w);
        write(w);
        msg.send(p, old == null ? "warp-created" : "warp-moved", "name", name);
    }

    public void deleteServerWarp(Player p, String rawName) {
        Warp w = serverWarp(rawName);
        if (w == null) {
            plugin.messages().send(p, "warp-missing", "name", String.valueOf(rawName));
            return;
        }
        server.remove(w.name);
        erase(w);
        plugin.messages().send(p, "warp-deleted", "name", w.name);
    }

    public void createPlayerWarp(Player p, String rawName) {
        var msg = plugin.messages();
        if (!plugin.getConfig().getBoolean("pwarps.enabled", true)) {
            msg.send(p, "warp-disabled");
            return;
        }
        String name = clean(rawName);
        if (!validName(p, name) || !worldAllowed(p)) return;
        Warp existing = players.get(name);
        if (existing != null && !existing.owner.equals(p.getUniqueId())) {
            msg.send(p, "pwarp-name-taken", "name", name);
            return;
        }
        boolean isNew = existing == null;
        if (isNew && warpsOf(p.getUniqueId()).size() >= limit(p)) {
            msg.send(p, "pwarp-limit", "limit", String.valueOf(limit(p)));
            return;
        }
        if (!p.hasPermission("arka.warp.admin") && !plugin.claims().canBuild(p, p.getLocation())) {
            msg.send(p, "pwarp-blocked-claim");
            return;
        }
        Location at = WarpSafety.normalize(p.getLocation());
        var r = WarpSafety.check(at, settings());
        if (!r.safe()) {
            msg.send(p, key(r.problem()), "detail", r.detail());
            return;
        }
        double cost = isNew ? createCost() : 0;
        if (cost > 0 && !p.hasPermission("arka.warp.free")) {
            var charger = Bukkit.getServicesManager().load(MoneyCharger.class);
            if (charger == null) {
                msg.send(p, "pwarp-no-economy");
                return;
            }
            var res = charger.charge(p.getUniqueId(), cost, "player warp " + name);
            if (res != MoneyCharger.Result.OK) {
                msg.send(p, res == MoneyCharger.Result.INSUFFICIENT ? "pwarp-cost-insufficient" : "pwarp-cost-failed",
                        "cost", plain(cost));
                return;
            }
            msg.send(p, "pwarp-charged", "cost", plain(cost));
        }
        Warp w = new Warp(name, p.getUniqueId(), p.getName(), Locs.str(at), isNew ? 0 : existing.visits,
                isNew ? System.currentTimeMillis() : existing.created);
        players.put(name, w);
        write(w);
        msg.send(p, isNew ? "pwarp-created" : "pwarp-moved", "name", name);
    }

    public void deletePlayerWarp(Player p, String rawName) {
        Warp w = playerWarp(rawName);
        if (w == null) {
            plugin.messages().send(p, "warp-missing", "name", String.valueOf(rawName));
            return;
        }
        if (!w.owner.equals(p.getUniqueId()) && !p.hasPermission("arka.warp.admin")) {
            plugin.messages().send(p, "pwarp-not-yours");
            return;
        }
        players.remove(w.name);
        erase(w);
        plugin.messages().send(p, "warp-deleted", "name", w.name);
    }

    private static String plain(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    // ---- teleporting ----------------------------------------------------------------------------

    public void teleport(Player p, Warp w) {
        var msg = plugin.messages();
        var c = plugin.getConfig();
        if (!c.getBoolean(w.server() ? "warps.enabled" : "pwarps.enabled", true)) {
            msg.send(p, "warp-disabled");
            return;
        }
        Location dest = Locs.parse(w.loc);
        if (dest == null) {
            msg.send(p, "warp-world-missing", "name", w.name);
            return;
        }
        if (plugin.onCooldown(p, "warp")) return;
        if (!busy.add(p.getUniqueId())) return;
        World world = dest.getWorld();
        int cx = dest.getBlockX() >> 4, cz = dest.getBlockZ() >> 4;
        List<CompletableFuture<?>> loads = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) loads.add(world.getChunkAtAsync(cx + dx, cz + dz));
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((v, ex) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    busy.remove(p.getUniqueId());
                    if (!p.isOnline()) return;
                    if (ex != null) {
                        msg.send(p, "warp-load-failed");
                        return;
                    }
                    afterLoad(p, w, dest);
                }));
    }

    private void afterLoad(Player p, Warp w, Location dest) {
        var msg = plugin.messages();
        var r = w.forced ? WarpSafety.Result.OK : WarpSafety.check(dest, settings());
        if (!r.safe()) {
            w.unsafeReason = r.problem().name();
            msg.send(p, "warp-refused", "name", w.name);
            msg.send(p, key(r.problem()), "detail", r.detail());
            if (!w.server()) {
                Player owner = Bukkit.getPlayer(w.owner);
                if (owner != null && owner != p) msg.send(owner, "pwarp-owner-unsafe", "name", w.name);
            }
            plugin.getLogger().info("Refused warp " + w.name + " for " + p.getName() + ": " + r.problem());
            return;
        }
        w.unsafeReason = null;
        if (!dest.getWorld().equals(p.getWorld()) && plugin.getConfig().getBoolean("warps.warn-other-world", true)) {
            msg.send(p, "warp-other-world", "world", dest.getWorld().getName(), "kind", worldKind(dest.getWorld()));
        }
        plugin.teleports().teleport(p, dest, () -> {
            plugin.startCooldown(p, "warp", plugin.getConfig().getInt("warps.cooldown", 5));
            if (w.owner == null || !w.owner.equals(p.getUniqueId())) {   // visiting your own warp is not a visit
                w.visits++;
                write(w);
            }
        });
    }

    public static String worldKind(World w) {
        return switch (w.getEnvironment()) {
            case NETHER -> "the Nether";
            case THE_END -> "the End";
            default -> "another world";
        };
    }

    public void forget(UUID id) {
        busy.remove(id);
    }
}
