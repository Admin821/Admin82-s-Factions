package com.admin82.factions;

import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.faction.*;
import com.admin82.factions.barracks.BarracksData;
import com.admin82.factions.barracks.KitData;
import com.admin82.factions.network.packet.OpenConquestGuiPacket;
import com.admin82.factions.network.packet.OpenKitSelectionPacket;
import com.admin82.factions.network.packet.SyncContainerHighlightsPacket;
import com.admin82.factions.network.packet.SyncWarStatePacket;
import com.admin82.factions.network.packet.WageWarPacket;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.ResourceWarAccess;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarPhase;
import com.admin82.factions.war.WarType;
import com.admin82.factions.war.VassalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

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

    // ── Block Protection ──────────────────────────────────────────────────────

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
        if (claimingFaction == null) return; // unclaimed — allow break

        Faction playerFaction = fmgr.getFactionForPlayer(player.getUUID());
        UUID claimerId = claimingFaction.getId();

        // Members of the claiming faction can always break their own blocks
        if (playerFaction != null && playerFaction.getId().equals(claimerId)) return;

        // Check ops (server admins bypass protection)
        if (player.hasPermissions(2)) return;

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
                    sendRwaPacketToPlayer((ServerPlayer) player, rwa);
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
                    // Limit already reached — deny and tell the player clearly
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

    // ── Player Death ──────────────────────────────────────────────────────────

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
            player.setGameMode(GameType.SPECTATOR);
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

    // ── Player Respawn → teleport to faction barracks ─────────────────────────

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
        if (lives <= 0) return; // eliminated — stays as spectator

        FactionManager fmgr = FactionManager.get(server);
        Faction faction = fmgr.getFactionForPlayer(uid);
        if (faction == null) return;

        // ── Check for a custom outpost war-spawn first ─────────────────────────
        com.admin82.factions.outpost.OutpostData outpostData =
                com.admin82.factions.outpost.OutpostData.get(server);
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
                       com.admin82.factions.blockentity.OutpostManagerBlockEntity outpostBe
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
                List<KitData> kits2 = new java.util.ArrayList<>(bData2.getKits(uid));
                if (!kits2.isEmpty()) {
                    PacketDistributor.sendToPlayer(player, OpenKitSelectionPacket.fromKits(kits2));
                }
                return;
            } else {
                // Outpost gone — clear the custom spawn
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
        int spawnY = targetLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnX, spawnZ);

        final ServerLevel finalLevel = targetLevel;
        player.teleportTo(finalLevel, spawnX + 0.5, spawnY, spawnZ + 0.5, player.getYRot(), player.getXRot());
        player.displayClientMessage(
                Component.literal("§7You respawned at the §e" + faction.getName() + " §7Barracks."), false);

        // Send kit selection screen if faction has kits available
        BarracksData bData = BarracksData.get(server);
        List<KitData> kits = new java.util.ArrayList<>(bData.getKits(player.getUUID()));
        if (!kits.isEmpty()) {
            PacketDistributor.sendToPlayer(player, OpenKitSelectionPacket.fromKits(kits));
        }
    }

    // ── Player Logout ─────────────────────────────────────────────────────────

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

    // ── Server Tick ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++warTickCounter < 20) return;
        warTickCounter = 0;

        MinecraftServer server = event.getServer();
        WarManager      warmgr = WarManager.get(server);
        FactionManager  fmgr   = FactionManager.get(server);

        // Tick demand expiry
        com.admin82.factions.war.WarNegotiationsManager.get(server).tickExpiry();

        // Expire resource-war accesses and clear client highlights when done
        warmgr.removeExpiredResourceAccesses();

        // Sync RWA state to winner faction members every second (drives the HUD timer)
        for (ResourceWarAccess rwa : warmgr.getAllResourceWarAccesses()) {
            if (rwa.isExpired()) continue;
            broadcastRwaToWinner(server, rwa, fmgr);
        }

        if (warmgr.getActiveWars().isEmpty()) return;

        long now = System.currentTimeMillis();
        List<UUID> toEnd = new ArrayList<>();

        for (ActiveWar war : warmgr.getActiveWars()) {
            if (war.phase == WarPhase.ENDED) { toEnd.add(war.warId); continue; }

            // ── Grace period countdown ─────────────────────────────────────
            if (war.phase == WarPhase.GRACE && now >= war.graceEndsAt) {
                war.phase = WarPhase.ACTIVE;
                war.lastTickMs = now;

                // ── Outpost phase: if the defender has an outpost, it must be
                //    destroyed before the faction table can be captured. ──────
                com.admin82.factions.outpost.OutpostData outpostData =
                        com.admin82.factions.outpost.OutpostData.get(server);
                java.util.List<com.admin82.factions.outpost.OutpostEntry> defenderOutposts =
                        outpostData.getOutpostsForFaction(war.defenderFactionId);
                if (!defenderOutposts.isEmpty()) {
                    com.admin82.factions.outpost.OutpostEntry outpost = defenderOutposts.get(0);
                    war.outpostPhase = true;
                    war.outpostPos   = outpost.managerPos;
                    war.outpostDim   = outpost.dimension;
                    war.outpostId    = outpost.id;
                }

                warmgr.setDirty();
                if (war.outpostPhase) {
                    notifyParticipants(server, war, Component.literal(
                            "§c[War] §eGrace period over! §c⛑ Destroy the defender's outpost first!"));
                } else {
                    notifyParticipants(server, war,
                            Component.literal("§c[War] §eGrace period over! §cThe war is now ACTIVE!"));
                }
                // Send defenders a clickable return-to-barracks hint
                net.minecraft.network.chat.Component returnLink =
                        Component.literal("§c[War] §7→ ")
                                .append(Component.literal("§a§n[Click here to return to your Barracks]§r")
                                        .withStyle(s -> s
                                                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                                        "/factionreturn"))
                                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                                        Component.literal("§7Teleport to your faction barracks")))));
                for (UUID uid : war.defenderLives.keySet()) {
                    ServerPlayer defSp = server.getPlayerList().getPlayer(uid);
                    if (defSp != null) defSp.displayClientMessage(returnLink, false);
                }
                if (warmgr.isWarTpEnabled()) {
                    teleportAttackers(server, war, fmgr);
                }
            }

            if (war.phase == WarPhase.ACTIVE) {
                float deltaSeconds = (now - war.lastTickMs) / 1000f;
                war.lastTickMs = now;

                // ── Boundary check ─────────────────────────────────────────
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
                            // Different dimension — warn, don't eliminate (they can return)
                            sp.displayClientMessage(Component.literal(
                                    "§c⚠ War boundary: You must return to the war dimension!"), true);
                            continue;
                        }

                        double dx = sp.getX() - (tablePos.getX() + 0.5);
                        double dz = sp.getZ() - (tablePos.getZ() + 0.5);
                        double dist = Math.sqrt(dx * dx + dz * dz);

                        if (dist > boundaryR + teleportThreshold) {
                            // Far outside boundary — likely teleported → eliminate
                            eliminateAttacker(server, war, warmgr, fmgr, sp, uid,
                                    "§c☠ You teleported outside the war boundary and were eliminated!");
                        } else if (dist > boundaryR) {
                            // Just slightly outside — push them back to the boundary edge
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
                                int safeY = warLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                                        (int) safeX, (int) safeZ);
                                sp.teleportTo(warLevel, safeX, safeY, safeZ, sp.getYRot(), sp.getXRot());
                            }
                            sp.displayClientMessage(Component.literal("§c⚠ WAR BOUNDARY! You have been pushed back."), true);
                        } else if (dist > boundaryR - 20) {
                            // Warning zone — approaching boundary
                            sp.displayClientMessage(Component.literal(
                                    "§e⚠ Approaching war boundary! (" + (int)(boundaryR - dist) + " blocks)"), true);
                        }
                    }
                }

                // ── Capture point ──────────────────────────────────────────
                // Dynamic: point to outpost during outpost phase, then faction table
                BlockPos captureTarget;
                String   captureDim;
                int captureR;
                float captureTimeSec;
                if (war.outpostPhase && war.outpostPos != null) {
                    captureTarget  = war.outpostPos;
                    captureDim     = war.outpostDim;
                    captureR       = com.admin82.factions.outpost.OutpostEntry.CAPTURE_RADIUS_BLOCKS;
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

                // ── Defender counter-attack: defenders can capture ATTACKER's table ─────
                // Blocked if attacker has an active (non-disintegrating) outpost.
                if (!war.outpostPhase && war.attackerTablePos != null) {
                    com.admin82.factions.outpost.OutpostData outpostData2 =
                            com.admin82.factions.outpost.OutpostData.get(server);
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
                                spawnKothParticles(atkLevel, atkTbl, !defendersAtAtkBase, defendersAtAtkBase);
                            }
                        }
                    }
                }

                // ── Outpost transition: if outpost KOTH is won, switch to main war ──────────
                if (war.outpostPhase && war.captureProgress >= captureTimeSec) {
                    com.admin82.factions.outpost.OutpostData outpostData =
                            com.admin82.factions.outpost.OutpostData.get(server);
                    if (war.outpostId != null) {
                        com.admin82.factions.outpost.OutpostEntry outpost = outpostData.getOutpost(war.outpostId);
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

                // ── KOTH capture-zone particle effects ─────────────────────
                spawnKothParticles(server, war, attackersOnPoint, defendersOnPoint);

                // ── Spectator leash: keep dead players within 10 blocks of nearest living teammate ──
                enforceSpectatorLeash(server, war.attackerLives);
                enforceSpectatorLeash(server, war.defenderLives);

                // ── Win conditions ─────────────────────────────────────────
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
                restoreWarSpectators(server, w);
                fmgr.endWar(w.attackerFactionId, w.defenderFactionId);
            }
            warmgr.endWar(wid);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void eliminateAttacker(MinecraftServer server, ActiveWar war, WarManager warmgr,
                                          FactionManager fmgr, ServerPlayer sp, UUID uid, String msg) {
        war.attackerLives.put(uid, 0);
        warmgr.setDirty();
        sp.displayClientMessage(Component.literal(msg), false);
        sp.setGameMode(GameType.SPECTATOR);
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

        boolean captureWin = war.captureProgress >= captureTimeSec;
        boolean atkElim    = war.allAttackersEliminated();
        boolean defElim    = allDefendersEliminated(war);
        // Defenders win by capturing the attacker's faction table
        boolean defCaptureWin = !war.outpostPhase && war.attackerTablePos != null
                && war.defenderCaptureProgress >= captureTimeSec;

        if (!captureWin && !atkElim && !defElim && !defCaptureWin) return;

        // Determine winner — attackers win by capturing defender's base or wiping defenders;
        // defenders win by capturing attacker's base or wiping attackers.
        boolean attackersWon = captureWin || defElim;

        war.phase = WarPhase.ENDED;
        warmgr.setDirty();
        // Record post-war cooldown so the attacker can't immediately re-declare on the same defender
        warmgr.recordWarCooldown(war.attackerFactionId, war.defenderFactionId);
        broadcastWarEnd(server, war, fmgr, attackersWon);

        if (attackersWon) {
            String how = captureWin ? "captured " + defName + "'s base!" : "eliminated all defenders!";
            if (af != null) notifyFaction(server, af, Component.literal("§6[War] §a§lVICTORY! §eYou " + how));
            if (df != null) notifyFaction(server, df, Component.literal("§6[War] §c§lDEFEAT! §e" + atkName + " " + how));
            if (af != null) resolveWarOutcome(server, af, df, war, fmgr);

        } else if (defCaptureWin) {
            // Defenders captured the attacker's faction table
            if (df != null) notifyFaction(server, df, Component.literal("§6[War] §a§lVICTORY! §eYou captured " + atkName + "'s base!"));
            if (af != null) notifyFaction(server, af, Component.literal("§6[War] §c§lDEFEAT! §e" + defName + " captured your base!"));
            // Defenders winning by capture: open conquest GUI so defenders choose the spoils
            if (df != null) resolveWarOutcome(server, df, af, war, fmgr);

        } else {
            // Defenders won — all attackers were eliminated
            if (df != null) notifyFaction(server, df, Component.literal("§6[War] §a§lVICTORY! §eAll attackers have been eliminated!"));
            if (af != null) notifyFaction(server, af, Component.literal("§6[War] §c§lDEFEAT! §eAll your fighters were eliminated by " + defName + "."));

            if (df != null) {
                switch (war.warType) {
                    case TERRITORY -> {
                        notifyFaction(server, df, Component.literal("§6[Territory War] §aYou successfully defended your territory!"));
                        notifyFaction(server, af, Component.literal("§6[Territory War] §cYou failed to capture the target chunks."));
                    }
                    case ALL_OUT -> resolveWarOutcome(server, df, af, war, fmgr);
                    case FIGHT   -> {
                        notifyFaction(server, df, Component.literal("§6[Faction Fight] §aVictory — honour to the defenders!"));
                    }
                    default      -> openConquestGui(server, df, af, fmgr);
                }
            }
        }
    }

    /** Broadcasts the ENDED phase to all committed players. */
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

    // ── Teleport attackers on war start ───────────────────────────────────────

    /**
     * When the grace period ends, teleports every committed attacker to a random
     * unclaimed chunk within 1-5 chunks of the defender's territory.
     */
    private static void teleportAttackers(MinecraftServer server, ActiveWar war, FactionManager fmgr) {
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
            int by = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);

            sp.teleportTo(level, bx + 0.5, by, bz + 0.5, sp.getYRot(), sp.getXRot());
            sp.displayClientMessage(Component.literal(
                    "§c[War] §eTeleported to the front line! Find the defending base!"), false);
        }
    }

    static void notifyParticipants(MinecraftServer server, ActiveWar war, Component msg) {
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

    // ── Restore spectators on login (in case war ended while offline) ─────────

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.isSpectator()) return;
        // If there's no active war for this player, restore to survival
        WarManager warmgr = WarManager.get(player.server);
        if (warmgr.getWarForPlayer(player.getUUID()) == null) {
            player.setGameMode(GameType.SURVIVAL);
            player.displayClientMessage(Component.literal("§aRestored to survival mode — your war has ended."), false);
        }
    }

    // ── Spectator helpers ─────────────────────────────────────────────────────

    private static final double SPECTATOR_LEASH_BLOCKS = 10.0;

    /**
     * Teleports eliminated spectators back to within {@link #SPECTATOR_LEASH_BLOCKS} blocks
     * of their nearest living teammate. Prevents spectators from roaming freely.
     */
    private static void enforceSpectatorLeash(MinecraftServer server, Map<UUID, Integer> side) {
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
            if (nearest == null) continue; // all dead — free to roam

            boolean wrongDim = !sp.level().dimension().equals(nearest.level().dimension());
            if (wrongDim || minDist > SPECTATOR_LEASH_BLOCKS) {
                sp.teleportTo((ServerLevel) nearest.level(),
                        nearest.getX(), nearest.getY() + 1.0, nearest.getZ(),
                        sp.getYRot(), sp.getXRot());
            }
        }
    }

    /** Restores all eliminated (spectator) players in a war back to survival mode. */
    private static void restoreWarSpectators(MinecraftServer server, ActiveWar war) {
        Set<UUID> all = new HashSet<>(war.attackerLives.keySet());
        all.addAll(war.defenderLives.keySet());
        for (UUID uid : all) {
            ServerPlayer sp = server.getPlayerList().getPlayer(uid);
            if (sp != null && sp.isSpectator()) {
                sp.setGameMode(GameType.SURVIVAL);
                sp.displayClientMessage(Component.literal("§aThe war has ended — you have been restored to survival."), false);
            }
        }
    }

    // ── KOTH particle effects ─────────────────────────────────────────────────

    /**
     * Spawns particles in a clear ring around the faction table border to show who controls it:
     *   No one    → white campfire signal smoke
     *   Attackers → orange flame
     *   Defenders → blue soul-fire flame
     *   Both      → alternating flame and soul-fire
     */
    private static void spawnKothParticles(MinecraftServer server, ActiveWar war,
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
    private static void spawnKothParticles(ServerLevel level, BlockPos tablePos,
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

            net.minecraft.core.particles.SimpleParticleType particle;
            if (teamAOn && teamBOn) {
                particle = (i % 2 == 0)
                        ? net.minecraft.core.particles.ParticleTypes.FLAME
                        : net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME;
            } else if (teamAOn) {
                particle = net.minecraft.core.particles.ParticleTypes.FLAME;
            } else if (teamBOn) {
                particle = net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME;
            } else {
                particle = net.minecraft.core.particles.ParticleTypes.SMOKE;
            }

            for (net.minecraft.server.level.ServerPlayer sp : nearbyPlayers) {
                level.sendParticles(sp, particle, true, px, cy, pz, 2, 0.05, 0.05, 0.05, 0.0);
            }
        }

        net.minecraft.core.particles.SimpleParticleType pillarParticle;
        if (teamAOn || teamBOn) {
            pillarParticle = teamAOn
                    ? net.minecraft.core.particles.ParticleTypes.FLAME
                    : net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME;
        } else {
            pillarParticle = net.minecraft.core.particles.ParticleTypes.SMOKE;
        }
        for (int h = 1; h <= 3; h++) {
            for (net.minecraft.server.level.ServerPlayer sp : nearbyPlayers) {
                level.sendParticles(sp, pillarParticle, true, cx, cy + h, cz, 2, 0.05, 0.05, 0.05, 0.0);
            }
        }
    }

    // ── Resource War helpers ──────────────────────────────────────────────────

    /** Sends the current RWA state to a single online player (for HUD updates). */
    private static void sendRwaPacketToPlayer(ServerPlayer player, ResourceWarAccess rwa) {
        PacketDistributor.sendToPlayer(player,
                new com.admin82.factions.network.packet.SyncResourceWarAccessPacket(
                        rwa.expiresAt, rwa.blockLimit, rwa.blocksBroken));
    }

    /** Sends the current RWA state to all online members of the winner faction. */
    static void broadcastRwaToWinner(MinecraftServer server, ResourceWarAccess rwa, FactionManager fmgr) {
        Faction winner = fmgr.getAllFactions().get(rwa.winnerFactionId);
        if (winner == null) return;
        com.admin82.factions.network.packet.SyncResourceWarAccessPacket pkt =
                new com.admin82.factions.network.packet.SyncResourceWarAccessPacket(
                        rwa.expiresAt, rwa.blockLimit, rwa.blocksBroken);
        for (FactionMember m : winner.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) PacketDistributor.sendToPlayer(sp, pkt);
        }
    }

    /** Returns the highest-ranked online member of a faction, or null if nobody is online. */
    private static ServerPlayer getHighestOnlineMember(MinecraftServer server, Faction faction) {
        ServerPlayer target = null;
        int topLevel = -1;
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp == null) continue;
            int lvl = m.getRole().getLevel();
            if (lvl > topLevel) { topLevel = lvl; target = sp; }
        }
        return target;
    }

    /**
     * Sends an {@link OpenConquestGuiPacket} to the highest-ranked online member of the
     * winning faction so they can choose what to do with the defeated faction.
     * Stores a pending conquest so the packet can be re-sent if needed.
     */
    private static void openConquestGui(MinecraftServer server, Faction attacker,
                                        Faction defeated, FactionManager fmgr) {
        if (defeated == null) return;
        EconomyManager eco = EconomyManager.get(server);
        VassalManager  vmgr = VassalManager.get(server);

        UUID defeatedId   = defeated.getId();
        String defName    = defeated.getName();
        int    defClaims  = defeated.getLandClaims().size();
        long   defVault   = eco.getVault(defeatedId);

        vmgr.addPendingConquest(attacker.getId(), defeatedId);

        // Find highest-ranked online member of the attacker faction
        ServerPlayer target = null;
        int topLevel = -1;
        for (FactionMember m : attacker.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp == null) continue;
            int lvl = m.getRole().getLevel();
            if (lvl > topLevel) { topLevel = lvl; target = sp; }
        }
        if (target == null) return; // nobody online — will be re-sent on next tick check

        PacketDistributor.sendToPlayer(target,
                new OpenConquestGuiPacket(defeatedId, defName, defClaims, defVault));
    }

    /**
     * Resolves the attacker-wins outcome based on the war's {@link WarType}.
     * <ul>
     *   <li>{@code TERRITORY} — opens the full conquest decision GUI (make vassal or seize land).</li>
     *   <li>{@code RESOURCE}  — auto-transfers 50 % of the defeated faction's vault to the victor.</li>
     *   <li>{@code VAULT}     — auto-seizes the entire defeated faction's vault.</li>
     *   <li>{@code FIGHT}     — no material consequence; bragging rights only.</li>
     * </ul>
     */
    private static void resolveWarOutcome(MinecraftServer server, Faction winner,
                                          Faction loser, ActiveWar war, FactionManager fmgr) {
        if (loser == null || winner == null) return;
        switch (war.warType) {

            // Territory War — auto-transfer the pre-selected chunks
            case TERRITORY -> {
                FactionManager.TableLocation tableLoc = fmgr.getFactionTable(loser.getId());
                int coreX = Integer.MIN_VALUE, coreZ = Integer.MIN_VALUE;
                String coreDim = "";
                if (tableLoc != null) {
                    coreX   = net.minecraft.core.SectionPos.blockToSectionCoord(tableLoc.pos().getX());
                    coreZ   = net.minecraft.core.SectionPos.blockToSectionCoord(tableLoc.pos().getZ());
                    coreDim = tableLoc.dimension();
                }
                int transferred = 0;
                for (String key : war.targetChunkKeys) {
                    String[] parts = key.split(",", 3);
                    if (parts.length < 3) continue;
                    try {
                        int cx = Integer.parseInt(parts[0]);
                        int cz = Integer.parseInt(parts[1]);
                        String dim = parts[2];
                        // Never take the core chunk
                        if (cx == coreX && cz == coreZ && dim.equals(coreDim)) continue;
                        // Verify loser still owns it
                        LandClaim match = null;
                        for (LandClaim c : loser.getLandClaims()) {
                            if (c.chunkX() == cx && c.chunkZ() == cz && c.dimension().toString().equals(dim)) {
                                match = c; break;
                            }
                        }
                        if (match == null) continue;
                        fmgr.unclaimChunk(loser.getId(), cx, cz, dim);
                        fmgr.claimChunk(winner.getId(), cx, cz, dim);
                        transferred++;
                    } catch (NumberFormatException ignored) {}
                }
                final int count = transferred;
                notifyFaction(server, winner, Component.literal(
                        "§6[Territory War] §a§lVICTORY! §e" + count + " chunk"
                        + (count == 1 ? "" : "s") + " transferred from §c" + loser.getName() + "§e."));
                notifyFaction(server, loser, Component.literal(
                        "§6[Territory War] §c§lDEFEAT! §c" + winner.getName()
                        + " §chas seized §e" + count + " chunk" + (count == 1 ? "" : "s")
                        + " §cfrom your territory."));
            }

            // Resource War — grant container-access + block-break access, highlight storage blocks
            case RESOURCE -> {
                WarManager warmgr = WarManager.get(server);
                ResourceWarAccess rwa = new ResourceWarAccess(
                        winner.getId(), loser.getId(), warmgr.getBlockBreakLimit());
                warmgr.addResourceWarAccess(rwa);

                // Send initial HUD state to winner faction members
                broadcastRwaToWinner(server, rwa, fmgr);

                notifyFaction(server, winner, Component.literal(
                        "§6[Resource War] §a§lVICTORY! §eYou may open §c" + loser.getName()
                        + "§e's containers and break up to §c" + rwa.blockLimit
                        + " §eblocks for §a10 minutes§e. §6Storage blocks are highlighted§e!"));
                notifyFaction(server, loser, Component.literal(
                        "§6[Resource War] §c§lDEFEAT! §c" + winner.getName()
                        + " §ccan access your chests and break §c" + rwa.blockLimit
                        + " §cblocks in your territory for the next 10 minutes!"));

                // Scan loser's territory and send container highlights to winning faction
                scanAndSendContainerHighlights(server, winner, loser);
            }

            case VAULT -> {
                EconomyManager eco = EconomyManager.get(server);
                long take = eco.getVault(loser.getId());
                eco.setVault(loser.getId(), 0);
                eco.addVault(winner.getId(), take);
                notifyFaction(server, winner, Component.literal(
                        "§6[War] §a§lVICTORY! §eSeized the entire vault of §c"
                        + loser.getName() + " §e(§6" + Currency.format(take) + "§e)!"));
                notifyFaction(server, loser, Component.literal(
                        "§6[War] §c§lDEFEAT! §c" + winner.getName()
                        + " §cseized your entire faction vault!"));
            }

            case FIGHT -> {
                notifyFaction(server, winner, Component.literal(
                        "§6[War] §a§lVICTORY! §eGlory to §a" + winner.getName() + "§e!"));
                notifyFaction(server, loser, Component.literal(
                        "§6[War] §c§lDEFEAT§c — but fight on with honour."));
            }

            // All Out War — total destruction: all claims, full vault, and disband the loser
            case ALL_OUT -> {
                EconomyManager eco = EconomyManager.get(server);
                // Transfer vault FIRST (performDisband will wipe it otherwise)
                long vault = eco.getVault(loser.getId());
                eco.setVault(loser.getId(), 0);
                eco.addVault(winner.getId(), vault);
                // Transfer all land claims
                var claims = new java.util.ArrayList<>(loser.getLandClaims());
                int transferred = 0;
                for (LandClaim c : claims) {
                    String dim = c.dimension().toString();
                    fmgr.unclaimChunk(loser.getId(), c.chunkX(), c.chunkZ(), dim);
                    fmgr.claimChunk(winner.getId(), c.chunkX(), c.chunkZ(), dim);
                    transferred++;
                }
                final int tCount = transferred;
                final long tVault = vault;
                notifyFaction(server, winner, Component.literal(
                        "§c[All Out War] §a§lTOTAL VICTORY! §eSeized §a" + tCount + " chunk"
                        + (tCount == 1 ? "" : "s") + "§e and §6" + Currency.format(tVault)
                        + " §efrom §c" + loser.getName() + "§e. Their faction has been disbanded!"));
                // Disband the loser (notifies their members)
                FactionCommands.performDisband(server, loser.getId(),
                        Component.literal("§c[All Out War] §f" + winner.getName()
                                + " §chas totally defeated your faction. You have been disbanded!"));
            }
        }
    }

    /** Scans the loser's claimed chunks for container/storage blocks and sends
     *  highlight positions to every online member of the winning faction. */
    private static void scanAndSendContainerHighlights(MinecraftServer server,
                                                        Faction winner, Faction loser) {
        List<BlockPos> positions = new ArrayList<>();
        for (LandClaim claim : loser.getLandClaims()) {
            ServerLevel level = null;
            String dimStr = claim.dimension().toString();
            for (ServerLevel lvl : server.getAllLevels()) {
                if (lvl.dimension().location().toString().equals(dimStr)) { level = lvl; break; }
            }
            if (level == null) continue;
            // Use chunk block-entity list — O(entities in chunk), not O(all blocks)
            // Use getChunk to load the chunk if needed (force-loads to ensure all containers are found)
            var chunk = level.getChunk(claim.chunkX(), claim.chunkZ());
            for (var entry : chunk.getBlockEntities().entrySet()) {
                if (isStorageBlockEntity(entry.getValue())) {
                    positions.add(entry.getKey());
                }
            }
        }

        SyncContainerHighlightsPacket pkt = new SyncContainerHighlightsPacket(positions);
        for (FactionMember m : winner.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) PacketDistributor.sendToPlayer(sp, pkt);
        }
    }

    /** Returns true if the block entity is a storage/container (vanilla or modded). */
    private static boolean isStorageBlockEntity(BlockEntity be) {
        if (be instanceof Container) return true;
        if (be instanceof BaseContainerBlockEntity) return true;
        // Modded storage via NeoForge item-handler capability
        try {
            var cap = be.getLevel().getCapability(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    be.getBlockPos(), null);
            if (cap != null) return true;
        } catch (Exception ignored) {}
        return false;
    }
}


