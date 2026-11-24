package com.arka.smp.core.homes;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.DataFile;
import com.arka.smp.core.Locs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

public final class HomeManager {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,16}");
    private final ArkaSMPCore plugin;
    private final DataFile data;
    private final Map<UUID, Integer> limitCache = new ConcurrentHashMap<>();

    public HomeManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.data = new DataFile(plugin, "homes.yml");
    }

    public void save() {
        data.saveNow();
    }

    public Set<String> names(UUID id) {
        ConfigurationSection s = data.yaml().getConfigurationSection("homes." + id);
        return s == null ? Set.of() : new TreeSet<>(s.getKeys(false));
    }

    public Location get(UUID id, String name) {
        return Locs.parse(data.yaml().getString("homes." + id + "." + name));
    }

    /** Limit for an online player: admin override, else the highest of permission / rank / config default. */
    public int limit(Player p) {
        Integer override = plugin.players().homeLimitOverride(p.getUniqueId());
        int best;
        if (override != null) {
            best = override;
        } else {
            best = plugin.permissionLimit(p, "arka.homes.limit.", plugin.getConfig().getInt("homes.default-limit", 3));
            var ranks = plugin.getConfig().getConfigurationSection("homes.ranks");
            if (ranks != null) {
                for (String rank : ranks.getKeys(false)) {
                    // LuckPerms gives every member of a group the node "group.<name>"
                    if (p.hasPermission("group." + rank)) best = Math.max(best, ranks.getInt(rank));
                }
            }
        }
        limitCache.put(p.getUniqueId(), best);
        return best;
    }

    /** Safe to call from any thread (TAB reads placeholders asynchronously). */
    public int cachedLimit(UUID id) {
        Integer v = limitCache.get(id);
        return v != null ? v : limitFor(id);
    }

    /** Refresh cached limits every 10 seconds so placeholders never compute permissions themselves. */
    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            var online = new java.util.HashSet<UUID>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                online.add(p.getUniqueId());
                limit(p);
            }
            limitCache.keySet().retainAll(online);
        }, 20L, 200L);
    }

    /** Limit when the player is offline (permissions are not known). */
    public int limitFor(UUID id) {
        Integer override = plugin.players().homeLimitOverride(id);
        return override != null ? override : plugin.getConfig().getInt("homes.default-limit", 3);
    }

    /** Delete a home by UUID (admin use). */
    public boolean delete(UUID id, String name) {
        if (!names(id).contains(name)) return false;
        data.yaml().set("homes." + id + "." + name, null);
        data.markDirty();
        return true;
    }

    public static String normalize(String name) {
        return name == null ? null : name.toLowerCase(java.util.Locale.ROOT);
    }

    /** Create or overwrite a home. Sends feedback to the player. */
    public boolean setHome(Player p, String rawName) {
        String name = normalize(rawName);
        if (name == null || !VALID.matcher(name).matches()) {
            plugin.messages().send(p, "home-invalid-name");
            return false;
        }
        UUID id = p.getUniqueId();
        if (!names(id).contains(name) && names(id).size() >= limit(p)) {
            plugin.messages().send(p, "home-limit", "limit", String.valueOf(limit(p)));
            return false;
        }
        if (plugin.getConfig().getBoolean("homes.block-in-foreign-claims", true)
                && !plugin.claims().canBuild(p, p.getLocation())) {
            plugin.messages().send(p, "home-blocked-claim");
            return false;
        }
        data.yaml().set("homes." + id + "." + name, Locs.str(p.getLocation()));
        data.markDirty();
        plugin.messages().send(p, "home-set", "name", name);
        return true;
    }

    public boolean deleteHome(Player p, String rawName) {
        String name = normalize(rawName);
        UUID id = p.getUniqueId();
        if (name == null || !names(id).contains(name)) {
            plugin.messages().send(p, "home-missing", "name", String.valueOf(rawName));
            return false;
        }
        data.yaml().set("homes." + id + "." + name, null);
        data.markDirty();
        plugin.messages().send(p, "home-deleted", "name", name);
        return true;
    }

    public void teleportHome(Player p, String rawName) {
        String name = normalize(rawName);
        Location loc = name == null ? null : get(p.getUniqueId(), name);
        if (loc == null) {
            plugin.messages().send(p, "home-missing", "name", String.valueOf(rawName));
            return;
        }
        if (plugin.onCooldown(p, "home")) return;
        plugin.teleports().teleport(p, loc,
                () -> plugin.startCooldown(p, "home", plugin.getConfig().getInt("homes.cooldown", 5)));
    }
}
