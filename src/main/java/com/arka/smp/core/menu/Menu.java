package com.arka.smp.core.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest menu. Identified by its InventoryHolder (no title matching), built once on open,
 * never refreshed by a timer. Works for Bedrock players through Geyser.
 */
public class Menu implements InventoryHolder {
    private final Inventory inventory;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    public Menu(Component title, int rows) {
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    public Menu set(int slot, ItemStack item, Consumer<Player> action) {
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
        return this;
    }

    public Menu fill(ItemStack filler) {
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) inventory.setItem(i, filler);
        }
        return this;
    }

    public void open(Player p) {
        p.openInventory(inventory);
    }

    void click(Player p, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) action.accept(p);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
