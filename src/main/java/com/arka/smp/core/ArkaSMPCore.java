package com.arka.smp.core;

import com.arka.smp.core.afk.AfkManager;
import com.arka.smp.core.claims.ClaimBlocks;
import com.arka.smp.core.claims.ClaimListener;
import com.arka.smp.core.claims.ClaimManager;
import com.arka.smp.core.claims.ClaimTool;
import com.arka.smp.core.claims.TrappedManager;
import com.arka.smp.core.cleaner.CleanerManager;
import com.arka.smp.core.homes.HomeManager;
import com.arka.smp.core.leaderboard.LeaderboardManager;
import com.arka.smp.core.menu.MenuListener;
import com.arka.smp.core.papi.ArkaExpansion;
import com.arka.smp.core.rtp.RtpManager;
import com.arka.smp.core.spawn.SpawnManager;
import com.arka.smp.core.stats.ServerStats;
import com.arka.smp.core.tpa.TpaManager;
import com.arka.smp.core.warps.WarpManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class ArkaSMPCore extends JavaPlugin {
    /** Raise this when a release changes how saved data is written in a way older jars cannot read. */
    private static final int DATA_FORMAT = 1;
    /** Sections whose entries belong to the admin: a rank they deleted is not added back by a config update. */
    private static final java.util.Set<String> ADMIN_OWNED = java.util.Set.of("homes.ranks", "pwarps.ranks");
    private static final String[] COMMANDS = {
            "sethome", "home", "delhome", "homes", "spawn", "setspawn", "tpa", "tpahere", "tpaccept",
            "tpdeny", "tpcancel", "rtp", "claim", "unclaim", "lag", "arka", "afk", "trapped",
            "warp", "setwarp", "delwarp", "pwarp"
    };

    private Messages messages;
    private Cooldowns cooldowns;
    private Players players;
    private TeleportService teleports;
    private HomeManager homes;
    private SpawnManager spawn;
    private TpaManager tpa;
    private RtpManager rtp;
    private TrappedManager trapped;
    private WarpManager warps;
    private ClaimManager claims;
    private ClaimBlocks claimBlocks;
    private ClaimTool claimTool;
    private ClaimListener claimListener;
    private CleanerManager cleaner;
    private LeaderboardManager leaderboards;
    private AfkManager afk;
    private ServerStats stats;

    @Override
    public void onEnable() {
        // Data safety first: snapshot the data on every version change, and refuse data from a newer format.
        if (!DataGuard.check(getDataFolder(), getPluginMeta().getVersion(), DATA_FORMAT, getLogger()).proceed()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        migrateLegacyConfig();
        ConfigUpdater.update(getDataFolder(), () -> getResource("config.yml"), getPluginMeta().getVersion(),
                getLogger(), ADMIN_OWNED);
        messages = new Messages(this);
        cooldowns = new Cooldowns();
        players = new Players(this);
        getServer().getServicesManager().register(com.arka.smp.core.api.PlayTimeProvider.class,
                id -> players.playTicks(id), this, org.bukkit.plugin.ServicePriority.Normal);
        getServer().getServicesManager().register(com.arka.smp.core.api.ClaimBlockBonus.class,
                (id, blocks) -> players.addBonus(id, blocks), this, org.bukkit.plugin.ServicePriority.Normal);
        stats = new ServerStats(this);
        teleports = new TeleportService(this);
        homes = new HomeManager(this);
        spawn = new SpawnManager(this);
        tpa = new TpaManager(this);
        rtp = new RtpManager(this);
        trapped = new TrappedManager(this);
        warps = new WarpManager(this);
        claims = new ClaimManager(this);
        claimBlocks = new ClaimBlocks(this);
        claimTool = new ClaimTool(this);
        cleaner = new CleanerManager(this);
        leaderboards = new LeaderboardManager(this);
        afk = new AfkManager(this);
        getServer().getServicesManager().register(com.arka.smp.core.api.CombatStatus.class,
                id -> afk.inCombat(id), this, org.bukkit.plugin.ServicePriority.Normal);

        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(players, this);
        pm.registerEvents(stats, this);
        pm.registerEvents(teleports, this);
        pm.registerEvents(spawn, this);
        pm.registerEvents(tpa, this);
        pm.registerEvents(claimTool, this);
        pm.registerEvents(leaderboards, this);
        pm.registerEvents(afk, this);
        claimListener = new ClaimListener(this);
        pm.registerEvents(claimListener, this);
        pm.registerEvents(new MenuListener(), this);

        CommandRouter router = new CommandRouter(this);
        for (String name : COMMANDS) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(router);
                cmd.setTabCompleter(router);
            }
        }
        cleaner.start();
        leaderboards.start();
        players.start();
        stats.start();
        homes.start();
        afk.start();

        if (pm.getPlugin("PlaceholderAPI") != null) {
            new ArkaExpansion(this).register();
            getLogger().info("PlaceholderAPI found: %arka_...% placeholders registered.");
        }
        getLogger().info("ArkaSMPCore enabled.");
    }

    /** A config from before versioning existed (no config-version, or 1) is kept as config-old.yml and replaced. */
    private void migrateLegacyConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) return;
        if (!YamlConfiguration.loadConfiguration(file).getString("config-version", "1").equals("1")) return;
        try {
            Files.move(file.toPath(), new File(getDataFolder(), "config-old.yml").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            getLogger().warning("Old config.yml saved as config-old.yml; a new config.yml was created.");
        } catch (IOException e) {
            getLogger().severe("Could not migrate config.yml: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (cleaner != null) cleaner.stop();
        if (afk != null) afk.shutdown();
        if (leaderboards != null) leaderboards.shutdown();
        if (teleports != null) teleports.shutdown();
        if (homes != null) homes.save();
        if (warps != null) warps.save();
        if (spawn != null) spawn.save();
        if (claims != null) claims.save();
        if (players != null) players.save();
        if (stats != null) stats.shutdown();
        HandlerList.unregisterAll(this);
    }

    public void reload() {
        reloadConfig();
        claimListener.refresh();
        cleaner.start();
        leaderboards.start();
    }

    /** Highest value among granted permissions "prefix<number>", or def. */
    public int permissionLimit(Player p, String prefix, int def) {
        int best = def;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission();
            if (perm.startsWith(prefix)) {
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return best;
    }

    /** True (and tells the player) if the cooldown is still running. */
    public boolean onCooldown(Player p, String type) {
        if (p.hasPermission("arka.bypass.cooldown")) return false;
        long left = cooldowns.remaining(type, p.getUniqueId());
        if (left > 0) {
            messages.send(p, "on-cooldown", "seconds", String.valueOf(left));
            return true;
        }
        return false;
    }

    public void startCooldown(Player p, String type, int seconds) {
        if (!p.hasPermission("arka.bypass.cooldown")) cooldowns.start(type, p.getUniqueId(), seconds);
    }

    public Messages messages() { return messages; }
    public Cooldowns cooldowns() { return cooldowns; }
    public Players players() { return players; }
    public TeleportService teleports() { return teleports; }
    public HomeManager homes() { return homes; }
    public SpawnManager spawn() { return spawn; }
    public TpaManager tpa() { return tpa; }
    public RtpManager rtp() { return rtp; }
    public TrappedManager trapped() { return trapped; }
    public WarpManager warps() { return warps; }
    public ClaimManager claims() { return claims; }
    public ClaimBlocks claimBlocks() { return claimBlocks; }
    public ClaimTool claimTool() { return claimTool; }
    public CleanerManager cleaner() { return cleaner; }
    public LeaderboardManager leaderboards() { return leaderboards; }
    public AfkManager afk() { return afk; }
    public ServerStats stats() { return stats; }
}
