package com.arka.smp.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** A YAML data file held in memory; saves are debounced and written off the main thread. */
public final class DataFile {
    private final ArkaSMPCore plugin;
    private final File file;
    private final YamlConfiguration yaml = new YamlConfiguration();
    private final Object lock = new Object();
    private BukkitTask pending;
    /** Every snapshot gets a number; an older snapshot can never overwrite a newer one. */
    private long sequence;
    private long written;

    public DataFile(ArkaSMPCore plugin, String name) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), name);
        if (file.exists()) {
            try {
                yaml.load(file);
            } catch (IOException | InvalidConfigurationException e) {
                plugin.getLogger().severe("Could not read " + name + ": " + e.getMessage());
                try {
                    Files.copy(file.toPath(), file.toPath().resolveSibling(name + ".broken"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public YamlConfiguration yaml() {
        return yaml;
    }

    /** Schedule a save in ~5 seconds; many changes within that window cost one write. */
    public void markDirty() {
        if (pending != null) return;
        if (!plugin.isEnabled()) return;   // shutting down: the caller's saveNow() writes everything
        pending = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending = null;
            final String data = yaml.saveToString();
            final long id = ++sequence;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data, id));
        }, 100L);
    }

    /** Synchronous save for shutdown. */
    public void saveNow() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        write(yaml.saveToString(), ++sequence);
    }

    private void write(String data, long id) {
        synchronized (lock) {
            if (id < written) return;   // a newer snapshot is already on disk
            written = id;
            try {
                Files.createDirectories(file.getParentFile().toPath());
                Path tmp = file.toPath().resolveSibling(file.getName() + ".tmp");
                Files.writeString(tmp, data, StandardCharsets.UTF_8);
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save " + file.getName() + ": " + e.getMessage());
            }
        }
    }
}
