package com.admin82.factions.compat.tacz;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;

/**
 * TaCZ integration. Safe to call whether or not TaCZ is installed: without it, guards get no TaCZ guns
 * (they fall back to crossbows) and TaCZ gunfire isn't heard.
 */
public final class Tacz {

    private static Boolean loaded;

    private Tacz() {}

    public static boolean loaded() {
        if (loaded == null) loaded = ModList.get().isLoaded("tacz");
        return loaded;
    }

    /** Hooks up TaCZ events. Called once while the mod loads. */
    public static void init() {
        if (loaded()) NeoForge.EVENT_BUS.register(TaczHearing.class);
    }

    /** A loaded TaCZ gun, or an empty stack if TaCZ isn't installed or the gun doesn't exist. */
    public static ItemStack createGun(RegistryAccess registries, ResourceLocation gunId) {
        return loaded() ? TaczGuns.createGun(registries, gunId) : ItemStack.EMPTY;
    }

    /** Fires the shooter's TaCZ gun. False when there's no TaCZ gun to fire. */
    public static boolean shoot(LivingEntity shooter, float pitch, float yaw) {
        return loaded() && TaczGuns.shoot(shooter, pitch, yaw);
    }

    /** Readies a TaCZ gun held in the main hand. */
    public static void drawGun(LivingEntity shooter) {
        if (loaded()) TaczGuns.drawGun(shooter);
    }
}
