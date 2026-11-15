package com.admin82.factions.war;

import com.admin82.factions.faction.*;
import com.admin82.factions.war.ActiveWar;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.*;

/** Players who are out of lives watch the rest of the war as spectators. */
final class WarSpectators {

    private WarSpectators() {}

    // Spectator helpers

    private static final double SPECTATOR_LEASH_BLOCKS = 10.0;

    /**
     * Teleports eliminated spectators back to within {@link #SPECTATOR_LEASH_BLOCKS} blocks
     * of their nearest living teammate. Prevents spectators from roaming freely.
     */
    static void enforceSpectatorLeash(MinecraftServer server, Map<UUID, Integer> side) {
        for (Map.Entry<UUID, Integer> entry : side.entrySet()) {
            if (entry.getValue() > 0) continue; // still has lives
            ServerPlayer sp = server.getPlayerList().getPlayer(entry.getKey());
            if (sp == null || !sp.isSpectator()) continue;

            // Find nearest living teammate
            double minDist = Double.MAX_VALUE;
            ServerPlayer nearest = null;
            for (Map.Entry<UUID, Integer> other : side.entrySet()) {
                if (other.getKey().equals(entry.getKey()) || other.getValue() <= 0) continue;
                ServerPlayer mate = server.getPlayerList().getPlayer(other.getKey());
                if (mate == null) continue;
                double dx = sp.getX() - mate.getX();
                double dy = sp.getY() - mate.getY();
                double dz = sp.getZ() - mate.getZ();
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < minDist) { minDist = d; nearest = mate; }
            }
            if (nearest == null) continue; // all dead, free to roam

            boolean wrongDim = !sp.level().dimension().equals(nearest.level().dimension());
            if (wrongDim || minDist > SPECTATOR_LEASH_BLOCKS) {
                sp.teleportTo((ServerLevel) nearest.level(),
                        nearest.getX(), nearest.getY() + 1.0, nearest.getZ(),
                        sp.getYRot(), sp.getXRot());
            }
        }
    }

    /** Marks players this mod turned into spectators, so only they are switched back (never an op who chose it). */
    private static final String SPECTATOR_TAG = "adminsfactions.warSpectator";

    /** Eliminates a player into spectator mode, remembering that the war did it. */
    static void makeSpectator(ServerPlayer sp) {
        sp.getPersistentData().putBoolean(SPECTATOR_TAG, true);
        sp.setGameMode(GameType.SPECTATOR);
    }

    static boolean isWarSpectator(ServerPlayer sp) {
        return sp.getPersistentData().getBoolean(SPECTATOR_TAG);
    }

    /** Switches a war spectator back to survival. Does nothing for players the war didn't make spectators. */
    static boolean restore(ServerPlayer sp) {
        if (!isWarSpectator(sp)) return false;
        sp.getPersistentData().remove(SPECTATOR_TAG);
        if (sp.isSpectator()) sp.setGameMode(GameType.SURVIVAL);
        return true;
    }

    /** Restores all eliminated (spectator) players in a war back to survival mode. */
    static void restoreWarSpectators(MinecraftServer server, ActiveWar war) {
        Set<UUID> all = new HashSet<>(war.attackerLives.keySet());
        all.addAll(war.defenderLives.keySet());
        for (UUID uid : all) {
            ServerPlayer sp = server.getPlayerList().getPlayer(uid);
            // Untagged players eliminated before this was tracked are restored too (spectator with no lives left).
            boolean eliminated = sp != null && sp.isSpectator()
                    && war.attackerLives.getOrDefault(uid, war.defenderLives.getOrDefault(uid, 1)) <= 0;
            if (sp != null && (restore(sp) || eliminated && !isWarSpectator(sp))) {
                if (sp.isSpectator()) sp.setGameMode(GameType.SURVIVAL);
                sp.displayClientMessage(Component.literal("§aThe war has ended, so you're back in survival."), false);
            }
        }
    }
}
