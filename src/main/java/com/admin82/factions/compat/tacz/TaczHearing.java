package com.admin82.factions.compat.tacz;

import com.admin82.factions.entity.monument.AiTuning;
import com.admin82.factions.entity.monument.PmcHearing;
import com.admin82.factions.entity.monument.RoguePmcEntity;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;

/** Lets monument guards hear TaCZ gunfire and bullets landing near them. Registered by {@link Tacz#init()}. */
final class TaczHearing {
    private static final double BULLET_IMPACT_RADIUS = 5.0;

    private TaczHearing() {}

    @SubscribeEvent
    public static void onGunShoot(GunShootEvent event) {
        if (!event.getLogicalSide().isServer()) return;
        LivingEntity shooter = event.getShooter();
        // Guards announce their own gunfire to allies directly (see RoguePmcEntity#fireAtPoint).
        if (shooter == null || shooter instanceof RoguePmcEntity
                || !(shooter.level() instanceof ServerLevel level)) return;
        if (shooter instanceof Player player && (player.isCreative() || player.isSpectator())) return;
        PmcHearing.emit(level, shooter.getEyePosition(), AiTuning.GUNSHOT_RADIUS, shooter, PmcHearing.SoundKind.GUNSHOT);
    }

    @SubscribeEvent
    public static void onBulletImpact(AmmoHitBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(event.getAmmo().getOwner() instanceof LivingEntity owner) || owner instanceof RoguePmcEntity) return;
        if (owner instanceof Player player && (player.isCreative() || player.isSpectator())) return;
        PmcHearing.emit(level, event.getHitResult().getLocation(), BULLET_IMPACT_RADIUS, owner,
                PmcHearing.SoundKind.BULLET_IMPACT);
    }
}
