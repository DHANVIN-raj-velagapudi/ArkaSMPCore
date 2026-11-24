package com.arka.smp.core.claims;

import com.arka.smp.core.ArkaSMPCore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Golden shovel: right-click one corner, then the opposite corner. */
public final class ClaimTool implements Listener {
    private final ArkaSMPCore plugin;
    private final Map<UUID, Location> firstCorner = new HashMap<>();
    private final Map<UUID, Long> lastInspect = new HashMap<>();

    public ClaimTool(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    public Material toolMaterial() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("claims.tool", "GOLDEN_SHOVEL"));
        return m != null && m.isItem() ? m : Material.GOLDEN_SHOVEL;
    }

    /** Stick (configurable): right-click a block to see who owns the land and how far the claim reaches. */
    public Material inspectMaterial() {
        Material m = Material.matchMaterial(plugin.getConfig().getString("claims.inspect-tool", "STICK"));
        return m != null && m.isItem() ? m : Material.STICK;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInspect(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        Block block = e.getClickedBlock();
        if (item == null || block == null || item.getType() != inspectMaterial()) return;
        if (item.getType() == toolMaterial()) return;
        Player p = e.getPlayer();
        var claims = plugin.claims();
        var existing = claims.claimAt(block);
        if (existing == null && !plugin.getConfig().getBoolean("claims.inspect-unclaimed-message", true)) return;
        e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);   // the stick only inspects, it never opens chests
        long now = System.currentTimeMillis();
        Long last = lastInspect.get(p.getUniqueId());
        if (last != null && now - last < 1000) return;                   // no packet spam
        lastInspect.put(p.getUniqueId(), now);
        if (existing == null) {
            plugin.messages().send(p, "claim-unclaimed");
            return;
        }
        plugin.messages().send(p, "claim-info", "owner", claims.ownerName(existing.owner),
                "w", String.valueOf(existing.width()), "l", String.valueOf(existing.length()),
                "area", String.valueOf(existing.area()));
        claims.show(p, existing);
    }

    public void give(Player p) {
        p.getInventory().addItem(new ItemStack(toolMaterial())).values()
                .forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        plugin.messages().send(p, "claim-tool-given");
    }

    // LOWEST so we run before the protection listener and fully handle the click.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = e.getItem();
        Block block = e.getClickedBlock();
        if (item == null || block == null || item.getType() != toolMaterial()) return;
        e.setCancelled(true);

        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        var msg = plugin.messages();
        var claims = plugin.claims();

        if (p.isSneaking()) {
            if (firstCorner.remove(id) != null) {
                claims.clearDisplay(id);
                msg.send(p, "claim-selection-cancelled");
            }
            return;
        }

        Location first = firstCorner.get(id);
        if (first == null || first.getWorld() != block.getWorld()) {
            var existing = claims.claimAt(block);
            if (existing != null) {
                msg.send(p, "claim-info", "owner", claims.ownerName(existing.owner),
                        "w", String.valueOf(existing.width()), "l", String.valueOf(existing.length()),
                        "area", String.valueOf(existing.area()));
                claims.show(p, existing);
                return;
            }
            firstCorner.put(id, block.getLocation());
            claims.showCorner(p, block.getLocation());
            msg.send(p, "claim-corner-1", "x", String.valueOf(block.getX()), "z", String.valueOf(block.getZ()));
        } else {
            firstCorner.remove(id);
            claims.createClaim(p, first, block.getLocation());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        firstCorner.remove(e.getPlayer().getUniqueId());
        lastInspect.remove(e.getPlayer().getUniqueId());
        plugin.claims().clearDisplay(e.getPlayer().getUniqueId());
    }
}
