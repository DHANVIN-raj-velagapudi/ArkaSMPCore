package com.arka.smp.core.afk;

import com.arka.smp.core.ArkaSMPCore;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Strict AFK detection. Only real client input counts as activity, never position changes, so
 * pistons, water, bubble columns, minecarts and knockback cannot keep a player "active".
 *
 * Strong activity (wakes an AFK player): chat, commands, block break/place, clicks, attacks,
 * inventory clicks. Weak activity (only delays the idle timeout): key presses, hotbar scrolling,
 * dropping items. A macro that only taps keys in a small area is caught by the macro check.
 */
public final class AfkManager implements Listener {
    public enum Reason { TIMEOUT, MACRO, MANUAL }

    private static final int INPUT = 1, HOTBAR = 2, DROP = 4;                 // weak
    private static final int CHAT = 8, COMMAND = 16, BLOCK = 32, INTERACT = 64, ATTACK = 128, INVENTORY = 256; // strong
    private static final int STRONG = CHAT | COMMAND | BLOCK | INTERACT | ATTACK | INVENTORY;

    private static final class State {
        long lastActivity;
        boolean afk;
        Reason reason;
        long afkSince;
        Location afkLoc;
        boolean held;                // movement key currently held (from the last input event)
        Location lastSample;
        long windowStart;
        int windowMask;
        double minX, maxX, minZ, maxZ;
        long combatUntil;
        int prevSim = -1, prevView = -1;
    }

    private final ArkaSMPCore plugin;
    private final Map<UUID, State> states = new java.util.concurrent.ConcurrentHashMap<>();   // read by async placeholders
    private BukkitTask task;
    private int flushCounter;

    public AfkManager(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    // ---- lifecycle --------------------------------------------------------------------------

    public void start() {
        stop();
        for (Player p : Bukkit.getOnlinePlayers()) init(p);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void shutdown() {
        stop();
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s != null && s.afk) restore(p, s);
        }
        states.clear();
    }

    private State init(Player p) {
        State s = new State();
        long now = System.currentTimeMillis();
        Location l = p.getLocation();
        s.lastActivity = now;
        s.lastSample = l;
        resetWindow(s, l, now);
        states.put(p.getUniqueId(), s);
        return s;
    }

    private void resetWindow(State s, Location l, long now) {
        s.windowStart = now;
        s.windowMask = 0;
        s.minX = s.maxX = l.getX();
        s.minZ = s.maxZ = l.getZ();
    }

    // ---- public API -------------------------------------------------------------------------

    public boolean isAfk(UUID id) {
        State s = states.get(id);
        return s != null && s.afk;
    }

    public Reason reason(UUID id) {
        State s = states.get(id);
        return s != null && s.afk ? s.reason : null;
    }

    public long afkSeconds(UUID id) {
        State s = states.get(id);
        return s != null && s.afk ? (System.currentTimeMillis() - s.afkSince) / 1000 : 0;
    }

    /** Text for %arka_afk%: the configured tag while AFK, empty otherwise. */
    public String tag(UUID id) {
        return isAfk(id) ? plugin.getConfig().getString("afk.tag", "&7[AFK] ") : "";
    }

    /** /afk toggle. */
    public void toggle(Player p) {
        State s = states.get(p.getUniqueId());
        if (s == null) return;
        if (s.afk) {
            wake(p, s);
            return;
        }
        if (System.currentTimeMillis() < s.combatUntil) {
            plugin.messages().send(p, "afk-combat");
            return;
        }
        setAfk(p, s, Reason.MANUAL);
    }

    public boolean inCombat(UUID id) {
        State s = states.get(id);
        return s != null && System.currentTimeMillis() < s.combatUntil;
    }

    /** Floodgate (Bedrock) UUIDs have a zero most-significant half. */
    private static boolean isBedrock(UUID id) {
        return id.getMostSignificantBits() == 0L;
    }

    // ---- state changes ----------------------------------------------------------------------

    private void setAfk(Player p, State s, Reason reason) {
        if (s.afk) return;
        var cfg = plugin.getConfig();
        s.afk = true;
        s.reason = reason;
        s.afkSince = System.currentTimeMillis();
        s.afkLoc = p.getLocation();
        s.held = false;
        // Treated as "no player here": no mob spawning near them, small loaded/ticking area, no sleep vote.
        s.prevSim = p.getSimulationDistance();
        s.prevView = p.getViewDistance();
        p.setAffectsSpawning(false);
        p.setSleepingIgnored(true);
        p.setSimulationDistance(Math.max(2, cfg.getInt("afk.simulation-distance", 2)));
        p.setViewDistance(Math.max(2, cfg.getInt("afk.view-distance", 3)));
        plugin.messages().send(p, reason == Reason.MACRO ? "afk-on-macro" : "afk-on");
        if (cfg.getBoolean("afk.announce", false)) plugin.messages().broadcast("afk-announce-on", "player", p.getName());
    }

    private void wake(Player p, State s) {
        if (!s.afk) return;
        restore(p, s);
        s.afk = false;
        long now = System.currentTimeMillis();
        s.lastActivity = now;
        s.lastSample = p.getLocation();
        resetWindow(s, p.getLocation(), now);
        plugin.messages().send(p, "afk-off");
        if (plugin.getConfig().getBoolean("afk.announce", false)) {
            plugin.messages().broadcast("afk-announce-off", "player", p.getName());
        }
    }

    private void restore(Player p, State s) {
        p.setAffectsSpawning(true);
        p.setSleepingIgnored(false);
        if (s.prevSim >= 2) p.setSimulationDistance(s.prevSim);
        if (s.prevView >= 2) p.setViewDistance(s.prevView);
    }

    // ---- activity ---------------------------------------------------------------------------

    private void activity(Player p, int kind) {
        State s = states.get(p.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();
        boolean strong = (kind & STRONG) != 0;
        if (s.afk) {
            // Weak input never wakes an AFK player. The 1s grace stops the /afk command waking itself.
            if (strong && now - s.afkSince > 1000) wake(p, s);
            return;
        }
        s.lastActivity = now;
        s.windowMask |= kind;
    }

    // ---- once per second --------------------------------------------------------------------

    private void tick() {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("afk.enabled", true)) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                State st = states.get(p.getUniqueId());
                if (st != null && st.afk) wake(p, st);   // feature switched off: nobody stays AFK
                plugin.players().addActive(p.getUniqueId(), 20);
            }
            return;
        }
        long now = System.currentTimeMillis();
        long timeout = Math.max(10, cfg.getInt("afk.timeout-seconds", 120)) * 1000L;
        boolean macro = cfg.getBoolean("afk.macro.enabled", true);
        long window = Math.max(60, cfg.getInt("afk.macro.window-seconds", 300)) * 1000L;
        double radius = cfg.getDouble("afk.macro.radius", 6);
        double wakeDist = cfg.getDouble("afk.macro.wake-distance", 8);
        long kickAfter = cfg.getInt("afk.kick-after-minutes", 0) * 60_000L;
        String bypass = cfg.getString("afk.bypass-permission", "arka.afk.bypass");
        boolean bedrockFallback = cfg.getBoolean("afk.bedrock-movement-fallback", true);

        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s == null) s = init(p);
            Location loc = p.getLocation();
            // "Really moving": a movement key is held, or (Bedrock fallback) walking on land, not drifting in water.
            boolean moving = s.held || (bedrockFallback && isBedrock(p.getUniqueId())
                    && !p.isInWater() && !p.isInsideVehicle());

            if (p.hasPermission(bypass)) {
                if (s.afk) wake(p, s);
                plugin.players().addActive(p.getUniqueId(), 20);
                continue;
            }

            if (s.afk) {
                // An AFK player only comes back by strong activity, or by really walking away.
                if (moving && s.afkLoc != null && s.afkLoc.getWorld() == loc.getWorld()
                        && horizontal(s.afkLoc, loc) >= wakeDist) {
                    wake(p, s);
                } else if (kickAfter > 0 && now - s.afkSince >= kickAfter) {
                    p.kick(plugin.messages().plain("afk-kicked"));
                }
                continue;
            }

            // Held movement key + actual displacement = the player is really moving themselves.
            if (moving && s.lastSample != null && s.lastSample.getWorld() == loc.getWorld()
                    && horizontal(s.lastSample, loc) >= 0.8) {
                s.lastActivity = now;
                s.windowMask |= INPUT;
            }
            s.lastSample = loc;
            s.minX = Math.min(s.minX, loc.getX());
            s.maxX = Math.max(s.maxX, loc.getX());
            s.minZ = Math.min(s.minZ, loc.getZ());
            s.maxZ = Math.max(s.maxZ, loc.getZ());

            boolean inCombat = now < s.combatUntil;
            if (!inCombat && now - s.lastActivity >= timeout) {
                setAfk(p, s, Reason.TIMEOUT);
                continue;
            }
            if (macro && now - s.windowStart >= window) {
                double span = Math.max(s.maxX - s.minX, s.maxZ - s.minZ);
                boolean onlyWeak = s.windowMask != 0 && (s.windowMask & STRONG) == 0;
                if (!inCombat && onlyWeak && span < radius) {
                    setAfk(p, s, Reason.MACRO);
                    continue;
                }
                resetWindow(s, loc, now);
            }
            plugin.players().addActive(p.getUniqueId(), 20);   // counts as real play time
        }

        if (++flushCounter >= 300) {   // every 5 minutes: save active time so a crash loses little
            flushCounter = 0;
            plugin.players().flush();
            plugin.cooldowns().prune();
        }
    }

    private static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---- listeners: activity ----------------------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        init(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        states.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInput(PlayerInputEvent e) {
        State s = states.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        Input in = e.getInput();
        s.held = in.isForward() || in.isBackward() || in.isLeft() || in.isRight();
        if (s.held || in.isJump() || in.isSneak() || in.isSprint()) activity(e.getPlayer(), INPUT);
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> activity(p, CHAT));
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String m = e.getMessage().toLowerCase(Locale.ROOT);
        if (m.equals("/afk") || m.startsWith("/afk ")) return;   // /afk handles itself
        activity(e.getPlayer(), COMMAND);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        activity(e.getPlayer(), BLOCK);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        activity(e.getPlayer(), BLOCK);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.PHYSICAL) activity(e.getPlayer(), INTERACT);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        activity(e.getPlayer(), INTERACT);
    }

    @EventHandler
    public void onInventory(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) activity(p, INVENTORY);
    }

    @EventHandler
    public void onHotbar(PlayerItemHeldEvent e) {
        activity(e.getPlayer(), HOTBAR);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        activity(e.getPlayer(), DROP);
    }

    // ---- listeners: combat and PvP protection -----------------------------------------------

    private static Player attacker(EntityDamageByEntityEvent e) {
        Entity d = e.getDamager();
        if (d instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) d = shooter;
        return d instanceof Player p ? p : null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPvp(EntityDamageByEntityEvent e) {
        Player attacker = attacker(e);
        if (attacker != null) activity(attacker, ATTACK);
        if (!(e.getEntity() instanceof Player victim) || attacker == null || attacker.equals(victim)) return;
        State vs = states.get(victim.getUniqueId());
        if (vs != null && vs.afk) {
            e.setCancelled(true);
            attacker.sendActionBar(plugin.messages().plain("afk-victim"));
        }
    }

    /** Only real PvP that was not cancelled by anyone starts the combat lock. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvpTag(EntityDamageByEntityEvent e) {
        Player attacker = attacker(e);
        if (attacker == null || !(e.getEntity() instanceof Player victim) || attacker.equals(victim)) return;
        long until = System.currentTimeMillis() + plugin.getConfig().getInt("afk.combat-lock-seconds", 15) * 1000L;
        State vs = states.get(victim.getUniqueId());
        State as = states.get(attacker.getUniqueId());
        if (vs != null) vs.combatUntil = until;
        if (as != null) as.combatUntil = until;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAnyDamage(EntityDamageEvent e) {
        if (!plugin.getConfig().getBoolean("afk.invulnerable", false)) return;
        if (e.getEntity() instanceof Player p && isAfk(p.getUniqueId())
                && e.getCause() != EntityDamageEvent.DamageCause.VOID
                && e.getCause() != EntityDamageEvent.DamageCause.KILL) {
            e.setCancelled(true);
        }
    }
}
