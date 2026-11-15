package com.admin82.factions.entity.monument;

import com.admin82.factions.AdminsFactions;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.VanillaGameEvent;

import java.util.List;
import javax.annotation.Nullable;

/**
 * Turns noises in the world into things monument guards can hear. Footsteps, doors, crates, blocks
 * and landing are quiet "suspicious" sounds; gunshots, explosions and bullets hitting nearby are
 * loud "alarm" sounds (TaCZ gunfire comes in through compat.tacz.TaczHearing). Sneaking players make
 * no footstep noise.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public final class PmcHearing {
    public enum SoundKind {
        /** Quiet noise: the guard gets suspicious and investigates carefully. */
        NOISE(false),
        /** Gunfire or explosions: the guard goes on alert and searches. */
        GUNSHOT(true),
        /** A bullet hit right next to the guard: alert, plus the guard knows it is under fire. */
        BULLET_IMPACT(true);

        private final boolean alarming;

        SoundKind(boolean alarming) { this.alarming = alarming; }

        public boolean alarming() { return alarming; }
    }

    private static final double MAX_RADIUS_SCALE = 1.5;

    private PmcHearing() {}

    /** Notifies every guard close enough to possibly hear a sound of the given base radius. */
    public static void emit(ServerLevel level, Vec3 origin, double radius, @Nullable LivingEntity source, SoundKind kind) {
        double searchRadius = radius * MAX_RADIUS_SCALE;
        List<RoguePmcEntity> listeners = level.getEntitiesOfClass(RoguePmcEntity.class,
                new AABB(origin, origin).inflate(searchRadius), pmc -> pmc.isAlive() && pmc != source);
        for (RoguePmcEntity listener : listeners) listener.hearSound(origin, radius, source, kind);
    }

    @SubscribeEvent
    public static void onVanillaGameEvent(VanillaGameEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Holder<GameEvent> type = event.getVanillaEvent();
        Entity cause = event.getCause();
        if (type.is(GameEvent.EXPLODE.key())) {
            emit(level, event.getEventPosition(), 80.0, cause instanceof LivingEntity living ? living : null,
                    SoundKind.GUNSHOT);
            return;
        }
        if (!(cause instanceof Player player) || player.isCreative() || player.isSpectator()) return;
        double radius = noiseRadius(type, player);
        if (radius > 0.0) emit(level, event.getEventPosition(), radius, player, SoundKind.NOISE);
    }

    private static double noiseRadius(Holder<GameEvent> type, Player player) {
        if (type.is(GameEvent.STEP.key())) {
            if (player.isSteppingCarefully() || player.isCrouching()) return 0.0;
            return player.isSprinting() ? AiTuning.FOOTSTEP_RADIUS * 1.8 : AiTuning.FOOTSTEP_RADIUS;
        }
        if (type.is(GameEvent.HIT_GROUND.key())) return player.isCrouching() ? 0.0 : 12.0;
        if (type.is(GameEvent.BLOCK_DESTROY.key()) || type.is(GameEvent.BLOCK_PLACE.key())) return 16.0;
        if (type.is(GameEvent.BLOCK_OPEN.key()) || type.is(GameEvent.BLOCK_CLOSE.key())) return 14.0;
        if (type.is(GameEvent.CONTAINER_OPEN.key()) || type.is(GameEvent.CONTAINER_CLOSE.key())) return 10.0;
        if (type.is(GameEvent.PROJECTILE_SHOOT.key()) || type.is(GameEvent.PROJECTILE_LAND.key())) return 16.0;
        if (type.is(GameEvent.SPLASH.key()) || type.is(GameEvent.SWIM.key())) return 10.0;
        if (type.is(GameEvent.EAT.key()) || type.is(GameEvent.DRINK.key())) return 5.0;
        return 0.0;
    }
}
