package com.arka.smp.core.kit;

import com.arka.smp.core.ArkaSMPCore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Gives a new player (first join ever) a starter kit: items listed in config.yml under starter-kit.items, written as
 * MATERIAL or MATERIAL:amount. Armour is worn when the slot is free. Whatever does not fit is dropped at their feet.
 */
public final class StarterKit implements Listener {
    private final ArkaSMPCore plugin;

    public StarterKit(ArkaSMPCore plugin) {
        this.plugin = plugin;
    }

    /** One "MATERIAL" or "MATERIAL:amount" entry, or null when it is not a real item. */
    public static ItemStack parse(String entry) {
        if (entry == null) return null;
        String s = entry.trim();
        int amount = 1;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            try {
                amount = Integer.parseInt(s.substring(colon + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            s = s.substring(0, colon).trim();
        }
        Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
        if (m == null || !m.isItem() || m.isAir() || amount < 1) return null;
        return new ItemStack(m, Math.min(amount, m.getMaxStackSize()));
    }

    /** The configured items; entries that are not valid are skipped (and named in the console). */
    public List<ItemStack> items() {
        List<ItemStack> out = new ArrayList<>();
        for (String entry : plugin.getConfig().getStringList("starter-kit.items")) {
            ItemStack it = parse(entry);
            if (it == null) plugin.getLogger().warning("starter-kit.items: '" + entry + "' is not a valid item, skipped.");
            else out.add(it);
        }
        return out;
    }

    private static EquipmentSlot armourSlot(Material m) {
        String n = m.name();
        if (n.endsWith("_HELMET")) return EquipmentSlot.HEAD;
        if (n.endsWith("_CHESTPLATE")) return EquipmentSlot.CHEST;
        if (n.endsWith("_LEGGINGS")) return EquipmentSlot.LEGS;
        if (n.endsWith("_BOOTS")) return EquipmentSlot.FEET;
        return null;
    }

    /** Gives the kit now. Returns how many stacks were handed over. */
    public int give(Player p) {
        PlayerInventory inv = p.getInventory();
        boolean wear = plugin.getConfig().getBoolean("starter-kit.wear-armor", true);
        int n = 0;
        for (ItemStack it : items()) {
            EquipmentSlot slot = wear ? armourSlot(it.getType()) : null;
            if (slot != null) {
                ItemStack current = inv.getItem(slot);
                if (current == null || current.getType().isAir()) {
                    inv.setItem(slot, it);
                    n++;
                    continue;
                }
            }
            inv.addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            n++;
        }
        return n;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.hasPlayedBefore() || !plugin.getConfig().getBoolean("starter-kit.enabled", true)) return;
        // One tick later, so other plugins that touch a new player's inventory on join are done first.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            give(p);
            plugin.messages().send(p, "starter-kit-given");
        }, 20L);
    }
}
