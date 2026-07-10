package com.admin82.factions.barracks;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * A named loadout stored in a faction Barracks.
 *
 * Slot layout (41 total):
 *   0–35  — inventory (left-to-right, top-to-bottom, matching player inv indices 9–44)
 *   36    — helmet
 *   37    — chestplate
 *   38    — leggings
 *   39    — boots
 *   40    — offhand
 */
public class KitData {

    public static final int SLOT_COUNT  = 41;
    public static final int INV_SLOTS   = 36;
    public static final int ARMOR_SLOTS = 4;
    public static final int OFFHAND_SLOT = 40;

    private String name;
    private final ItemStack[] slots = new ItemStack[SLOT_COUNT];

    public KitData(String name) {
        this.name = name;
        Arrays.fill(slots, ItemStack.EMPTY);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ItemStack getSlot(int index) {
        if (index < 0 || index >= SLOT_COUNT) return ItemStack.EMPTY;
        ItemStack s = slots[index];
        return s == null ? ItemStack.EMPTY : s;
    }

    public void setSlot(int index, ItemStack stack) {
        if (index < 0 || index >= SLOT_COUNT) return;
        slots[index] = (stack == null || stack.isEmpty()) ? ItemStack.EMPTY : stack.copy();
    }

    public ItemStack[] getSlotsCopy() {
        ItemStack[] copy = new ItemStack[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) copy[i] = getSlot(i).copy();
        return copy;
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        ListTag list = new ListTag();
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack s = slots[i];
            if (s != null && !s.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putByte("Slot", (byte) i);
                entry.put("Item", s.save(registries));
                list.add(entry);
            }
        }
        tag.put("Slots", list);
        return tag;
    }

    public static KitData load(CompoundTag tag, HolderLookup.Provider registries) {
        KitData kit = new KitData(tag.getString("name"));
        ListTag list = tag.getList("Slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int index = entry.getByte("Slot") & 0xFF;
            if (index < SLOT_COUNT) {
                kit.slots[index] = ItemStack.parseOptional(registries, entry.getCompound("Item"));
            }
        }
        return kit;
    }
}
