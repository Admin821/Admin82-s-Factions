package com.admin82.factions.war;

import com.admin82.factions.Config;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.ActiveWar;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

/** Particles around capture points so players can see them. */
final class KothParticles {

    private KothParticles() {}

    // KOTH particle effects

    /**
     * Spawns particles in a clear ring around the faction table border to show who controls it:
     *   No one    → white campfire signal smoke
     *   Attackers → orange flame
     *   Defenders → blue soul-fire flame
     *   Both      → alternating flame and soul-fire
     */
    static void spawnKothParticles(MinecraftServer server, ActiveWar war,
                                           boolean attackersOn, boolean defendersOn) {
        ServerLevel level = null;
        for (ServerLevel lvl : server.getAllLevels()) {
            if (lvl.dimension().location().toString().equals(war.defenderDimension)) {
                level = lvl; break;
            }
        }
        if (level == null) return;

        double radius = Config.WAR_CAPTURE_RADIUS_BLOCKS.get();
        spawnKothParticles(level, war.defenderTablePos, attackersOn, defendersOn);
    }

    /** Low-level KOTH ring/pillar particles around an arbitrary table position. */
    static void spawnKothParticles(ServerLevel level, BlockPos tablePos,
                                           boolean teamAOn, boolean teamBOn) {
        double radius = Config.WAR_CAPTURE_RADIUS_BLOCKS.get();
        double cx = tablePos.getX() + 0.5;
        double cy = tablePos.getY() + 1.0;
        double cz = tablePos.getZ() + 0.5;

        var nearbyPlayers = level.players();
        int numPoints = 36;
        for (int i = 0; i < numPoints; i++) {
            double angle = (2 * Math.PI * i) / numPoints;
            double px = cx + radius * Math.cos(angle);
            double pz = cz + radius * Math.sin(angle);

            SimpleParticleType particle;
            if (teamAOn && teamBOn) {
                particle = (i % 2 == 0)
                        ? ParticleTypes.FLAME
                        : ParticleTypes.SOUL_FIRE_FLAME;
            } else if (teamAOn) {
                particle = ParticleTypes.FLAME;
            } else if (teamBOn) {
                particle = ParticleTypes.SOUL_FIRE_FLAME;
            } else {
                particle = ParticleTypes.SMOKE;
            }

            for (ServerPlayer sp : nearbyPlayers) {
                level.sendParticles(sp, particle, true, px, cy, pz, 2, 0.05, 0.05, 0.05, 0.0);
            }
        }

        SimpleParticleType pillarParticle;
        if (teamAOn || teamBOn) {
            pillarParticle = teamAOn
                    ? ParticleTypes.FLAME
                    : ParticleTypes.SOUL_FIRE_FLAME;
        } else {
            pillarParticle = ParticleTypes.SMOKE;
        }
        for (int h = 1; h <= 3; h++) {
            for (ServerPlayer sp : nearbyPlayers) {
                level.sendParticles(sp, pillarParticle, true, cx, cy + h, cz, 2, 0.05, 0.05, 0.05, 0.0);
            }
        }
    }
}
