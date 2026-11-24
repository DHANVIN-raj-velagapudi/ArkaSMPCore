package com.arka.smp.core.spawn;

import com.arka.smp.core.ArkaSMPCore;
import com.arka.smp.core.DataFile;
import com.arka.smp.core.Locs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class SpawnManager implements Listener {
    private final ArkaSMPCore plugin;
    private final DataFile data;
    private Location cached;

    public SpawnManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.data = new DataFile(plugin, "spawn.yml");
    }

    public void save() {
        data.saveNow();
    }

    /** Server spawn (falls back to the main world spawn if none was set). */
    public Location get() {
        if (cached == null || cached.getWorld() == null) cached = Locs.parse(data.yaml().getString("spawn"));
        return cached != null ? cached : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    public void set(Location l) {
        cached = l.clone();
        data.yaml().set("spawn", Locs.str(l));
        data.markDirty();
    }

    public void teleport(Player p) {
        plugin.teleports().teleport(p, get(), null);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!e.getPlayer().hasPlayedBefore() && plugin.getConfig().getBoolean("spawn.teleport-on-first-join", true)) {
            e.getPlayer().teleportAsync(get());
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (plugin.getConfig().getBoolean("spawn.teleport-on-respawn", true)
                && !e.isBedSpawn() && !e.isAnchorSpawn()) {
            e.setRespawnLocation(get());
        }
    }
}
