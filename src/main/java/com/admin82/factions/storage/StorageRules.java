package com.admin82.factions.storage;

import com.admin82.factions.AdminsFactions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.neoforged.neoforge.capabilities.Capabilities;

/**
 * What may go into faction item vaults and outpost storage boxes: nothing that holds items itself (shulker boxes,
 * bundles, backpacks, chests carrying their contents...), so storage can't be stacked inside storage.
 */
public final class StorageRules {

    /** Extra items to keep out, for modded storage the checks below miss. */
    public static final TagKey<Item> NOT_ALLOWED = ItemTags.create(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "not_allowed_in_storage"));

    public static final String MESSAGE = "Items that hold other items (shulker boxes, backpacks, bundles...) can't go in here.";

    private StorageRules() {}

    public static boolean allowed(ItemStack stack) {
        if (stack.isEmpty()) return true;
        return !holdsItems(stack);
    }

    /** Whether the item carries other items inside it, or is a kind of portable storage. */
    public static boolean holdsItems(ItemStack stack) {
        if (stack.is(NOT_ALLOWED)) return true;
        Item item = stack.getItem();
        if (item instanceof BundleItem) return true;
        if (item instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock) return true;
        // Vanilla containers keep their contents in these components (shulker boxes, bundles, picked-up chests)
        if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS)) return true;
        CustomData blockData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (blockData != null && blockData.contains("Items")) return true;
        // Modded backpacks and the like expose an item inventory on the stack
        return stack.getCapability(Capabilities.ItemHandler.ITEM) != null;
    }
}
