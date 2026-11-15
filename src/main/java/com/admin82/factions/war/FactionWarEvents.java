package com.admin82.factions.war;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import com.admin82.factions.barracks.BarracksData;
import com.admin82.factions.barracks.KitData;
import com.admin82.factions.blockentity.OutpostManagerBlockEntity;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.network.packet.OpenKitSelectionPacket;
import com.admin82.factions.network.packet.SyncFactionDataPacket;
import com.admin82.factions.network.packet.SyncWarStatePacket;
import com.admin82.factions.network.packet.WageWarPacket;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.util.BypassManager;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.ResourceWarAccess;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import javax.annotation.Nullable;

/**
 * Handles all live-war server-side logic:
 *   - Block break protection (claims + upkeep + war override)
 *   - Player death → deduct lives / eliminate
 *   - Player logout → eliminate attacker
 *   - Server tick → grace countdown, capture progress, boundary check, win conditions
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public class FactionWarEvents {

    /** Tick counter for war processing; runs every 20 ticks (1 s). */
    private static int warTickCounter = 0;

    // Block Protection

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player player = event.getPlayer();

        // Find which faction owns this chunk (if any)
        int cx = SectionPos.blockToSectionCoord(event.getPos().getX());
        int cz = SectionPos.blockToSectionCoord(event.getPos().getZ());
        String dim = level.dimension().location().toString();

        FactionManager fmgr = FactionManager.get(level.getServer());
        Faction claimingFaction = null;
        for (Faction f : fmgr.getAllFactions().values()) {
            if (f.hasClaim(cx, cz, dim)) { claimingFaction = f; break; }
        }
        if (claimingFaction == null) return; // unclaimed, allow break

        Faction playerFaction = fmgr.getFactionForPlayer(player.getUUID());
        UUID claimerId = claimingFaction.getId();

        // Members of the claiming faction can break blocks if their rank has the Build permission (same as placing).
        if (playerFaction != null && playerFaction.getId().equals(claimerId)) {
            FactionMember self = playerFaction.getMember(player.getUUID());
            if (self != null && playerFaction.getRolePermission(self.getRole(), FactionPermission.MEMBER_BUILD)) return;
            if (BypassManager.isBypassing(player.getUUID())) return;
            event.setCanceled(true);
            player.displayClientMessage(Component.literal("§cYour rank isn't allowed to build or break here."), true);
            return;
        }

        // Check ops (server admins bypass protection)
        if(BypassManager.isBypassing(player.getUUID())) return;

        EconomyManager eco = EconomyManager.get(level.getServer());

        // If the claiming faction is insolvent (vault dry) and past the grace period, land is unprotected
        if (!eco.isProtected(claimerId) && !claimingFaction.isInGracePeriod()) return;

        // During an active war, both sides can break each other's claimed territory
        WarManager warmgr = WarManager.get(level.getServer());
        ActiveWar war = warmgr.getWarForPlayer(player.getUUID());
        if (war != null && war.phase == WarPhase.ACTIVE) {
            if (war.isAttacker(player.getUUID()) && war.defenderFactionId.equals(claimerId))
                return;
            if (war.isDefender(player.getUUID()) && war.attackerFactionId.equals(claimerId))
                return;
        }

        // Resource-War block-break access: winner can break blocks up to the limit
        ResourceWarAccess rwa = warmgr.getResourceWarAccess(claimerId);
        if (rwa != null && !rwa.isExpired()) {
            Faction rwaPlayerFaction = fmgr.getFactionForPlayer(player.getUUID());
            if (rwaPlayerFaction != null && rwaPlayerFaction.getId().equals(rwa.winnerFactionId)) {
                if (rwa.canBreak()) {
                    rwa.blocksBroken++;
                    warmgr.setDirty();
                    // Push updated counter to this player's HUD
                    WarEndings.sendRwaPacketToPlayer((ServerPlayer) player, rwa);
                    int rem = rwa.remaining();
                    if (rem == 0) {
                        player.displayClientMessage(Component.literal(
                                "§c[Resource War] Block-break limit reached (" + rwa.blockLimit + ")."), false);
                    } else if (rem <= 5) {
                        player.displayClientMessage(Component.literal(
                                "§e[Resource War] §c" + rem + " §eblock breaks remaining."), true);
                    }
                    return; // allow the break
                } else {
                    // Limit already reached, deny and tell the player clearly
                    player.displayClientMessage(Component.literal(
                            "§c[Resource War] Block-break limit reached (" + rwa.blockLimit + "). No more breaks allowed."), true);
                    event.setCanceled(true);
                    return;
                }
            }
        }

        // Default: claim is protected
        event.setCanceled(true);
    }

    // Player Death

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.server;
        WarManager warmgr = WarManager.get(server);
        ActiveWar war = warmgr.getWarForPlayer(player.getUUID());
        if (war == null || war.phase != WarPhase.ACTIVE) return;

        UUID uid = player.getUUID();
        Map<UUID, Integer> livesMap = war.isAttacker(uid) ? war.attackerLives : war.defenderLives;
        int remaining = livesMap.getOrDefault(uid, 0);
        if (remaining <= 0) return;

        remaining--;
        livesMap.put(uid, remaining);
        warmgr.setDirty();

        FactionManager fmgr = FactionManager.get(server);
        if (remaining <= 0) {
            player.displayClientMessage(Component.literal("§c☠ You have been eliminated from the war!"), false);
            WarSpectators.makeSpectator(player);
            player.displayClientMessage(Component.literal("§7You are now spectating. You will be restored to survival when the war ends."), false);
            // Send "0 lives" packet to this player so HUD updates
            sendWarStateTo(server, war, fmgr, player);
        } else {
            player.displayClientMessage(
                    Component.literal("§c☠ You died! §e" + remaining + " life" + (remaining == 1 ? "" : "s") + " remaining."), false);
        }

        checkWinConditions(server, war, fmgr, warmgr);
        if (war.phase != WarPhase.ENDED) {
            WageWarPacket.broadcastWarState(server, war, fmgr);
        }
    }

    // Player Respawn → teleport to faction barracks

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.server;
        WarManager warmgr = WarManager.get(server);
        ActiveWar war = warmgr.getWarForPlayer(player.getUUID());
        if (war == null || war.phase != WarPhase.ACTIVE) return;

        // Only teleport if they still have lives
        UUID uid = player.getUUID();
        boolean isAtk = war.isAttacker(uid);
        int lives = isAtk ? war.attackerLives.getOrDefault(uid, 0)
                           : war.defenderLives.getOrDefault(uid, 0);
        if (lives <= 0) return; // eliminated, stays as spectator

        FactionManager fmgr = FactionManager.get(server);
        Faction faction = fmgr.getFactionForPlayer(uid);
        if (faction == null) return;

        // Check for a custom outpost war-spawn first
        OutpostData outpostData =
                OutpostData.get(server);
        BlockPos outpostSpawnPos = outpostData.getWarSpawnPos(uid);
        String   outpostSpawnDim = outpostData.getWarSpawnDim(uid);
        if (outpostSpawnPos != null && outpostSpawnDim != null) {
            ServerLevel outpostLevel = null;
            for (ServerLevel lvl : server.getAllLevels()) {
                if (lvl.dimension().location().toString().equals(outpostSpawnDim)) {
                    outpostLevel = lvl; break;
                }
            }
            // Verify the outpost block still exists and belongs to player's faction
            if (outpostLevel != null
                    && outpostLevel.getBlockEntity(outpostSpawnPos.below()) instanceof
                       OutpostManagerBlockEntity outpostBe
                    && faction.getId().equals(outpostBe.getLinkedFactionId())) {
                final ServerLevel fl = outpostLevel;
                player.teleportTo(fl,
                        outpostSpawnPos.getX() + 0.5,
                        outpostSpawnPos.getY(),
                        outpostSpawnPos.getZ() + 0.5,
                        player.getYRot(), player.getXRot());
                player.displayClientMessage(
                        Component.literal("§7You respawned at your §e§lOutpost§7."), false);
                BarracksData bData2 = BarracksData.get(server);
                List<KitData> kits2 = new ArrayList<>(bData2.getKits(uid));
                if (!kits2.isEmpty()) {
                    PacketDistributor.sendToPlayer(player, OpenKitSelectionPacket.fromKits(kits2));
                }
                return;
            } else {
                // Outpost gone, clear the custom spawn
                outpostData.clearWarSpawn(uid);
                player.displayClientMessage(
                        Component.literal("§c[Outpost] Your war spawn outpost is gone! Falling back to barracks."), false);
            }
        }

        FactionManager.TableLocation barrLoc = fmgr.getFactionBarracks(faction.getId());
        if (barrLoc == null) return;

        // Find the target level
        ServerLevel targetLevel = null;
        for (ServerLevel lvl : server.getAllLevels()) {
            if (lvl.dimension().location().toString().equals(barrLoc.dimension())) {
                targetLevel = lvl; break;
            }
        }
        if (targetLevel == null) return;

        BlockPos barrPos = barrLoc.pos();
        // Spawn a few blocks in front of the barracks door
        int spawnX = barrPos.getX();
        int spawnZ = barrPos.getZ() + 2;
        targetLevel.getChunk(spawnX >> 4, spawnZ >> 4); // load it first, or the height reads as the bottom of the world
        int spawnY = targetLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnX, spawnZ);

        final ServerLevel finalLevel = targetLevel;
        player.teleportTo(finalLevel, spawnX + 0.5, spawnY, spawnZ + 0.5, player.getYRot(), player.getXRot());
        player.displayClientMessage(
                Component.literal("§7You respawned at the §e" + faction.getName() + " §7Barracks."), false);

        // Send kit selection screen if faction has kits available
        BarracksData bData = BarracksData.get(server);
        List<KitData> kits = new ArrayList<>(bData.getKits(player.getUUID()));
        if (!kits.isEmpty()) {
            PacketDistributor.sendToPlayer(player, OpenKitSelectionPacket.fromKits(kits));
        }
    }

    // Player Logout

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = player.server;
        WarManager warmgr = WarManager.get(server);
        ActiveWar war = warmgr.getWarForPlayer(player.getUUID());
        if (war == null || war.phase != WarPhase.ACTIVE) return;

        // Attackers who log out lose all their lives
        if (war.isAttacker(player.getUUID())) {
            war.attackerLives.put(player.getUUID(), 0);
            warmgr.setDirty();
            FactionManager fmgr = FactionManager.get(server);
            checkWinConditions(server, war, fmgr, warmgr);
            if (war.phase != WarPhase.ENDED) {
                WageWarPacket.broadcastWarState(server, war, fmgr);
            }
        }
    }

    // Server Tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++warTickCounter < 20) return;
        warTickCounter = 0;

        MinecraftServer server = event.getServer();
        WarManager      warmgr = WarManager.get(server);
        FactionManager  fmgr   = FactionManager.get(server);

        // Tick demand expiry
        WarNegotiationsManager.get(server).tickExpiry();

        // Expire resource-war accesses and clear client highlights when done
        warmgr.removeExpiredResourceAccesses();

        // Sync RWA state to winner faction members every second (drives the HUD timer)
        for (ResourceWarAccess rwa : warmgr.getAllResourceWarAccesses()) {
            if (rwa.isExpired()) continue;
            WarEndings.broadcastRwaToWinner(server, rwa, fmgr);
        }

        if (warmgr.getActiveWars().isEmpty()) return;

        long now = System.currentTimeMillis();
        List<UUID> toEnd = new ArrayList<>();

        for (ActiveWar war : warmgr.getActiveWars()) {
            if (war.phase == WarPhase.ENDED) { toEnd.add(war.warId); continue; }
            if (war.outpostPhase) { war.outpostPhase = false; warmgr.setDirty(); } // wars saved by older versions

            // Grace period countdown
            if (war.phase == WarPhase.GRACE && now >= war.graceEndsAt) {
                war.phase = WarPhase.ACTIVE;
                war.lastTickMs = now;
                // Attackers who logged off during the grace period are out; otherwise they'd hold lives forever.
                war.attackerLives.replaceAll((uuid, lives) -> server.getPlayerList().getPlayer(uuid) == null ? 0 : lives);

                // Outposts aren't a capture point in wars any more: attackers capture them by breaking
                // their flag. The war goes straight to the faction table.
                warmgr.setDirty();
                if (war.outpostPhase) {
                    notifyParticipants(server, war, Component.literal(
                            "§c[War] §eGrace period over! §c⛑ Destroy the defender's outpost first!"));
                } else {
                    notifyParticipants(server, war,
                            Component.literal("§c[War] §eGrace period over! §cThe war is now ACTIVE!"));
                }
                // Send defenders a clickable return-to-barracks hint
                Component returnLink =
                        Component.literal("§c[War] §7→ ")
                                .append(Component.literal("§a§n[Click here to return to your Barracks]§r")
                                        .withStyle(s -> s
                                                .withClickEvent(new ClickEvent(
                                                        ClickEvent.Action.RUN_COMMAND,
                                                        "/factionreturn"))
                                                .withHoverEvent(new HoverEvent(
                                                        HoverEvent.Action.SHOW_TEXT,
                                                        Component.literal("§7Teleport to your faction barracks")))));
                for (UUID uid : war.defenderLives.keySet()) {
                    ServerPlayer defSp = server.getPlayerList().getPlayer(uid);
                    if (defSp != null) defSp.displayClientMessage(returnLink, false);
                }
                if (warmgr.isWarTpEnabled()) {
                    WarTeleports.teleportAttackers(server, war, fmgr);
                }
            }

            if (war.phase == WarPhase.ACTIVE) {
                float deltaSeconds = (now - war.lastTickMs) / 1000f;
                war.lastTickMs = now;

                // Boundary check
                if (warmgr.isWarBoundaryEnabled()) {
                    int boundaryR = Config.WAR_BOUNDARY_RADIUS_BLOCKS.get();
                    // Teleport-detection threshold: >20 blocks past boundary in one tick = tp
                    int teleportThreshold = 20;
                    String defDim = war.defenderDimension;
                    BlockPos tablePos = war.defenderTablePos;

                    for (UUID uid : new ArrayList<>(war.attackerLives.keySet())) {
                        if (war.attackerLives.getOrDefault(uid, 0) <= 0) continue;
                        ServerPlayer sp = server.getPlayerList().getPlayer(uid);
                        if (sp == null) continue;
                        String playerDim = sp.level().dimension().location().toString();

                        if (!playerDim.equals(defDim)) {
                            // Different dimension, warn, don't eliminate (they can return)
                            sp.displayClientMessage(Component.literal(
                                    "§c⚠ War boundary: You must return to the war dimension!"), true);
                            continue;
                        }

                        double dx = sp.getX() - (tablePos.getX() + 0.5);
                        double dz = sp.getZ() - (tablePos.getZ() + 0.5);
                        double dist = Math.sqrt(dx * dx + dz * dz);

                        if (dist > boundaryR + teleportThreshold) {
                            // Far outside boundary, likely teleported → eliminate
                            eliminateAttacker(server, war, warmgr, fmgr, sp, uid,
                                    "§c☠ You teleported outside the war boundary and were eliminated!");
                        } else if (dist > boundaryR) {
                            // Just slightly outside, push them back to the boundary edge
                            double scale = (boundaryR - 1.0) / dist;
                            double safeX = tablePos.getX() + 0.5 + dx * scale;
                            double safeZ = tablePos.getZ() + 0.5 + dz * scale;
                            ServerLevel warLevel = null;
                            for (ServerLevel lvl : server.getAllLevels()) {
                                if (lvl.dimension().location().toString().equals(defDim)) {
                                    warLevel = lvl; break;
                                }
                            }
                            if (warLevel != null) {
                                warLevel.getChunk((int) safeX >> 4, (int) safeZ >> 4);
                                int safeY = warLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                        (int) safeX, (int) safeZ);
                                sp.teleportTo(warLevel, safeX, safeY, safeZ, sp.getYRot(), sp.getXRot());
                            }
                            sp.displayClientMessage(Component.literal("§c⚠ WAR BOUNDARY! You have been pushed back."), true);
                        } else if (dist > boundaryR - 20) {
                            // Warning zone, approaching boundary
                            sp.displayClientMessage(Component.literal(
                                    "§e⚠ Approaching war boundary! (" + (int)(boundaryR - dist) + " blocks)"), true);
                        }
                    }
                }

                // Capture point
                // Dynamic: point to outpost during outpost phase, then faction table
                BlockPos captureTarget;
                String   captureDim;
                int captureR;
                float captureTimeSec;
                if (war.outpostPhase && war.outpostPos != null) {
                    captureTarget  = war.outpostPos;
                    captureDim     = war.outpostDim;
                    captureR       = OutpostEntry.captureRadius();
                    captureTimeSec = warmgr.getOutpostKothTime();
                } else {
                    captureTarget  = war.defenderTablePos;
                    captureDim     = war.defenderDimension;
                    captureR       = Config.WAR_CAPTURE_RADIUS_BLOCKS.get();
                    captureTimeSec = warmgr.getTableKothTime();
                }
                // (legacy variables kept for boundary check above)
                String defDim = war.defenderDimension;
                BlockPos tablePos = war.defenderTablePos;

                boolean attackersOnPoint = false;
                boolean defendersOnPoint = false;

                for (UUID uid : war.attackerLives.keySet()) {
                    if (war.attackerLives.getOrDefault(uid, 0) <= 0) continue;
                    ServerPlayer sp = server.getPlayerList().getPlayer(uid);
                    if (sp == null || !sp.level().dimension().location().toString().equals(captureDim)) continue;
                    double dx = sp.getX() - (captureTarget.getX() + 0.5);
                    double dz = sp.getZ() - (captureTarget.getZ() + 0.5);
                    if (Math.sqrt(dx * dx + dz * dz) <= captureR) { attackersOnPoint = true; break; }
                }

                for (UUID uid : war.defenderLives.keySet()) {
                    if (war.defenderLives.getOrDefault(uid, 0) <= 0) continue;
                    ServerPlayer sp = server.getPlayerList().getPlayer(uid);
                    if (sp == null || !sp.level().dimension().location().toString().equals(captureDim)) continue;
                    double dx = sp.getX() - (captureTarget.getX() + 0.5);
                    double dz = sp.getZ() - (captureTarget.getZ() + 0.5);
                    if (Math.sqrt(dx * dx + dz * dz) <= captureR) { defendersOnPoint = true; break; }
                }

                if (attackersOnPoint && !defendersOnPoint) {
                    war.captureProgress = Math.min(captureTimeSec, war.captureProgress + deltaSeconds);
                } else if (defendersOnPoint && !attackersOnPoint) {
                    war.captureProgress = Math.max(0f, war.captureProgress - deltaSeconds);
                }
                // Both present → contested → no change
                warmgr.setDirty();

                // Defender counter-attack: defenders can capture ATTACKER's table
                // Blocked if attacker has an active (non-disintegrating) outpost.
                if (!war.outpostPhase && war.attackerTablePos != null) {
                    OutpostData outpostData2 =
                            OutpostData.get(server);
                    boolean atkHasLivingOutpost = outpostData2
                            .getOutpostsForFaction(war.attackerFactionId)
                            .stream().anyMatch(o -> !o.disintegrating);

                    if (!atkHasLivingOutpost) {
                        String atkDim2  = war.attackerDimension;
                        BlockPos atkTbl = war.attackerTablePos;
                        int atkCaptureR = Config.WAR_CAPTURE_RADIUS_BLOCKS.get();
                        float atkCapTime = warmgr.getTableKothTime();

                        boolean defendersAtAtkBase  = false;
                        boolean attackersAtAtkBase  = false;

                        for (UUID uid : war.defenderLives.keySet()) {
                            if (war.defenderLives.getOrDefault(uid, 0) <= 0) continue;
                            ServerPlayer sp2 = server.getPlayerList().getPlayer(uid);
                            if (sp2 == null || !sp2.level().dimension().location().toString().equals(atkDim2)) continue;
                            double ddx = sp2.getX() - (atkTbl.getX() + 0.5);
                            double ddz = sp2.getZ() - (atkTbl.getZ() + 0.5);
                            if (Math.sqrt(ddx * ddx + ddz * ddz) <= atkCaptureR) { defendersAtAtkBase = true; break; }
                        }
                        for (UUID uid : war.attackerLives.keySet()) {
                            if (war.attackerLives.getOrDefault(uid, 0) <= 0) continue;
                            ServerPlayer sp2 = server.getPlayerList().getPlayer(uid);
                            if (sp2 == null || !sp2.level().dimension().location().toString().equals(atkDim2)) continue;
                            double ddx = sp2.getX() - (atkTbl.getX() + 0.5);
                            double ddz = sp2.getZ() - (atkTbl.getZ() + 0.5);
                            if (Math.sqrt(ddx * ddx + ddz * ddz) <= atkCaptureR) { attackersAtAtkBase = true; break; }
                        }

                        if (defendersAtAtkBase && !attackersAtAtkBase) {
                            war.defenderCaptureProgress = Math.min(atkCapTime, war.defenderCaptureProgress + deltaSeconds);
                        } else if (attackersAtAtkBase && !defendersAtAtkBase) {
                            war.defenderCaptureProgress = Math.max(0f, war.defenderCaptureProgress - deltaSeconds);
                        }

                        // KOTH particles around attacker's table when defenders are counter-capturing
                        if (war.defenderCaptureProgress > 0) {
                            ServerLevel atkLevel = null;
                            for (ServerLevel lvl : server.getAllLevels()) {
                                if (lvl.dimension().location().toString().equals(atkDim2)) { atkLevel = lvl; break; }
                            }
                            if (atkLevel != null) {
                                KothParticles.spawnKothParticles(atkLevel, atkTbl, !defendersAtAtkBase, defendersAtAtkBase);
                            }
                        }
                    }
                }

                // Outpost transition: if outpost KOTH is won, switch to main war
                if (war.outpostPhase && war.captureProgress >= captureTimeSec) {
                    OutpostData outpostData =
                            OutpostData.get(server);
                    if (war.outpostId != null) {
                        OutpostEntry outpost = outpostData.getOutpost(war.outpostId);
                        if (outpost != null) {
                            outpost.disintegrating       = true;
                            outpost.disintegrateStartMs  = System.currentTimeMillis();
                            outpostData.setDirty();
                        }
                        // 1-hour placement cooldown for the defending faction
                        outpostData.setOutpostCooldown(war.defenderFactionId,
                                System.currentTimeMillis() + 3_600_000L);
                    }
                    war.outpostPhase   = false;
                    war.captureProgress = 0f;
                    warmgr.setDirty();
                    notifyParticipants(server, war, Component.literal(
                            "§c[War] §e⛑ Outpost destroyed! §cMain war begins — §ecapture the faction base!"));
                    WageWarPacket.broadcastWarState(server, war, fmgr);
                    continue; // skip win conditions this tick so HUD updates cleanly
                }

                // KOTH capture-zone particle effects
                KothParticles.spawnKothParticles(server, war, attackersOnPoint, defendersOnPoint);

                // Spectator leash: keep dead players within 10 blocks of nearest living teammate
                WarSpectators.enforceSpectatorLeash(server, war.attackerLives);
                WarSpectators.enforceSpectatorLeash(server, war.defenderLives);

                // Win conditions
                checkWinConditions(server, war, fmgr, warmgr);
            }

            if (war.phase != WarPhase.ENDED) {
                WageWarPacket.broadcastWarState(server, war, fmgr);
            } else {
                toEnd.add(war.warId);
            }
        }

        // Clean up ended wars
        for (UUID wid : toEnd) {
            ActiveWar w = warmgr.getWar(wid);
            if (w != null) {
                WarSpectators.restoreWarSpectators(server, w);
                fmgr.endWar(w.attackerFactionId, w.defenderFactionId);
            }
            warmgr.endWar(wid);
            // Open peace offers and demands die with the war.
            WarNegotiationsManager.get(server).clearWarDemands(wid);
        }
    }

    // Helpers

    private static void eliminateAttacker(MinecraftServer server, ActiveWar war, WarManager warmgr,
                                          FactionManager fmgr, ServerPlayer sp, UUID uid, String msg) {
        war.attackerLives.put(uid, 0);
        warmgr.setDirty();
        sp.displayClientMessage(Component.literal(msg), false);
        WarSpectators.makeSpectator(sp);
        sp.displayClientMessage(Component.literal("§7You are now spectating. You will be restored to survival when the war ends."), false);
        checkWinConditions(server, war, fmgr, warmgr);
    }

    private static boolean allDefendersEliminated(ActiveWar war) {
        return !war.defenderLives.isEmpty()
                && war.defenderLives.values().stream().allMatch(l -> l <= 0);
    }

    private static void checkWinConditions(MinecraftServer server, ActiveWar war,
                                           FactionManager fmgr, WarManager warmgr) {
        if (war.phase != WarPhase.ACTIVE) return;

        float captureTimeSec = warmgr.getTableKothTime();
        Faction af = fmgr.getAllFactions().get(war.attackerFactionId);
        Faction df = fmgr.getAllFactions().get(war.defenderFactionId);
        String atkName = af != null ? af.getName() : "Attackers";
        String defName = df != null ? df.getName() : "Defenders";

        // During the outpost phase the capture counter belongs to the outpost, not the table.
        boolean captureWin = !war.outpostPhase && war.captureProgress >= captureTimeSec;
        boolean atkElim    = war.allAttackersEliminated();
        boolean defElim    = allDefendersEliminated(war);
        // Defenders win by capturing the attacker's faction table
        boolean defCaptureWin = !war.outpostPhase && war.attackerTablePos != null
                && war.defenderCaptureProgress >= captureTimeSec;

        if (!captureWin && !atkElim && !defElim && !defCaptureWin) return;

        // Determine winner, attackers win by capturing defender's base or wiping defenders;
        // defenders win by capturing attacker's base or wiping attackers.
        boolean attackersWon = captureWin || defElim;

        war.phase = WarPhase.ENDED;
        warmgr.setDirty();
        // Record post-war cooldown so the attacker can't immediately re-declare on the same defender
        warmgr.recordWarCooldown(war.attackerFactionId, war.defenderFactionId);
        broadcastWarEnd(server, war, fmgr, attackersWon);
        // The winner earns warbonds for faction upgrades.
        Faction victor = attackersWon ? af : df;
        int reward = Config.WARBOND_WAR_REWARD.get();
        if (victor != null && reward > 0) {
            UpgradeService.award(server, victor, reward);
            notifyFaction(server, victor, Component.literal("§d[War] Your faction earned " + reward
                    + " warbonds (in the item vault). Spend them in Faction Upgrades."));
        }

        if (attackersWon) {
            String how = captureWin ? "captured " + defName + "'s base!" : "eliminated all defenders!";
            if (af != null) notifyFaction(server, af, Component.literal("§6[War] §a§lVICTORY! §eYou " + how));
            if (df != null) notifyFaction(server, df, Component.literal("§6[War] §c§lDEFEAT! §e" + atkName + " " + how));
            if (af != null) WarEndings.resolveWarOutcome(server, af, df, war, fmgr);

        } else if (defCaptureWin) {
            // Defenders captured the attacker's faction table
            if (df != null) notifyFaction(server, df, Component.literal("§6[War] §a§lVICTORY! §eYou captured " + atkName + "'s base!"));
            if (af != null) notifyFaction(server, af, Component.literal("§6[War] §c§lDEFEAT! §e" + defName + " captured your base!"));
            // Defenders winning by capture: open conquest GUI so defenders choose the spoils
            if (df != null) WarEndings.resolveWarOutcome(server, df, af, war, fmgr);

        } else {
            // Defenders won, all attackers were eliminated
            if (df != null) notifyFaction(server, df, Component.literal("§6[War] §a§lVICTORY! §eAll attackers have been eliminated!"));
            if (af != null) notifyFaction(server, af, Component.literal("§6[War] §c§lDEFEAT! §eAll your fighters were eliminated by " + defName + "."));

            if (df != null) {
                switch (war.warType) {
                    case TERRITORY -> {
                        notifyFaction(server, df, Component.literal("§6[Territory War] §aYou successfully defended your territory!"));
                        if (af != null) notifyFaction(server, af, Component.literal("§6[Territory War] §cYou failed to capture the target chunks."));
                    }
                    case ALL_OUT -> WarEndings.resolveWarOutcome(server, df, af, war, fmgr);
                    case FIGHT   -> {
                        notifyFaction(server, df, Component.literal("§6[Faction Fight] §aVictory — honour to the defenders!"));
                    }
                    default      -> WarEndings.openConquestGui(server, df, af, fmgr);
                }
            }
        }
    }

    /** Broadcasts the ENDED phase to all committed players. */
    /**
     * Ends a war because both sides agreed to terms (negotiated peace).
     * Clears the HUD, removes the war from both factions straight away, and lets the
     * next war tick restore spectators and drop the ActiveWar.
     */
    public static void endWarByAgreement(MinecraftServer server, @Nullable ActiveWar war,
                                         UUID factionA, UUID factionB) {
        FactionManager fmgr = FactionManager.get(server);
        WarManager warmgr = WarManager.get(server);
        if (war != null && war.phase != WarPhase.ENDED) {
            war.phase = WarPhase.ENDED;
            warmgr.recordWarCooldown(war.attackerFactionId, war.defenderFactionId);
            warmgr.setDirty();
            broadcastWarEnd(server, war, fmgr, false);
        }
        fmgr.endWar(factionA, factionB);
        for (UUID id : List.of(factionA, factionB)) {
            Faction f = fmgr.getFaction(id);
            if (f == null) continue;
            for (FactionMember m : f.getMembers()) {
                ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
                if (sp != null) PacketDistributor.sendToPlayer(sp,
                        new SyncFactionDataPacket(f));
            }
        }
    }

    private static void broadcastWarEnd(MinecraftServer server, ActiveWar war, FactionManager fmgr, boolean attackersWon) {
        Faction af = fmgr.getAllFactions().get(war.attackerFactionId);
        Faction df = fmgr.getAllFactions().get(war.defenderFactionId);
        String atkName = af != null ? af.getName() : "Unknown";
        String defName = df != null ? df.getName() : "Unknown";
        int captureTimeSec = WarManager.get(server).getTableKothTime();

        sendWarEndPkt(server, war.attackerLives.keySet(), war, atkName, defName, captureTimeSec, true);
        sendWarEndPkt(server, war.defenderLives.keySet(), war, atkName, defName, captureTimeSec, false);
    }

    private static void sendWarEndPkt(MinecraftServer server, Set<UUID> uids, ActiveWar war,
                                      String atkName, String defName, int captureTimeSec, boolean isAtk) {
        int tx = war.defenderTablePos.getX(), tz = war.defenderTablePos.getZ();
        String tdim = war.defenderDimension;
        int capTime = WarManager.get(server).getTableKothTime();
        for (UUID uid : uids) {
            ServerPlayer sp = server.getPlayerList().getPlayer(uid);
            if (sp == null) continue;
            int myLives = isAtk ? war.attackerLives.getOrDefault(uid, 0) : war.defenderLives.getOrDefault(uid, 0);
            PacketDistributor.sendToPlayer(sp, new SyncWarStatePacket(
                    war.warId, war.phase.ordinal(), 0,
                    war.captureProgress, capTime, myLives, isAtk,
                    atkName, defName, war.totalAttackerLives(), war.totalDefenderLives(),
                    tx, tz, tdim,
                    false, tx, tz, tdim,  // outpost phase cleared at end
                    war.defenderCaptureProgress
            ));
        }
    }

    private static void sendWarStateTo(MinecraftServer server, ActiveWar war, FactionManager fmgr, ServerPlayer sp) {
        Faction af = fmgr.getAllFactions().get(war.attackerFactionId);
        Faction df = fmgr.getAllFactions().get(war.defenderFactionId);
        String atkName = af != null ? af.getName() : "Unknown";
        String defName = df != null ? df.getName() : "Unknown";
        UUID uid = sp.getUUID();
        boolean isAtk = war.isAttacker(uid);
        int myLives = isAtk ? war.attackerLives.getOrDefault(uid, 0) : war.defenderLives.getOrDefault(uid, 0);
        int graceSec = (int) Math.max(0, (war.graceEndsAt - System.currentTimeMillis()) / 1000L);
        int tx = war.defenderTablePos.getX(), tz = war.defenderTablePos.getZ();
        boolean outpostPhase = war.outpostPhase && war.outpostPos != null;
        int capX = outpostPhase ? war.outpostPos.getX() : tx;
        int capZ = outpostPhase ? war.outpostPos.getZ() : tz;
        String capDim = outpostPhase ? war.outpostDim : war.defenderDimension;
        int capTimeSec = outpostPhase
                ? WarManager.get(server).getOutpostKothTime()
                : WarManager.get(server).getTableKothTime();
        PacketDistributor.sendToPlayer(sp, new SyncWarStatePacket(
                war.warId, war.phase.ordinal(), graceSec,
                war.captureProgress, capTimeSec,
                myLives, isAtk, atkName, defName,
                war.totalAttackerLives(), war.totalDefenderLives(),
                tx, tz, war.defenderDimension,
                outpostPhase, capX, capZ, capDim,
                war.defenderCaptureProgress
        ));
    }

    private static void notifyParticipants(MinecraftServer server, ActiveWar war, Component msg) {
        Set<UUID> all = new HashSet<>(war.attackerLives.keySet());
        all.addAll(war.defenderLives.keySet());
        for (UUID uid : all) {
            ServerPlayer sp = server.getPlayerList().getPlayer(uid);
            if (sp != null) sp.displayClientMessage(msg, false);
        }
    }

    static void notifyFaction(MinecraftServer server, Faction faction, Component msg) {
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) sp.displayClientMessage(msg, false);
        }
    }

    // Restore spectators on login (in case war ended while offline)

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        WarEndings.resendConquest(player.server, player);
        if (!WarSpectators.isWarSpectator(player)) return;
        // Restore if the war that eliminated them is over, or they're in a new war where they still have lives.
        ActiveWar war = WarManager.get(player.server).getWarForPlayer(player.getUUID());
        int lives = war == null ? 0
                : war.attackerLives.getOrDefault(player.getUUID(), war.defenderLives.getOrDefault(player.getUUID(), 0));
        if (war == null || lives > 0) {
            WarSpectators.restore(player);
            player.displayClientMessage(Component.literal("§aYour war has ended, so you're back in survival."), false);
        }
    }

}

