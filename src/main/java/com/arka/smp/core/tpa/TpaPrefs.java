package com.arka.smp.core.tpa;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.DataFile;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Each player's TPA choices, saved in tpa-prefs.yml and kept for good: an "off" switch (nobody can send them a
 * request; off by default, so everybody can until they turn it on) and a list of players who may not send
 * them requests. Main thread only.
 */
public final class TpaPrefs {
    private final DataFile data;
    private final Set<UUID> off = new HashSet<>();
    private final Map<UUID, Set<UUID>> blocked = new HashMap<>();

    public TpaPrefs(ArkaSMPCore plugin) {
        this.data = new DataFile(plugin, "tpa-prefs.yml");
        for (String s : data.yaml().getStringList("off")) {
            UUID id = parse(s);
            if (id != null) off.add(id);
        }
        ConfigurationSection sec = data.yaml().getConfigurationSection("blocked");
        if (sec != null) {
            for (String owner : sec.getKeys(false)) {
                UUID o = parse(owner);
                if (o == null) continue;
                Set<UUID> set = new HashSet<>();
                for (String s : sec.getStringList(owner)) {
                    UUID id = parse(s);
                    if (id != null) set.add(id);
                }
                if (!set.isEmpty()) blocked.put(o, set);
            }
        }
    }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when this player has turned requests off. */
    public boolean isOff(UUID player) {
        return off.contains(player);
    }

    public void setOff(UUID player, boolean value) {
        if (value ? off.add(player) : off.remove(player)) write();
    }

    /** True when {@code owner} blocked {@code sender}. */
    public boolean isBlocked(UUID owner, UUID sender) {
        Set<UUID> set = blocked.get(owner);
        return set != null && set.contains(sender);
    }

    /** Returns false when they were already blocked. */
    public boolean block(UUID owner, UUID sender) {
        boolean added = blocked.computeIfAbsent(owner, k -> new HashSet<>()).add(sender);
        if (added) write();
        return added;
    }

    /** Returns false when they were not blocked. */
    public boolean unblock(UUID owner, UUID sender) {
        Set<UUID> set = blocked.get(owner);
        if (set == null || !set.remove(sender)) return false;
        if (set.isEmpty()) blocked.remove(owner);
        write();
        return true;
    }

    public List<UUID> blockedBy(UUID owner) {
        return new ArrayList<>(blocked.getOrDefault(owner, Set.of()));
    }

    private void write() {
        var y = data.yaml();
        y.set("off", off.stream().map(UUID::toString).toList());
        y.set("blocked", null);
        for (Map.Entry<UUID, Set<UUID>> e : blocked.entrySet()) {
            y.set("blocked." + e.getKey(), e.getValue().stream().map(UUID::toString).toList());
        }
        data.markDirty();
    }

    public void save() {
        data.saveNow();
    }
}
