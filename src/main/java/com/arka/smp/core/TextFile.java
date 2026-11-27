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
import java.util.function.Supplier;

/**
 * A data file whose content lives in the owner's own compact objects, not in a YamlConfiguration tree.
 *
 * A YamlConfiguration costs about 100 bytes of heap for every single value (16 KB per player for the three player
 * files), and the owner usually already holds the same data as objects, so the tree is a second, wasted copy. Here the
 * file is read once at start (the tree is dropped as soon as the owner has copied what it needs) and saved by writing
 * the YAML text straight from the objects. The text keeps the same keys as before, so older versions still read it.
 * Saves are debounced and the disk write happens off the main thread.
 */
public final class TextFile {
    private final ArkaSMPCore plugin;
    private final File file;
    private final Supplier<String> render;
    private final Object lock = new Object();
    private BukkitTask pending;
    /** Every snapshot gets a number; an older snapshot can never overwrite a newer one. */
    private long sequence;
    private long written;

    /** @param render builds the whole file text from the owner's objects; called on the main thread */
    public TextFile(ArkaSMPCore plugin, String name, Supplier<String> render) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), name);
        this.render = render;
    }

    /**
     * Reads the file into a temporary tree. The caller copies what it needs and then lets go of the tree. A file that
     * cannot be read is kept as {@code <name>.broken-<time>} so a later save never overwrites the only copy.
     */
    public YamlConfiguration read() {
        YamlConfiguration yaml = new YamlConfiguration();
        if (!file.exists()) return yaml;
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("Could not read " + file.getName() + ": " + e.getMessage());
            String keep = file.getName() + ".broken-" + System.currentTimeMillis();
            try {
                Files.copy(file.toPath(), file.toPath().resolveSibling(keep), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().severe("A copy of the unreadable file was kept as " + keep
                        + ". Starting with empty " + file.getName() + ".");
            } catch (IOException ignored) {
            }
            return new YamlConfiguration();
        }
        return yaml;
    }

    /** Schedule a save in ~5 seconds; many changes within that window cost one write. */
    public void markDirty() {
        if (pending != null) return;
        if (!plugin.isEnabled()) return;   // shutting down: the caller's saveNow() writes everything
        pending = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending = null;
            final String text = render.get();
            final long id = ++sequence;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(text, id));
        }, 100L);
    }

    /** Synchronous save for shutdown. */
    public void saveNow() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        write(render.get(), ++sequence);
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

    /** A YAML value for text: single quotes, so names like "true", "null" or "123" stay text. */
    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') sb.append("''");
            else if (c < 0x20 || c == 0x7f) sb.append('?');   // control characters cannot sit on one YAML line
            else sb.append(c);
        }
        return sb.append('\'').toString();
    }
}
