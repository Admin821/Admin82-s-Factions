package com.admin82.factions.economy;

import com.admin82.factions.registry.ModItems;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
 * Coin storage made of normal slots (meters, outposts). Money is the slots plus "loose" copper that didn't fit;
 * {@link #consolidate} rebuilds the slots with as few coins as possible, so copper turns into silver, gold and
 * platinum on its own.
 */
public final class CoinSlots {

    public static final int SIZE = 9;

    private CoinSlots() {}

    /** Slots that only take coins; {@code onChange} runs whenever they change. */
    public static SimpleContainer create(Runnable onChange) {
        return new SimpleContainer(SIZE) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return EconomyManager.isCoin(stack.getItem());
            }

            @Override
            public void setChanged() {
                super.setChanged();
                onChange.run();
            }
        };
    }

    /** What the coins in the slots are worth, in copper. */
    public static long value(Container slots) {
        long total = 0;
        for (int i = 0; i < slots.getContainerSize(); i++) total += EconomyManager.stackCopper(slots.getItem(i));
        return total;
    }

    /**
     * Rewrites the slots to hold {@code value(slots) + loose} with as few coins as possible. Returns the copper
     * that didn't fit (the new loose amount). Leaves the slots alone when they're already right.
     */
    public static long consolidate(Container slots, long loose) {
        return fill(slots, value(slots) + Math.max(0, loose));
    }

    /** Sets the slots to hold {@code copper} with as few coins as possible; returns what didn't fit. */
    public static long fill(Container slots, long copper) {
        Item[] items = {ModItems.PLATINUM_COIN.get(), ModItems.GOLD_COIN.get(), ModItems.SILVER_COIN.get(), ModItems.COPPER_COIN.get()};
        long[] values = {Currency.COPPER_PER_PLATINUM, Currency.COPPER_PER_GOLD, Currency.COPPER_PER_SILVER, 1};
        long left = Math.max(0, copper);
        ItemStack[] wanted = new ItemStack[slots.getContainerSize()];
        Arrays.fill(wanted, ItemStack.EMPTY);
        int slot = 0;
        for (int i = 0; i < items.length && slot < wanted.length; i++) {
            long count = left / values[i];
            while (count > 0 && slot < wanted.length) {
                int size = (int) Math.min(count, items[i].getDefaultMaxStackSize());
                wanted[slot++] = new ItemStack(items[i], size);
                left -= size * values[i];
                count -= size;
            }
        }
        boolean same = true;
        for (int i = 0; i < wanted.length && same; i++) same = ItemStack.matches(slots.getItem(i), wanted[i]);
        if (!same) for (int i = 0; i < wanted.length; i++) slots.setItem(i, wanted[i]);
        return left;
    }
}
