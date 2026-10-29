package com.admin82.factions.entity.monument;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

final class TaczPmcBridge {
    private static final int INFINITE_MAGAZINE_AMMO = 999;

    private TaczPmcBridge() {}

    static ItemStack createGun(LivingEntity shooter, ResourceLocation gunId) {
        return GunItemBuilder.create()
                .setId(gunId)
                .setAmmoCount(INFINITE_MAGAZINE_AMMO)
                .build(shooter.level().registryAccess());
    }

    static boolean shoot(LivingEntity shooter, float pitch, float yaw) {
        ItemStack weapon = shooter.getMainHandItem();
        IGun gun = IGun.getIGunOrNull(weapon);
        if (gun == null) return false;

        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        operator.aim(true);
        ShootResult result = operator.shoot(() -> pitch, () -> yaw);
        if (result == ShootResult.NO_AMMO) {
            refill(gun, weapon);
            result = operator.shoot(() -> pitch, () -> yaw);
        }
        if (result == ShootResult.SUCCESS) refill(gun, weapon);
        if (result == ShootResult.NEED_BOLT) operator.bolt();
        if (result == ShootResult.NOT_DRAW) operator.draw(shooter::getMainHandItem);
        if (result == ShootResult.IS_SPRINTING) shooter.setSprinting(false);
        return result != ShootResult.NOT_GUN
                && result != ShootResult.ID_NOT_EXIST
                && result != ShootResult.UNKNOWN_FAIL;
    }

    static void drawGun(LivingEntity shooter) {
        if (IGun.getIGunOrNull(shooter.getMainHandItem()) == null) return;
        IGunOperator operator = IGunOperator.fromLivingEntity(shooter);
        operator.initialData();
        operator.draw(shooter::getMainHandItem);
    }

    private static void refill(IGun gun, ItemStack weapon) {
        gun.setCurrentAmmoCount(weapon, INFINITE_MAGAZINE_AMMO);
        gun.setBulletInBarrel(weapon, true);
    }
}