package com.admin82.factions.supplydrop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class SupplyDropPool {
    public static final int SLOT_COUNT = 54;
    private static final double[] APPEARANCE_CHANCES = {
        100.0, 90.0, 80.0, 70.0, 60.0, 50.0, 25.0, 10.0, 5.0, 1.0, 0.1
    };
    private static final String[] RARITY_NAMES = {
        "Common", "Common", "Uncommon", "Uncommon", "Rare", "Rare",
        "Epic", "Epic", "Legendary", "Mythic", "Rarest"
    };

    private final String name;
    private final ItemStack[] slots = new ItemStack[SLOT_COUNT];
    private final int[] minCounts = new int[SLOT_COUNT];
    private final int[] maxCounts = new int[SLOT_COUNT];
    private final int[] rarityLevels = new int[SLOT_COUNT];

    public SupplyDropPool(String name) {
        this.name = name;
        Arrays.fill(slots, ItemStack.EMPTY);
        Arrays.fill(minCounts, 1);
        Arrays.fill(maxCounts, 1);
        Arrays.fill(rarityLevels, 0);
    }

    public String getName() {
        return name;
    }

    public ItemStack getSlot(int index) {
        if (index < 0 || index >= SLOT_COUNT) return ItemStack.EMPTY;
        ItemStack stack = slots[index];
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public void setSlot(int index, ItemStack stack) {
        if (index < 0 || index >= SLOT_COUNT) return;
        boolean wasEmpty = getSlot(index).isEmpty();
        slots[index] = stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        if (slots[index].isEmpty()) {
            minCounts[index] = 1;
            maxCounts[index] = 1;
            rarityLevels[index] = 0;
        } else if (wasEmpty) {
            minCounts[index] = 1;
            maxCounts[index] = Math.max(1, Math.min(slots[index].getCount(), slots[index].getMaxStackSize()));
            rarityLevels[index] = 0;
        }
    }

    public int getMinCount(int index) {
        return index < 0 || index >= SLOT_COUNT ? 1 : minCounts[index];
    }

    public int getMaxCount(int index) {
        return index < 0 || index >= SLOT_COUNT ? 1 : maxCounts[index];
    }

    public int getRarityLevel(int index) {
        return index < 0 || index >= SLOT_COUNT ? 0 : rarityLevels[index];
    }

    public static double getAppearanceChance(int rarityLevel) {
        return APPEARANCE_CHANCES[Math.max(0, Math.min(10, rarityLevel))];
    }

    public static String getAppearanceChanceText(int rarityLevel) {
        double chance = getAppearanceChance(rarityLevel);
        return chance < 1.0 ? "0.1%" : (int) chance + "%";
    }

    public static String getRarityName(int rarityLevel) {
        return RARITY_NAMES[Math.max(0, Math.min(10, rarityLevel))];
    }

    public int[] getMinCountsCopy() {
        return Arrays.copyOf(minCounts, SLOT_COUNT);
    }

    public int[] getMaxCountsCopy() {
        return Arrays.copyOf(maxCounts, SLOT_COUNT);
    }

    public int[] getRarityLevelsCopy() {
        return Arrays.copyOf(rarityLevels, SLOT_COUNT);
    }

    public void setGenerationSettings(int index, int minCount, int maxCount, int rarityLevel) {
        if (index < 0 || index >= SLOT_COUNT || getSlot(index).isEmpty()) return;
        int maxStackSize = Math.max(1, getSlot(index).getMaxStackSize());
        int clampedMin = Math.max(1, Math.min(minCount, maxStackSize));
        int clampedMax = Math.max(clampedMin, Math.min(maxCount, maxStackSize));
        minCounts[index] = clampedMin;
        maxCounts[index] = clampedMax;
        rarityLevels[index] = Math.max(0, Math.min(10, rarityLevel));
    }

    public List<ItemStack> nonEmptyItems() {
        return Arrays.stream(slots)
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(ItemStack::copy)
                .toList();
    }

    public List<ItemStack> generateItems(Random random) {
        List<ItemStack> generated = new ArrayList<>();
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack base = getSlot(i);
            if (base.isEmpty()) continue;
            if (random.nextDouble() * 100.0 >= getAppearanceChance(getRarityLevel(i))) continue;
            int min = getMinCount(i);
            int max = Math.max(min, getMaxCount(i));
            int count = min + random.nextInt(max - min + 1);
            ItemStack stack = base.copy();
            stack.setCount(Math.min(count, stack.getMaxStackSize()));
            generated.add(stack);
        }
        return generated;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("name", name);
        ListTag list = new ListTag();
        for (int i = 0; i < SLOT_COUNT; i++) {
            ItemStack stack = slots[i];
            if (stack != null && !stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putByte("Slot", (byte) i);
                entry.put("Item", stack.save(registries));
                entry.putInt("MinCount", minCounts[i]);
                entry.putInt("MaxCount", maxCounts[i]);
                entry.putInt("Rarity", rarityLevels[i]);
                list.add(entry);
            }
        }
        tag.put("Slots", list);
        return tag;
    }

    public static SupplyDropPool load(CompoundTag tag, HolderLookup.Provider registries) {
        SupplyDropPool pool = new SupplyDropPool(tag.getString("name"));
        ListTag list = tag.getList("Slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int index = entry.getByte("Slot") & 0xFF;
            if (index < SLOT_COUNT) {
                pool.slots[index] = ItemStack.parseOptional(registries, entry.getCompound("Item"));
                pool.minCounts[index] = entry.contains("MinCount", Tag.TAG_INT) ? entry.getInt("MinCount") : 1;
                pool.maxCounts[index] = entry.contains("MaxCount", Tag.TAG_INT)
                        ? entry.getInt("MaxCount")
                        : Math.max(1, Math.min(pool.getSlot(index).getCount(), pool.getSlot(index).getMaxStackSize()));
                pool.rarityLevels[index] = entry.contains("Rarity", Tag.TAG_INT) ? entry.getInt("Rarity") : 0;
                pool.setGenerationSettings(index, pool.minCounts[index], pool.maxCounts[index], pool.rarityLevels[index]);
            }
        }
        return pool;
    }
}