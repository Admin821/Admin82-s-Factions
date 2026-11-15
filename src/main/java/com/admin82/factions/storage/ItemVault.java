package com.admin82.factions.storage;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Item storage that holds a limited number of different items, each up to a limited count, like a
 * drawer rather than a chest. Used by the faction item vault and outpost storage boxes.
 * Items count as the same kind only when everything about them matches (enchantments, names, ...).
 */
public class ItemVault {

    /** One kind of item: a single-item template and how many are stored. */
    public static final class Entry {
        public final ItemStack item;
        public long count;

        Entry(ItemStack item, long count) {
            this.item = item.copyWithCount(1);
            this.count = count;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int level = 1;

    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public int level() { return level; }
    public void setLevel(int level) { this.level = Math.max(1, level); }
    public boolean isEmpty() { return entries.isEmpty(); }

    /**
     * Stores as much of {@code stack} as fits and shrinks it by that amount.
     * Returns how many were stored.
     */
    public int deposit(ItemStack stack, int maxKinds, long maxPerKind) {
        if (stack.isEmpty()) return 0;
        Entry entry = find(stack);
        if (entry == null) {
            if (entries.size() >= maxKinds) return 0;
            entry = new Entry(stack, 0);
            entries.add(entry);
        }
        int moved = (int) Math.min(stack.getCount(), Math.max(0, maxPerKind - entry.count));
        if (moved <= 0) {
            if (entry.count == 0) entries.remove(entry);
            return 0;
        }
        entry.count += moved;
        stack.shrink(moved);
        return moved;
    }

    /** Takes up to {@code amount} of the entry at {@code index} (at most one stack). */
    public ItemStack withdraw(int index, int amount) {
        if (index < 0 || index >= entries.size() || amount <= 0) return ItemStack.EMPTY;
        Entry entry = entries.get(index);
        int take = (int) Math.min(Math.min(amount, entry.item.getMaxStackSize()), entry.count);
        if (take <= 0) return ItemStack.EMPTY;
        entry.count -= take;
        if (entry.count <= 0) entries.remove(index);
        return entry.item.copyWithCount(take);
    }

    /** Puts a stack taken by {@link #withdraw} back (when the player had no room for it). */
    public void putBack(ItemStack stack) {
        if (stack.isEmpty()) return;
        Entry entry = find(stack);
        if (entry == null) entries.add(new Entry(stack, stack.getCount()));
        else entry.count += stack.getCount();
    }

    /** Empties the vault, returning everything as normal-sized stacks. */
    public List<ItemStack> takeAll() {
        List<ItemStack> out = new ArrayList<>();
        for (Entry e : entries) {
            long left = e.count;
            int max = Math.max(1, e.item.getMaxStackSize());
            while (left > 0) {
                int n = (int) Math.min(max, left);
                out.add(e.item.copyWithCount(n));
                left -= n;
            }
        }
        entries.clear();
        return out;
    }

    private Entry find(ItemStack stack) {
        for (Entry e : entries) if (ItemStack.isSameItemSameComponents(e.item, stack)) return e;
        return null;
    }

    // Saving

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("level", level);
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag t = new CompoundTag();
            t.put("item", e.item.save(registries));
            t.putLong("count", e.count);
            list.add(t);
        }
        tag.put("entries", list);
        return tag;
    }

    public static ItemVault load(CompoundTag tag, HolderLookup.Provider registries) {
        ItemVault vault = new ItemVault();
        vault.level = Math.max(1, tag.getInt("level"));
        for (Tag raw : tag.getList("entries", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) raw;
            ItemStack item = ItemStack.parseOptional(registries, t.getCompound("item"));
            long count = t.getLong("count");
            if (!item.isEmpty() && count > 0) vault.entries.add(new Entry(item, count));
        }
        return vault;
    }
}
