package com.admin82.factions.seam;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One chunk's seam: which ore it gives, how rich it is, and how much is left at that richness. The ore doesn't exist
 * in the world; only a Seam Extractor can mine it.
 */
public final class Seam {

    /** Item id of the ore, "" when there's none (depleted from the start, or deleted by an admin). */
    public String ore;
    public SeamTier tier;
    public boolean endless;
    /** Ore left before it drops to the next tier. Fractions come from upgrades that wear it down slower or faster. */
    public double remaining;

    public Seam(String ore, SeamTier tier, boolean endless) {
        this.ore = ore;
        this.tier = tier;
        this.endless = endless;
        this.remaining = tier.amount();
    }

    /** No seam at all in the chunk. */
    public static Seam none() {
        return new Seam("", SeamTier.DEPLETED, false);
    }

    /** Whether the chunk has a seam (minable or depleted); false when there's none. */
    public boolean exists() {
        return !ore.isEmpty() && item() != Items.AIR;
    }

    public boolean minable() {
        return tier != SeamTier.DEPLETED && !ore.isEmpty() && item() != Items.AIR;
    }

    public Item item() {
        ResourceLocation id = ResourceLocation.tryParse(ore);
        return id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
    }

    public ItemStack stack() {
        return minable() ? new ItemStack(item()) : ItemStack.EMPTY;
    }

    /** Wears the seam down by {@code amount}; drops to the next tier when it runs out. Endless seams don't wear. */
    public void consume(double amount) {
        if (endless || tier == SeamTier.DEPLETED) return;
        remaining -= amount;
        while (remaining <= 0 && tier != SeamTier.DEPLETED) {
            tier = tier.next();
            remaining += tier.amount();
        }
        if (tier == SeamTier.DEPLETED) remaining = 0;
    }

    /** "Rich", "Endless (Rich speed)", "Depleted". */
    public String describe() {
        if (!exists()) return "§8No seam";
        if (!minable()) return SeamTier.DEPLETED.color + SeamTier.DEPLETED.label;
        return endless ? "§6Endless §7(" + tier.color + tier.speedLabel + "§7 speed)" : tier.color + tier.label;
    }

    public Seam copy() {
        Seam s = new Seam(ore, tier, endless);
        s.remaining = remaining;
        return s;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ore", ore);
        tag.putString("tier", tier.name());
        tag.putBoolean("endless", endless);
        tag.putDouble("remaining", remaining);
        return tag;
    }

    public static Seam load(CompoundTag tag) {
        Seam s = new Seam(tag.getString("ore"), SeamTier.byName(tag.getString("tier")), tag.getBoolean("endless"));
        s.remaining = tag.getDouble("remaining");
        return s;
    }
}
