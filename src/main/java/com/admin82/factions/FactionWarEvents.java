package com.admin82.factions;

import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.network.packet.OpenConquestGuiPacket;
import com.admin82.factions.network.packet.SyncWarStatePacket;
import com.admin82.factions.network.packet.WageWarPacket;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarPhase;
import com.admin82.factions.war.VassalManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
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

        // If the claiming faction has no upkeep and is past the grace period, land is unprotected
        if (!eco.hasUpkeep(claimerId) && !claimingFaction.isInGracePeriod()) return;

        // During an active war, both sides can break each other's claimed territory
        WarManager warmgr = WarManager.get(level.getServer());
        ActiveWar war = warmgr.getWarForPlayer(player.getUUID());
        if (war != null && war.phase == WarPhase.ACTIVE) {
            if (war.isAttacker(player.getUUID()) && war.defenderFactionId.equals(claimerId))
                return; // attacker can break defender's blocks during war
            if (war.isDefender(player.getUUID()) && war.attackerFactionId.equals(claimerId))
                return; // defender can break attacker's blocks during war
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

        if (warmgr.getActiveWars().isEmpty()) return;

        long now = System.currentTimeMillis();
        List<UUID> toEnd = new ArrayList<>();

        for (ActiveWar war : warmgr.getActiveWars()) {
            if (war.phase == WarPhase.ENDED) { toEnd.add(war.warId); continue; }

            // ── Grace period countdown ─────────────────────────────────────
            if (war.phase == WarPhase.GRACE && now >= war.graceEndsAt) {
                war.phase = WarPhase.ACTIVE;
                war.lastTickMs = now;
                warmgr.setDirty();
                notifyParticipants(server, war,
                        Component.literal("§c[War] §eGrace period over! §cThe war is now ACTIVE!"));
                teleportAttackers(server, war, fmgr);
            }

            if (war.phase == WarPhase.ACTIVE) {
                float deltaSeconds = (now - war.lastTickMs) / 1000f;
                war.lastTickMs = now;

                // ── Boundary check ─────────────────────────────────────────
                if (Config.WAR_BOUNDARY_ENABLED.get()) {
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
                int captureR = Config.WAR_CAPTURE_RADIUS_BLOCKS.get();
                String defDim = war.defenderDimension;
                BlockPos tablePos = war.defenderTablePos;

                boolean attackersOnPoint = false;
                boolean defendersOnPoint = false;

                for (UUID uid : war.attackerLives.keySet()) {
                    if (war.attackerLives.getOrDefault(uid, 0) <= 0) continue;
                    ServerPlayer sp = server.getPlayerList().getPlayer(uid);
                    if (sp == null || !sp.level().dimension().location().toString().equals(defDim)) continue;
                    double dx = sp.getX() - (tablePos.getX() + 0.5);
                    double dz = sp.getZ() - (tablePos.getZ() + 0.5);
                    if (Math.sqrt(dx * dx + dz * dz) <= captureR) { attackersOnPoint = true; break; }
                }

                for (UUID uid : war.defenderLives.keySet()) {
                    if (war.defenderLives.getOrDefault(uid, 0) <= 0) continue;
                    ServerPlayer sp = server.getPlayerList().getPlayer(uid);
                    if (sp == null || !sp.level().dimension().location().toString().equals(defDim)) continue;
                    double dx = sp.getX() - (tablePos.getX() + 0.5);
                    double dz = sp.getZ() - (tablePos.getZ() + 0.5);
                    if (Math.sqrt(dx * dx + dz * dz) <= captureR) { defendersOnPoint = true; break; }
                }

                float captureTimeSec = Config.WAR_CAPTURE_TIME_SECONDS.get();
                if (attackersOnPoint && !defendersOnPoint) {
                    war.captureProgress = Math.min(captureTimeSec, war.captureProgress + deltaSeconds);
                } else if (defendersOnPoint && !attackersOnPoint) {
                    war.captureProgress = Math.max(0f, war.captureProgress - deltaSeconds);
                }
                // Both present → contested → no change
                warmgr.setDirty();

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

    private static void checkWinConditions(MinecraftServer server, ActiveWar war,
                                           FactionManager fmgr, WarManager warmgr) {
        if (war.phase != WarPhase.ACTIVE) return;

        float captureTimeSec = Config.WAR_CAPTURE_TIME_SECONDS.get();
        FactionManager.TableLocation defTable = fmgr.getFactionTable(war.defenderFactionId);
        Faction af = fmgr.getAllFactions().get(war.attackerFactionId);
        Faction df = fmgr.getAllFactions().get(war.defenderFactionId);
        String atkName = af != null ? af.getName() : "Attackers";
        String defName = df != null ? df.getName() : "Defenders";

        if (war.captureProgress >= captureTimeSec) {
            // Attackers captured the point — they win
            war.phase = WarPhase.ENDED;
            warmgr.setDirty();
            broadcastWarEnd(server, war, fmgr, true);
            if (af != null)
                notifyFaction(server, af, Component.literal("§6[War] §a§lVICTORY! §eYou captured " + defName + "'s base!"));
            if (df != null)
                notifyFaction(server, df, Component.literal("§6[War] §c§lDEFEAT! §e" + atkName + " captured your base!"));

            // Open conquest decision GUI for the attacker faction
            if (af != null) openConquestGui(server, af, df, fmgr);

        } else if (war.allAttackersEliminated()) {
            // Defenders eliminated all attackers — defenders win
            war.phase = WarPhase.ENDED;
            warmgr.setDirty();
            broadcastWarEnd(server, war, fmgr, false);
            if (df != null)
                notifyFaction(server, df, Component.literal("§6[War] §a§lVICTORY! §eAll attackers have been eliminated!"));
            if (af != null)
                notifyFaction(server, af, Component.literal("§6[War] §c§lDEFEAT! §eAll your fighters were eliminated by " + defName + "."));
        }
    }

    /** Broadcasts the ENDED phase to all committed players. */
    private static void broadcastWarEnd(MinecraftServer server, ActiveWar war, FactionManager fmgr, boolean attackersWon) {
        Faction af = fmgr.getAllFactions().get(war.attackerFactionId);
        Faction df = fmgr.getAllFactions().get(war.defenderFactionId);
        String atkName = af != null ? af.getName() : "Unknown";
        String defName = df != null ? df.getName() : "Unknown";
        int captureTimeSec = Config.WAR_CAPTURE_TIME_SECONDS.get();

        sendWarEndPkt(server, war.attackerLives.keySet(), war, atkName, defName, captureTimeSec, true);
        sendWarEndPkt(server, war.defenderLives.keySet(), war, atkName, defName, captureTimeSec, false);
    }

    private static void sendWarEndPkt(MinecraftServer server, Set<UUID> uids, ActiveWar war,
                                      String atkName, String defName, int captureTimeSec, boolean isAtk) {
        int tx = war.defenderTablePos.getX(), tz = war.defenderTablePos.getZ();
        String tdim = war.defenderDimension;
        for (UUID uid : uids) {
            ServerPlayer sp = server.getPlayerList().getPlayer(uid);
            if (sp == null) continue;
            int myLives = isAtk ? war.attackerLives.getOrDefault(uid, 0) : war.defenderLives.getOrDefault(uid, 0);
            PacketDistributor.sendToPlayer(sp, new SyncWarStatePacket(
                    war.warId, war.phase.ordinal(), 0,
                    war.captureProgress, captureTimeSec, myLives, isAtk,
                    atkName, defName, war.totalAttackerLives(), war.totalDefenderLives(),
                    tx, tz, tdim
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
        PacketDistributor.sendToPlayer(sp, new SyncWarStatePacket(
                war.warId, war.phase.ordinal(), graceSec,
                war.captureProgress, Config.WAR_CAPTURE_TIME_SECONDS.get(),
                myLives, isAtk, atkName, defName,
                war.totalAttackerLives(), war.totalDefenderLives(),
                tx, tz, war.defenderDimension
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
     * Spawns particles around the capture zone each second to show who controls it:
     *   No one    → white campfire signal smoke
     *   Attackers → orange flame
     *   Defenders → blue soul-fire flame
     *   Both      → mix of flame and soul-fire
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

        double spread = Config.WAR_CAPTURE_RADIUS_BLOCKS.get() * 0.65;
        double cx = war.defenderTablePos.getX() + 0.5;
        double cy = war.defenderTablePos.getY() + 1.5;
        double cz = war.defenderTablePos.getZ() + 0.5;

        if (attackersOn && defendersOn) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
                    cx, cy, cz, 8, spread, 0.6, spread, 0.03);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME,
                    cx, cy, cz, 8, spread, 0.6, spread, 0.03);
        } else if (attackersOn) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
                    cx, cy, cz, 16, spread, 0.6, spread, 0.03);
        } else if (defendersOn) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME,
                    cx, cy, cz, 16, spread, 0.6, spread, 0.03);
        } else {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_SIGNAL_SMOKE,
                    cx, cy, cz, 4, spread * 0.5, 0.3, spread * 0.5, 0.01);
        }
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
}

