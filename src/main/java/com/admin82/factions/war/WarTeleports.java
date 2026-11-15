package com.admin82.factions.war;

import com.admin82.factions.faction.*;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.WarManager;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/** Moves attackers near the defenders when the grace period ends. */
final class WarTeleports {

    private WarTeleports() {}

    // Teleport attackers on war start

    /**
     * When the grace period ends, teleports every committed attacker to a random
     * unclaimed chunk within 1-5 chunks of the defender's territory.
     */
    static void teleportAttackers(MinecraftServer server, ActiveWar war, FactionManager fmgr) {
        Faction defender = fmgr.getAllFactions().get(war.defenderFactionId);
        if (defender == null) return;

        // Find the target ServerLevel
        ServerLevel targetLevel = null;
        for (ServerLevel lvl : server.getAllLevels()) {
            if (lvl.dimension().location().toString().equals(war.defenderDimension)) {
                targetLevel = lvl;
                break;
            }
        }
        if (targetLevel == null) return;
        final ServerLevel level = targetLevel;

        String dim = war.defenderDimension;

        // Collect defender's claimed chunk keys in this dimension
        Set<String> defClaims = new HashSet<>();
        for (LandClaim c : defender.getLandClaims()) {
            if (c.dimension().toString().equals(dim)) {
                defClaims.add(c.chunkX() + "," + c.chunkZ());
            }
        }

        // All claimed chunks in this dimension (for exclusion)
        Set<String> allClaimed = new HashSet<>();
        for (Faction f : fmgr.getAllFactions().values()) {
            for (LandClaim c : f.getLandClaims()) {
                if (c.dimension().toString().equals(dim)) {
                    allClaimed.add(c.chunkX() + "," + c.chunkZ());
                }
            }
        }

        // If no defender claims, fall back to area around their table
        if (defClaims.isEmpty()) {
            int tcx = SectionPos.blockToSectionCoord(war.defenderTablePos.getX());
            int tcz = SectionPos.blockToSectionCoord(war.defenderTablePos.getZ());
            for (int d = 2; d <= 6; d++) {
                defClaims.add((tcx + d) + "," + tcz);
                defClaims.add((tcx - d) + "," + tcz);
                defClaims.add(tcx + "," + (tcz + d));
                defClaims.add(tcx + "," + (tcz - d));
            }
        }

        // Build ring of unclaimed spawn chunks (configurable distance around defender claims)
        int tpDist = WarManager.get(server).getTpDistanceChunks();
        Set<String> spawnKeys = new LinkedHashSet<>();
        List<int[]>  spawnList = new ArrayList<>();
        for (String key : defClaims) {
            String[] p = key.split(",");
            int cx = Integer.parseInt(p[0]), cz = Integer.parseInt(p[1]);
            for (int dx = -tpDist; dx <= tpDist; dx++) {
                for (int dz = -tpDist; dz <= tpDist; dz++) {
                    int chebDist = Math.max(Math.abs(dx), Math.abs(dz));
                    if (chebDist < 1 || chebDist > tpDist) continue;
                    int ncx = cx + dx, ncz = cz + dz;
                    String nk = ncx + "," + ncz;
                    if (!allClaimed.contains(nk) && spawnKeys.add(nk)) {
                        spawnList.add(new int[]{ncx, ncz});
                    }
                }
            }
        }

        // If still empty, use raw offset from table
        if (spawnList.isEmpty()) {
            int tcx = SectionPos.blockToSectionCoord(war.defenderTablePos.getX());
            int tcz = SectionPos.blockToSectionCoord(war.defenderTablePos.getZ());
            int fallbackMin = Math.max(2, tpDist - 1);
            int fallbackMax = tpDist + 2;
            for (int d = fallbackMin; d <= fallbackMax; d++) {
                spawnList.add(new int[]{tcx + d, tcz});
                spawnList.add(new int[]{tcx - d, tcz});
                spawnList.add(new int[]{tcx, tcz + d});
                spawnList.add(new int[]{tcx, tcz - d});
            }
        }

        Collections.shuffle(spawnList, new Random());
        int spawnCount = spawnList.size();
        int[] idx = {0};

        for (UUID uid : war.attackerLives.keySet()) {
            if (war.attackerLives.getOrDefault(uid, 0) <= 0) continue;
            ServerPlayer sp = server.getPlayerList().getPlayer(uid);
            if (sp == null) continue;

            int[] chunk = spawnList.get(idx[0] % spawnCount);
            idx[0]++;

            // Random position within the chunk, surface Y
            Random rng = new Random();
            int bx = chunk[0] * 16 + rng.nextInt(16);
            int bz = chunk[1] * 16 + rng.nextInt(16);
            level.getChunk(bx >> 4, bz >> 4); // the height map is empty (bedrock level) until the chunk is loaded
            int by = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);

            sp.teleportTo(level, bx + 0.5, by, bz + 0.5, sp.getYRot(), sp.getXRot());
            sp.displayClientMessage(Component.literal(
                    "§c[War] §eTeleported to the front line! Find the defending base!"), false);
        }
    }
}
