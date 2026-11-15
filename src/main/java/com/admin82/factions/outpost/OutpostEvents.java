package com.admin82.factions.outpost;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.Treasury;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Random;

/**
 * Once a second: outpost upkeep (paid from the outpost's own coins), flags repairing after a while
 * without hits, outposts falling apart, and particles.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public class OutpostEvents {

    private static final Random RANDOM = new Random();
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < 20) return;
        tickCounter = 0;
        MinecraftServer server = event.getServer();
        tickOutposts(server);
        tickParticles(server);
    }

    private static void tickOutposts(MinecraftServer server) {
        OutpostData outposts = OutpostData.get(server);
        FactionManager fmgr = FactionManager.get(server);
        long now = System.currentTimeMillis();
        long regenMs = Config.OUTPOST_FLAG_REGEN_MINUTES.get() * 60_000L;

        for (OutpostEntry entry : new ArrayList<>(outposts.getAllOutposts())) {
            ServerLevel level = OutpostService.level(server, entry.dimension);
            if (level == null) continue;

            // A flag left alone for a while is repaired.
            if (entry.hp < entry.maxHp() && now - entry.lastHitAt >= regenMs) {
                entry.hp = entry.maxHp();
                entry.lastHitBy = null;
                outposts.setDirty();
            }

            if (entry.consolidateCoins()) outposts.setDirty(); // copper put in becomes silver and so on
            if (!entry.disintegrating && now >= entry.upkeepNextDue) {
                tickUpkeep(server, entry, fmgr, outposts, now);
            }
            if (entry.disintegrating) {
                tickDisintegration(server, entry, level, outposts, now);
            }
        }
    }

    /**
     * Upkeep comes out of the coins kept in the outpost. If there aren't enough, the outpost is unsupplied:
     * payment is retried every hour, and when the unsupplied time runs out it falls apart. (Outposts can be
     * captured at any time, supplied or not.)
     */
    private static void tickUpkeep(MinecraftServer server, OutpostEntry entry, FactionManager fmgr,
                                   OutpostData outposts, long now) {
        long upkeepCost = computeOutpostUpkeep(server, entry, fmgr);
        Faction owner = fmgr.getFaction(entry.ownerFactionId);
        if (entry.coinBalance() >= upkeepCost) {
            entry.setCoinBalance(entry.coinBalance() - upkeepCost);
            Treasury.record(server, Treasury.Source.OUTPOST_UPKEEP, upkeepCost,
                    owner != null ? Treasury.factionPayer(owner.getName()) : "", "Outpost upkeep");
            boolean wasUnsupplied = entry.isUnsupplied();
            entry.unsuppliedSince = 0;
            entry.upkeepNextDue = now + OutpostEntry.UPKEEP_INTERVAL_MS;
            outposts.setDirty();
            if (owner != null) {
                if (wasUnsupplied) {
                    OutpostService.notifyFaction(server, owner, "§a[Outpost] Upkeep paid. Your outpost is supplied and safe again.");
                } else if (entry.coinBalance() < upkeepCost) {
                    OutpostService.notifyFaction(server, owner, "§e[Outpost] Your outpost only has coins for today. Put more in at its flag.");
                }
            }
            return;
        }
        if (entry.unsuppliedSince == 0) {
            entry.unsuppliedSince = now;
            if (owner != null) {
                OutpostService.notifyFaction(server, owner, "§c[Outpost] Your outpost ran out of coins for upkeep ("
                        + Currency.format(upkeepCost) + "§c/day). §eIt's unsupplied: §cput coins in at its flag within §e"
                        + Config.OUTPOST_UNSUPPLIED_GRACE_HOURS.get() + "h§c or it falls apart.");
            }
        }
        if (now >= entry.unsuppliedUntil()) {
            entry.disintegrating = true;
            entry.disintegrateStartMs = now;
            if (owner != null) {
                OutpostService.notifyFaction(server, owner, "§c[Outpost] Upkeep was never paid. Your outpost has fallen apart;"
                        + " its items went to the faction item vault.");
            }
        } else {
            entry.upkeepNextDue = now + OutpostEntry.UPKEEP_RETRY_MS; // try again in an hour
        }
        outposts.setDirty();
    }

    /**
     * Outposts from older versions still have a cobblestone platform that crumbles block by block first.
     * Once nothing is left, the outpost is removed and its items handed on.
     */
    private static void tickDisintegration(MinecraftServer server, OutpostEntry entry, ServerLevel level,
                                           OutpostData outposts, long now) {
        if (entry.structureBlocks.isEmpty()) {
            OutpostService.dissolve(server, entry, "fell apart");
            return;
        }
        long msPerBlock = OutpostEntry.DISINTEGRATE_MS / Math.max(1, entry.structureBlocks.size() + 1);
        int shouldHaveRemoved = (int) ((now - entry.disintegrateStartMs) / msPerBlock);
        int alreadyRemoved = 25 - entry.structureBlocks.size(); // old platforms were 25 blocks
        if (shouldHaveRemoved > alreadyRemoved) {
            BlockPos bp = entry.structureBlocks.remove(RANDOM.nextInt(entry.structureBlocks.size()));
            level.removeBlock(bp, false);
            outposts.setDirty();
        }
    }

    /** Smoke while falling apart; flames above the flag while it's in danger (damaged, unsupplied or at war). */
    private static void tickParticles(MinecraftServer server) {
        for (OutpostEntry entry : OutpostData.get(server).getAllOutposts()) {
            ServerLevel level = OutpostService.level(server, entry.dimension);
            if (level == null || !level.isLoaded(entry.managerPos)) continue;
            double x = entry.managerPos.getX() + 0.5, y = entry.managerPos.getY() + 2.2, z = entry.managerPos.getZ() + 0.5;
            if (entry.disintegrating) {
                level.sendParticles(ParticleTypes.SMOKE, x, y - 1, z, 3, 0.3, 0.4, 0.3, 0.02);
            } else if (OutpostService.underThreat(server, entry)) {
                var particle = entry.hp < entry.maxHp() ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME;
                for (ServerPlayer sp : level.players()) {
                    if (sp.distanceToSqr(x, y, z) < 128 * 128) level.sendParticles(sp, particle, true, x, y, z, 4, 0.15, 0.2, 0.15, 0.01);
                }
            }
        }
    }

    /**
     * Daily outpost upkeep: the base price times the level, plus a ramp for distance from the faction table
     * ({@code base × level × (1 + floor(chunks / 5) × ramp)}).
     */
    public static long computeOutpostUpkeep(MinecraftServer server, OutpostEntry entry, FactionManager fmgr) {
        long base = (long) Config.OUTPOST_UPKEEP_COPPER.get() * entry.level;
        FactionManager.TableLocation tableLoc = fmgr.getFactionTable(entry.ownerFactionId);
        if (tableLoc == null || !tableLoc.dimension().equals(entry.dimension)) return base;
        int coreX = SectionPos.blockToSectionCoord(tableLoc.pos().getX());
        int coreZ = SectionPos.blockToSectionCoord(tableLoc.pos().getZ());
        int outpostCX = SectionPos.blockToSectionCoord(entry.managerPos.getX());
        int outpostCZ = SectionPos.blockToSectionCoord(entry.managerPos.getZ());
        int dist = Math.max(Math.abs(outpostCX - coreX), Math.abs(outpostCZ - coreZ));
        double ramp = EconomyManager.get(server).getOutpostDistanceRamp();
        return (long) Math.ceil(base * (1.0 + (dist / 5) * ramp));
    }

    /** Kept for callers that still ask: whether the outpost is in particular danger right now (see underThreat). */
    public static boolean isCapturable(MinecraftServer server, OutpostEntry entry) {
        return OutpostService.underThreat(server, entry);
    }

    static void notifyFaction(MinecraftServer server, Faction faction, Component msg) {
        OutpostService.notifyFaction(server, faction, msg.getString());
    }
}
