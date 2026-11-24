package com.arka.smp.core.claims;

import com.arka.smp.core.ArkaSMPCore;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClaimListener implements Listener {
    private final ArkaSMPCore plugin;
    private final ClaimManager claims;
    private final Map<UUID, Long> lastDeny = new HashMap<>();
    private final Map<UUID, Long> lastChatWarn = new HashMap<>();

    // Config switches cached as fields: hot events must not parse config paths.
    private boolean liquids, explosions, fire, pistons, animals, hoppers, dispensers, mobGriefing, enterMessage, defendSelf;
    private String mobMode = "all";   // all | non-hostile | off

    public ClaimListener(ArkaSMPCore plugin) {
        this.plugin = plugin;
        this.claims = plugin.claims();
        refresh();
    }

    /** Re-read config switches (called on /arka reload). */
    public void refresh() {
        var c = plugin.getConfig();
        liquids = c.getBoolean("claims.protect.liquids", true);
        explosions = c.getBoolean("claims.protect.explosions", true);
        fire = c.getBoolean("claims.protect.fire", true);
        pistons = c.getBoolean("claims.protect.pistons", true);
        animals = c.getBoolean("claims.protect.animals", true);
        hoppers = c.getBoolean("claims.protect.hoppers", true);
        dispensers = c.getBoolean("claims.protect.dispensers", true);
        mobGriefing = c.getBoolean("claims.protect.mob-griefing", true);
        enterMessage = c.getBoolean("claims.show-enter-message", true);
        mobMode = c.getString("claims.protect.mobs", "all").toLowerCase(java.util.Locale.ROOT);
        defendSelf = c.getBoolean("claims.protect.defend-self", true);
    }

    private void deny(Player p, UUID owner) {
        long now = System.currentTimeMillis();
        Long last = lastDeny.get(p.getUniqueId());
        if (last != null && now - last < 1000) return;
        lastDeny.put(p.getUniqueId(), now);
        String name = claims.ownerName(owner);
        p.sendActionBar(plugin.messages().plain("claim-denied", "owner", name));
        // a chat line too (rate limited) so players on phones, where the action bar is easy to miss, see it
        var cfg = plugin.getConfig();
        if (cfg.getBoolean("claims.warn.chat", true)) {
            long every = Math.max(1, cfg.getInt("claims.warn.chat-interval-seconds", 5)) * 1000L;
            Long lc = lastChatWarn.get(p.getUniqueId());
            if (lc == null || now - lc >= every) {
                lastChatWarn.put(p.getUniqueId(), now);
                plugin.messages().send(p, "claim-denied-chat", "owner", name);
                if (cfg.getBoolean("claims.warn.sound", true)) {
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
                }
            }
        }
    }

    /** Returns true if the action must be blocked (and tells the player). */
    private boolean blocked(Player p, Block b) {
        ClaimManager.Claim c = claims.claimAt(b);
        if (c == null || claims.canBuild(p, c)) return false;
        deny(p, c.owner);
        return true;
    }

    private boolean blocked(Player p, Location l) {
        ClaimManager.Claim c = claims.claimAt(l);
        if (c == null || claims.canBuild(p, c)) return false;
        deny(p, c.owner);
        return true;
    }

    // ---- players ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (blocked(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (blocked(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    /** Beds are two blocks: the second half must not land inside someone else's claim. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent e) {
        for (org.bukkit.block.BlockState st : e.getReplacedBlockStates()) {
            if (blocked(e.getPlayer(), st.getBlock())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Item frames and paintings hang in the block next to the one you click: check that block too. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        Player p = e.getPlayer();
        if (p != null && blocked(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.PHYSICAL) return;
        if (blocked(e.getPlayer(), b)) {
            e.setUseInteractedBlock(Event.Result.DENY);
            e.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (blocked(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (blocked(e.getPlayer(), e.getBlock())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (!animals) return;
        Entity en = e.getRightClicked();
        if (en instanceof Hanging || en instanceof ArmorStand || en instanceof Animals || en instanceof Vehicle) {
            if (blocked(e.getPlayer(), en.getLocation())) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (animals && blocked(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true);
    }

    /** Only the owner (and trusted players) may hurt or kill mobs, animals, pets, villagers and vehicles in a claim. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (mobMode.equals("off") || claims.isEmpty()) return;
        Entity victim = e.getEntity();
        if (victim instanceof Player) return;
        Entity damager = e.getDamager();
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) damager = shooter;
        if (!(damager instanceof Player p)) return;
        if (victim instanceof Enemy && mobMode.equals("non-hostile")) return;
        // fighting back is always allowed: a monster that is attacking you can be killed anywhere
        if (defendSelf && victim instanceof Mob m && p.equals(m.getTarget())) return;
        if (blocked(p, victim.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(org.bukkit.event.vehicle.VehicleDamageEvent e) {
        if (animals && e.getAttacker() instanceof Player p && blocked(p, e.getVehicle().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShear(org.bukkit.event.player.PlayerShearEntityEvent e) {
        if (animals && blocked(e.getPlayer(), e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(org.bukkit.event.entity.PlayerLeashEntityEvent e) {
        if (animals && blocked(e.getPlayer(), e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEntity(org.bukkit.event.player.PlayerBucketEntityEvent e) {
        if (animals && blocked(e.getPlayer(), e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreakByEntity(HangingBreakByEntityEvent e) {
        Entity remover = e.getRemover();
        if (remover instanceof Projectile proj && proj.getShooter() instanceof Entity shooter) remover = shooter;
        if (remover instanceof Player p && blocked(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    // ---- explosions, fire, mobs -------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (explosions) e.blockList().removeIf(b -> claims.ownerOf(b) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (explosions) e.blockList().removeIf(b -> claims.ownerOf(b) != null);
    }

    /** Item frames, paintings and armor stands must survive explosions inside claims. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if (explosions && e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION
                && claims.ownerOf(e.getEntity().getLocation()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent e) {
        if (!explosions) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        Entity en = e.getEntity();
        if ((en instanceof ArmorStand || en instanceof Hanging) && claims.ownerOf(en.getLocation()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (fire && claims.ownerOf(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!fire) return;
        ClaimManager.Claim c = claims.claimAt(e.getBlock());
        if (c == null) return;
        Player p = e.getPlayer();
        if (p == null || !claims.canBuild(p, c)) {
            if (p != null) deny(p, c.owner);
            e.setCancelled(true);
        }
    }

    /** Endermen, withers, ravagers, zombies breaking doors... cannot change blocks in claims. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMobGrief(EntityChangeBlockEvent e) {
        if (!mobGriefing) return;
        Entity en = e.getEntity();
        if (en instanceof Player || en instanceof FallingBlock || en instanceof Villager) return;
        if (claims.ownerOf(e.getBlock()) != null) e.setCancelled(true);
    }

    // ---- automation: pistons, hoppers, dispensers -------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (pistons && crossesBorder(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (pistons && crossesBorder(e.getBlock(), e.getBlocks(), e.getDirection().getOppositeFace())) {
            e.setCancelled(true);
        }
    }

    private boolean crossesBorder(Block piston, List<Block> moved, BlockFace dir) {
        UUID pistonOwner = claims.ownerOf(piston);
        UUID head = claims.ownerOf(piston.getRelative(dir));
        if (head != null && !head.equals(pistonOwner)) return true;
        for (Block b : moved) {
            UUID src = claims.ownerOf(b);          // a sticky piston outside must not pull blocks OUT of a claim
            if (src != null && !src.equals(pistonOwner)) return true;
            UUID dest = claims.ownerOf(b.getRelative(dir));
            if (dest != null && !dest.equals(pistonOwner)) return true;
        }
        return false;
    }

    /** Water and lava placed outside must not flow into someone else's claim (sideways; falling stays in the column). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!liquids || claims.isEmpty()) return;
        Block from = e.getBlock(), to = e.getToBlock();
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return;
        ClaimManager.Claim target = claims.claimAt(to);
        if (target == null) return;
        ClaimManager.Claim source = claims.claimAt(from);
        if (source == null || !source.owner.equals(target.owner)) e.setCancelled(true);
    }

    /** A hopper outside a claim must not be able to pull items out of (or push into) a claimed chest. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (!hoppers || claims.isEmpty()) return;
        Location from = e.getSource().getLocation();
        Location to = e.getDestination().getLocation();
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null) return;
        UUID fromOwner = claims.ownerOf(from);
        UUID toOwner = claims.ownerOf(to);
        if (fromOwner == null && toOwner == null) return;
        if (fromOwner == null || !fromOwner.equals(toOwner)) e.setCancelled(true);
    }

    /** A dispenser outside a claim must not pour lava/water or place things inside a claim. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (!dispensers || claims.isEmpty()) return;
        if (!(e.getBlock().getBlockData() instanceof Directional d)) return;
        UUID target = claims.ownerOf(e.getBlock().getRelative(d.getFacing()));
        if (target != null && !target.equals(claims.ownerOf(e.getBlock()))) e.setCancelled(true);
    }

    // ---- enter / leave message --------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!enterMessage || !e.hasChangedBlock()) return;
        UUID a = claims.ownerOf(e.getFrom());
        UUID b = claims.ownerOf(e.getTo());
        if ((a == null && b == null) || (a != null && a.equals(b))) return;
        Player p = e.getPlayer();
        if (b != null) p.sendActionBar(plugin.messages().plain("claim-enter", "owner", claims.ownerName(b)));
        else p.sendActionBar(plugin.messages().plain("claim-leave"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        claims.forgetBypass(e.getPlayer().getUniqueId());
        lastDeny.remove(e.getPlayer().getUniqueId());
        lastChatWarn.remove(e.getPlayer().getUniqueId());
    }
}
