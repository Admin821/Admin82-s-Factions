package com.admin82.factions;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

/**
 * Handles outpost KOTH capture, upkeep charging, disintegration, and particle effects.
 * Runs every server tick, batched into coarser intervals for cheap processing.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public class OutpostEvents {

    private static int tickCounter    = 0;
    private static int particleTick   = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        particleTick++;

        // ── Particles every 20 ticks (1 second) ──────────────────────────────
        if (particleTick >= 20) {
            particleTick = 0;
            tickParticles(event.getServer());
        }

        // ── KOTH + upkeep + disintegration every 20 ticks (1 second) ─────────
        if (tickCounter < 20) return;
        tickCounter = 0;
        tickOutposts(event.getServer());
    }

    // ── KOTH capture + upkeep + disintegration ────────────────────────────────

    private static void tickOutposts(MinecraftServer server) {
        OutpostData    outposts = OutpostData.get(server);
        FactionManager fmgr    = FactionManager.get(server);
        EconomyManager eco     = EconomyManager.get(server);
        long now = System.currentTimeMillis();

        List<UUID> toRemove = new ArrayList<>();

        for (OutpostEntry entry : new ArrayList<>(outposts.getAllOutposts())) {

            // Find the server level for this outpost
            ServerLevel level = null;
            for (ServerLevel lvl : server.getAllLevels()) {
                if (lvl.dimension().location().toString().equals(entry.dimension)) {
                    level = lvl; break;
                }
            }
            if (level == null) continue;

            // ── KOTH capture ──────────────────────────────────────────────────
            tickKoth(server, entry, fmgr, level);

            // ── Upkeep (distance-ramped: +0.1× per 5 chunks from faction table) ────────
            if (!entry.disintegrating && now >= entry.upkeepNextDue) {
                long upkeepCost = computeOutpostUpkeep(server, entry, fmgr);
                if (eco.deductVault(entry.ownerFactionId, upkeepCost)) {
                    entry.upkeepNextDue = now + OutpostEntry.UPKEEP_INTERVAL_MS;
                    outposts.setDirty();
                } else {
                    // Begin disintegration
                    entry.disintegrating    = true;
                    entry.disintegrateStartMs = now;
                    outposts.setDirty();
                    Faction f = fmgr.getAllFactions().get(entry.ownerFactionId);
                    if (f != null) {
                        notifyFaction(server, f, Component.literal(
                                "§c[Outpost] Upkeep unpaid! Your outpost is now disintegrating over the next hour."));
                    }
                }
            }

            // ── Disintegration ────────────────────────────────────────────────
            if (entry.disintegrating) {
                tickDisintegration(server, entry, level, outposts, toRemove, now);
            }
        }

        for (UUID id : toRemove) outposts.removeOutpost(id);
    }

    private static void tickKoth(MinecraftServer server, OutpostEntry entry,
                                 FactionManager fmgr, ServerLevel level) {
        // If this outpost is in a war's outpost phase, FactionWarEvents handles the KOTH
        com.admin82.factions.war.WarManager warmgr = com.admin82.factions.war.WarManager.get(server);
        for (com.admin82.factions.war.ActiveWar w : warmgr.getActiveWars()) {
            if (w.outpostPhase && entry.id.equals(w.outpostId)) return;
        }

        // The KOTH zone is only active while the owner faction is engaged in a war
        if (!isFactionAtWar(warmgr, entry.ownerFactionId)) {
            if (entry.captureProgress > 0 || entry.capturingFactionId != null) {
                entry.captureProgress    = 0f;
                entry.capturingFactionId = null;
                OutpostData.get(server).setDirty();
            }
            return;
        }

        OutpostData outposts = OutpostData.get(server);
        int captureRadius = OutpostEntry.CAPTURE_RADIUS_BLOCKS;

        // Find which factions have players on the point (excluding the owner)
        Map<UUID, Integer> factionsOnPoint = new HashMap<>();
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (!sp.level().dimension().location().toString().equals(entry.dimension)) continue;
            double dx = sp.getX() - (entry.managerPos.getX() + 0.5);
            double dz = sp.getZ() - (entry.managerPos.getZ() + 0.5);
            if (Math.sqrt(dx * dx + dz * dz) > captureRadius) continue;

            Faction f = fmgr.getFactionForPlayer(sp.getUUID());
            if (f == null || f.getId().equals(entry.ownerFactionId)) continue;
            factionsOnPoint.merge(f.getId(), 1, Integer::sum);
        }

        boolean contested = factionsOnPoint.size() > 1;
        if (factionsOnPoint.isEmpty()) {
            // Nobody contesting — reset progress slowly
            if (entry.captureProgress > 0) {
                entry.captureProgress = Math.max(0f, entry.captureProgress - 0.5f);
                outposts.setDirty();
            }
            entry.capturingFactionId = null;
            return;
        }
        if (contested) {
            // Multiple factions — contested, no progress
            return;
        }

        UUID attFactionId = factionsOnPoint.keySet().iterator().next();

        // Outposts can only be captured if an active war exists between
        // the attacker and the outpost owner.
        com.admin82.factions.war.WarManager warMgr = com.admin82.factions.war.WarManager.get(server);
        if (warMgr.getWarBetween(attFactionId, entry.ownerFactionId) == null) {
            // No war — quietly reset any stale progress and ignore the presence
            if (entry.captureProgress > 0) {
                entry.captureProgress = 0f;
                entry.capturingFactionId = null;
                outposts.setDirty();
            }
            return;
        }
        if (!attFactionId.equals(entry.capturingFactionId)) {
            entry.capturingFactionId = attFactionId;
            entry.captureProgress    = 0f;
            outposts.setDirty();

            // Notify the owner faction that a capture has started
            Faction ownerFaction    = fmgr.getAllFactions().get(entry.ownerFactionId);
            Faction attackerFaction = fmgr.getAllFactions().get(attFactionId);
            String  attackerName    = attackerFaction != null ? attackerFaction.getName() : "Unknown";
            if (ownerFaction != null) {
                notifyFaction(server, ownerFaction, Component.literal(
                        "§c[Outpost] §e" + attackerName
                        + " §cis attempting to capture your outpost! §("
                        + com.admin82.factions.war.WarManager.get(server).getOutpostKothTime() + "s to capture)"));
            }
        }

        entry.captureProgress += 1f; // 1 second per tick
        float captureTimeSec = (float) com.admin82.factions.war.WarManager.get(server).getOutpostKothTime();
        outposts.setDirty();

        if (entry.captureProgress >= captureTimeSec) {
            // Capture complete — transfer ownership
            UUID prevOwner  = entry.ownerFactionId;
            entry.ownerFactionId      = attFactionId;
            entry.capturingFactionId  = null;
            entry.captureProgress     = 0f;
            outposts.setDirty();

            // Update block entity
            if (level.getBlockEntity(entry.managerPos) instanceof
                    com.admin82.factions.blockentity.OutpostManagerBlockEntity be) {
                be.setLinkedFactionId(attFactionId);
            }

            Faction prevF = fmgr.getAllFactions().get(prevOwner);
            Faction newF  = fmgr.getAllFactions().get(attFactionId);
            String prevName = prevF != null ? prevF.getName() : "Unknown";
            String newName  = newF  != null ? newF.getName()  : "Unknown";

            if (prevF != null)
                notifyFaction(server, prevF, Component.literal(
                        "§c[Outpost] Your outpost has been captured by §e" + newName + "§c!"));
            if (newF != null)
                notifyFaction(server, newF, Component.literal(
                        "§a[Outpost] §eVICTORY! §aYou captured §f" + prevName + "§a's outpost!"));
        }
    }

    /**
     * Removes one random structure block per call.
     * After all blocks removed, removes the manager block and marks outpost for deletion.
     */
    private static void tickDisintegration(MinecraftServer server, OutpostEntry entry,
                                           ServerLevel level, OutpostData outposts,
                                           List<UUID> toRemove, long now) {
        if (entry.structureBlocks.isEmpty()) {
            // All structure gone — remove manager block and delete entry
            level.removeBlock(entry.managerPos, false);
            toRemove.add(entry.id);
            Faction f = FactionManager.get(server).getAllFactions().get(entry.ownerFactionId);
            if (f != null)
                notifyFaction(server, f, Component.literal(
                        "§c[Outpost] Your outpost has fully disintegrated!"));
            return;
        }

        // Remove one block every (DISINTEGRATE_MS / blockCount) ms
        long msPerBlock = OutpostEntry.DISINTEGRATE_MS / Math.max(1, entry.structureBlocks.size() + 1);
        long elapsed    = now - entry.disintegrateStartMs;
        int  shouldHaveRemoved = (int) (elapsed / msPerBlock);
        int  alreadyRemoved    = 25 - entry.structureBlocks.size(); // original was 25 blocks

        if (shouldHaveRemoved > alreadyRemoved && !entry.structureBlocks.isEmpty()) {
            // Remove a random block
            int idx = new Random().nextInt(entry.structureBlocks.size());
            BlockPos bp = entry.structureBlocks.remove(idx);
            level.removeBlock(bp, false);
            outposts.setDirty();
        }
    }

    // ── Particle effects ──────────────────────────────────────────────────────

    private static void tickParticles(MinecraftServer server) {
        OutpostData outposts = OutpostData.get(server);
        com.admin82.factions.war.WarManager warMgr = com.admin82.factions.war.WarManager.get(server);

        for (OutpostEntry entry : outposts.getAllOutposts()) {
            ServerLevel level = null;
            for (ServerLevel lvl : server.getAllLevels()) {
                if (lvl.dimension().location().toString().equals(entry.dimension)) {
                    level = lvl; break;
                }
            }
            if (level == null) continue;

            boolean disint = entry.disintegrating;

            if (disint) {
                // ── Disintegrating: smoke rises from the remaining structure blocks, no KOTH ring ──
                double cx = entry.managerPos.getX() + 0.5;
                double cy = entry.managerPos.getY() + 1.0;
                double cz = entry.managerPos.getZ() + 0.5;

                // A few smoke puffs near the manager block
                level.sendParticles(ParticleTypes.SMOKE, cx, cy + 0.5, cz, 2, 0.3, 0.3, 0.3, 0.02);

                // Sparse smoke from random structure blocks (max 5 per tick to avoid fog)
                if (!entry.structureBlocks.isEmpty()) {
                    int count = Math.min(5, entry.structureBlocks.size());
                    java.util.Random rng = new java.util.Random();
                    for (int i = 0; i < count; i++) {
                        BlockPos bp = entry.structureBlocks.get(rng.nextInt(entry.structureBlocks.size()));
                        level.sendParticles(ParticleTypes.SMOKE,
                                bp.getX() + 0.5, bp.getY() + 1.0, bp.getZ() + 0.5,
                                1, 0.2, 0.1, 0.2, 0.01);
                    }
                }
                continue; // skip KOTH ring entirely
            }

            // Only show KOTH particles when the owner faction is at war
            if (!isFactionAtWar(warMgr, entry.ownerFactionId)) continue;

            double radius = OutpostEntry.CAPTURE_RADIUS_BLOCKS;
            double cx     = entry.managerPos.getX() + 0.5;
            double cy     = entry.managerPos.getY() + 1.0;
            double cz     = entry.managerPos.getZ() + 0.5;
            boolean beingCaptured = entry.capturingFactionId != null;

            // Ring: 36 points (every 10 degrees), 1 particle each — tight but not foggy
            // Use per-player sendParticles so longDistance=true works without NPE.
            var nearbyPlayers = level.players();
            int numPoints = 36;
            for (int i = 0; i < numPoints; i++) {
                double angle = (2 * Math.PI * i) / numPoints;
                double px    = cx + radius * Math.cos(angle);
                double pz    = cz + radius * Math.sin(angle);

                var particle = beingCaptured ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME;
                for (net.minecraft.server.level.ServerPlayer sp : nearbyPlayers) {
                    level.sendParticles(sp, particle, true, px, cy, pz, 2, 0.05, 0.05, 0.05, 0.0);
                }
            }

            // Vertical pillar above the outpost manager — 3 blocks tall
            var pillarParticle = beingCaptured ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME;
            for (int h = 1; h <= 3; h++) {
                for (net.minecraft.server.level.ServerPlayer sp : nearbyPlayers) {
                    level.sendParticles(sp, pillarParticle, true, cx, cy + h, cz, 2, 0.05, 0.05, 0.05, 0.0);
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    /** Returns true if the given faction is the attacker or defender in any active war. */
    private static boolean isFactionAtWar(com.admin82.factions.war.WarManager warmgr, java.util.UUID factionId) {
        for (com.admin82.factions.war.ActiveWar w : warmgr.getActiveWars()) {
            if (w.attackerFactionId.equals(factionId) || w.defenderFactionId.equals(factionId)) return true;
        }
        return false;
    }

    /**
     * Outpost daily upkeep with a distance ramp from the owner faction's table.
     * Formula: {@code base × (1.0 + floor(chebDist / 5) × 0.1)}
     * This scales without any cap — every additional 5 chunks adds another +0.1×:
     * 0-4 chunks → 1.0×, 5-9 → 1.1×, 10-14 → 1.2×, 100 chunks → 3.0×, 1000 chunks → 21.0×, etc.
     */
    private static long computeOutpostUpkeep(MinecraftServer server, OutpostEntry entry,
                                             FactionManager fmgr) {
        long base = OutpostEntry.UPKEEP_COST_COPPER;
        FactionManager.TableLocation tableLoc = fmgr.getFactionTable(entry.ownerFactionId);
        if (tableLoc == null) return base;
        // Only apply ramp when outpost and table are in the same dimension
        if (!tableLoc.dimension().equals(entry.dimension)) return base;
        int coreX = net.minecraft.core.SectionPos.blockToSectionCoord(tableLoc.pos().getX());
        int coreZ = net.minecraft.core.SectionPos.blockToSectionCoord(tableLoc.pos().getZ());
        int outpostCX = net.minecraft.core.SectionPos.blockToSectionCoord(entry.managerPos.getX());
        int outpostCZ = net.minecraft.core.SectionPos.blockToSectionCoord(entry.managerPos.getZ());
        int dist  = Math.max(Math.abs(outpostCX - coreX), Math.abs(outpostCZ - coreZ));
        // Configurable ramp via /faction economy outpostramp <value>
        double rampPerBand = com.admin82.factions.economy.EconomyManager.get(server).getOutpostDistanceRamp();
        double mult = 1.0 + (dist / 5) * rampPerBand; // unbounded: each 5-chunk band adds rampPerBand
        return (long) Math.ceil(base * mult);
    }
    private static void notifyFaction(MinecraftServer server, Faction faction, Component msg) {
        for (var m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) sp.displayClientMessage(msg, false);
        }
    }
}
