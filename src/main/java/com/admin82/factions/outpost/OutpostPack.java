package com.admin82.factions.outpost;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.storage.ItemVault;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Moving an outpost packs it into the Outpost item it hands back: its name, level and coins travel inside the item,
 * and placing that item sets the new outpost up the same way. The storage box has to be emptied of items before
 * moving (anything still in it, e.g. from an older version, travels along too).
 */
public final class OutpostPack {

    private static final String KEY = "packedOutpost";

    private OutpostPack() {}

    /**
     * An Outpost item carrying {@code entry}. Empties the entry's storage and coins into it, so dissolving the
     * outpost afterwards has nothing left to hand over.
     */
    public static ItemStack pack(OutpostEntry entry, HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        t.putString("name", entry.name);
        t.putInt("level", entry.level);
        t.put("storage", entry.storage.save(registries));
        entry.storage.takeAll();
        t.putLong("coins", entry.takeAllCoins());
        CompoundTag root = new CompoundTag();
        root.put(KEY, t);
        ItemStack stack = new ItemStack(ModItems.OUTPOST.get());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return stack;
    }

    /** What a packed Outpost item carries, or null for a new one. */
    @Nullable
    public static CompoundTag packed(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag root = data.copyTag();
        return root.contains(KEY, Tag.TAG_COMPOUND) ? root.getCompound(KEY) : null;
    }

    public static int level(CompoundTag packed) {
        return Math.max(1, Math.min(OutpostEntry.MAX_LEVEL, packed.getInt("level")));
    }

    /** Puts the packed storage and coins into a newly placed outpost (its level and name are set by the caller). */
    public static void unpack(CompoundTag packed, OutpostEntry entry, HolderLookup.Provider registries) {
        ItemVault items = ItemVault.load(packed.getCompound("storage"), registries);
        for (ItemStack stack : items.takeAll()) entry.storage.putBack(stack);
        entry.setCoinBalance(Math.max(0, packed.getLong("coins")));
    }

    /** Tooltip lines for a packed Outpost item. */
    public static void describe(CompoundTag packed, List<Component> tooltip) {
        int kinds = packed.getCompound("storage").getList("entries", Tag.TAG_COMPOUND).size();
        long coins = packed.getLong("coins");
        tooltip.add(Component.literal("§6Packed: §f" + packed.getString("name") + " §7(level " + level(packed) + ")"));
        if (coins > 0) tooltip.add(Component.literal("§7Coins: " + Currency.format(coins)));
        if (kinds > 0) tooltip.add(Component.literal("§7Storage: §f" + kinds + " kind" + (kinds == 1 ? "" : "s") + " of item"));
        tooltip.add(Component.literal("§8Place it to set the outpost up again with all of this."));
    }
}
