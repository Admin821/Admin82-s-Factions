package com.admin82.factions.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class TemporaryMoveItem {
    private static final String PREFIX = "Temporary Move: ";

    private TemporaryMoveItem() {}

    public static ItemStack create(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(PREFIX + name));
        return stack;
    }

    public static boolean isTemporary(ItemStack stack, Item item) {
        if (stack.isEmpty() || stack.getItem() != item) return false;
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        return name != null && name.getString().startsWith(PREFIX);
    }

    public static void removeAll(Player player, Item item) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isTemporary(stack, item)) {
                inventory.setItem(slot, ItemStack.EMPTY);
            }
        }
    }
}
