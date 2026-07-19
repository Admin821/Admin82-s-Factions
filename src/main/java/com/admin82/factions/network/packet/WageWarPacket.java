package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.*;

/**
 * Client → Server: player requests to start an active war against a target faction,
 * committing a specific set of their own faction members as attackers.
 */
public record WageWarPacket(UUID targetFactionId, List<UUID> attackerUUIDs, WarType warType,
                            List<String> targetChunkKeys) implements CustomPacketPayload {

    public static final Type<WageWarPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "wage_war")
    );

    public static final StreamCodec<FriendlyByteBuf, WageWarPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.targetFactionId());
                buf.writeVarInt(pkt.attackerUUIDs().size());
                pkt.attackerUUIDs().forEach(buf::writeUUID);
                buf.writeVarInt(pkt.warType().ordinal());
                buf.writeVarInt(pkt.targetChunkKeys().size());
                pkt.targetChunkKeys().forEach(k -> buf.writeUtf(k, 256));
            },
            buf -> {
                UUID targetId = buf.readUUID();
                int count = buf.readVarInt();
                List<UUID> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) list.add(buf.readUUID());
                WarType warType = WarType.fromOrdinal(buf.readVarInt());
                int keyCount = buf.readVarInt();
                List<String> keys = new ArrayList<>(keyCount);
                for (int i = 0; i < keyCount; i++) keys.add(buf.readUtf(256));
                return new WageWarPacket(targetId, List.copyOf(list), warType, List.copyOf(keys));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ── Server handler ────────────────────────────────────────────────────────

    public static void handle(WageWarPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer    player  = (ServerPlayer) context.player();
            FactionManager  fmgr   = FactionManager.get(player.server);
            EconomyManager  eco    = EconomyManager.get(player.server);
            WarManager      warmgr = WarManager.get(player.server);

            Faction attacker = fmgr.getFactionForPlayer(player.getUUID());
            if (attacker == null) return;

            FactionMember member = attacker.getMember(player.getUUID());
            if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) {
                player.displayClientMessage(Component.literal("§cOfficer rank or higher required to declare war."), false);
                return;
            }

            Faction defender = fmgr.getAllFactions().get(packet.targetFactionId());
            if (defender == null || defender.getId().equals(attacker.getId())) {
                player.displayClientMessage(Component.literal("§cInvalid target faction."), false);
                return;
            }

            if (warmgr.getWarBetween(attacker.getId(), defender.getId()) != null) {
                player.displayClientMessage(Component.literal("§cAlready at war with that faction."), false);
                return;
            }

            // Block if the defender is already in any active war
            if (warmgr.isFactionInActiveWar(defender.getId())) {
                player.displayClientMessage(Component.literal(
                        "§c[War] §e" + defender.getName() + "§c is already engaged in a war. Wait until their war ends before declaring."), false);
                return;
            }

            // Check post-war cooldown (attacker → same defender)
            if (warmgr.isOnWarCooldown(attacker.getId(), defender.getId())) {
                long secs = warmgr.getWarCooldownRemainingSeconds(attacker.getId(), defender.getId());
                String timeStr = secs >= 3600 ? (secs / 3600) + "h " + ((secs % 3600) / 60) + "m"
                                              : (secs / 60) + "m " + (secs % 60) + "s";
                player.displayClientMessage(Component.literal(
                        "§c[War] You recently fought §e" + defender.getName()
                        + "§c. Cooldown: §e" + timeStr + "§c remaining."), false);
                return;
            }

            // Check that enough of the defender's members are online
            int minPct = warmgr.getMinOnlinePercentageForWar();
            if (minPct > 0) {
                java.util.List<FactionMember> defMembers = defender.getMembers();
                if (!defMembers.isEmpty()) {
                    int online = (int) defMembers.stream()
                            .filter(m -> player.server.getPlayerList().getPlayer(m.getUuid()) != null)
                            .count();
                    int required = (int) Math.ceil(defMembers.size() * minPct / 100.0);
                    if (online < required) {
                        player.displayClientMessage(Component.literal(
                                "§c[War] §e" + defender.getName() + "§c doesn't have enough members online."
                                + " Need §e" + minPct + "%§c (§e" + required + "§c/§e" + defMembers.size()
                                + "§c). Currently §e" + online + "§c online."), false);
                        return;
                    }
                }
            }

            // Validate committed attackers are real faction members
            List<UUID> validAttackers = new ArrayList<>();
            for (UUID uid : packet.attackerUUIDs()) {
                if (attacker.getMember(uid) != null) validAttackers.add(uid);
            }
            if (validAttackers.isEmpty()) {
                player.displayClientMessage(Component.literal("§cSelect at least one attacker."), false);
                return;
            }

            // Defending faction table location (needed for capture point)
            FactionManager.TableLocation defTable = fmgr.getFactionTable(defender.getId());
            if (defTable == null) {
                player.displayClientMessage(Component.literal("§cDefending faction has no registered table."), false);
                return;
            }

            // Lives depend on whether the defender has active upkeep (vault solvent)
            boolean defHasUpkeep = eco.isProtected(defender.getId());
            int attackerLives = Config.WAR_ATTACKER_LIVES.get();
            int defenderLives = defHasUpkeep
                    ? Config.WAR_DEFENDER_LIVES.get()
                    : Config.WAR_DEFENDER_LIVES_NO_UPKEEP.get();

            List<UUID> defenderUUIDs = defender.getMembers().stream()
                    .map(FactionMember::getUuid).toList();

            // Register war diplomatically (adds WarEntry to both factions)
            fmgr.declareWar(attacker.getId(), defender.getId());

            // Create the live ActiveWar
            ActiveWar war = warmgr.startWar(
                    attacker.getId(), defender.getId(),
                    packet.warType(),
                    validAttackers, defenderUUIDs,
                    attackerLives, defenderLives,
                    defTable.pos(), defTable.dimension(),
                    packet.targetChunkKeys());

            // Store attacker's faction table so defenders can counter-capture it
            FactionManager.TableLocation atkTable = fmgr.getFactionTable(attacker.getId());
            if (atkTable != null) {
                war.attackerTablePos  = atkTable.pos();
                war.attackerDimension = atkTable.dimension();
            }

            int graceSec = warmgr.getGracePeriodSeconds();
            String upkeepNote = defHasUpkeep ? "" : " §7(defender has no upkeep — §c" + defenderLives + " life each§7)";
            notifyFaction(player.server, attacker,
                    Component.literal("§c[War] §eYou declared war on §f" + defender.getName() + "§e! Grace period: §f" + graceSec + "s§e." + upkeepNote));
            notifyFaction(player.server, defender,
                    Component.literal("§c[War] §f" + attacker.getName() + " §cdeclared war on you! Grace period: §f" + graceSec + "s§c. Prepare to defend!"));

            broadcastWarState(player.server, war, fmgr);
        });
    }

    // ── Broadcast helpers (package-visible for FactionWarEvents) ──────────────

    static void notifyFaction(MinecraftServer server, Faction faction, Component msg) {
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) sp.displayClientMessage(msg, false);
        }
    }

    public static void broadcastWarState(MinecraftServer server, ActiveWar war, FactionManager fmgr) {
        Faction af = fmgr.getAllFactions().get(war.attackerFactionId);
        Faction df = fmgr.getAllFactions().get(war.defenderFactionId);
        String attackerName = af != null ? af.getName() : "Unknown";
        String defenderName = df != null ? df.getName() : "Unknown";
        // Use outpost capture time during outpost phase, otherwise war config
        int captureTimeSec  = war.outpostPhase
                ? com.admin82.factions.war.WarManager.get(server).getOutpostKothTime()
                : com.admin82.factions.war.WarManager.get(server).getTableKothTime();
        long now            = System.currentTimeMillis();
        int graceSec        = (int) Math.max(0, (war.graceEndsAt - now) / 1000L);
        int phaseOrdinal    = war.phase.ordinal();
        int tableX          = war.defenderTablePos.getX();
        int tableZ          = war.defenderTablePos.getZ();
        String tableDim     = war.defenderDimension;
        // Actual capture target for compass / KOTH bar
        boolean outpostPhase  = war.outpostPhase && war.outpostPos != null;
        int captureTargetX    = outpostPhase ? war.outpostPos.getX() : tableX;
        int captureTargetZ    = outpostPhase ? war.outpostPos.getZ() : tableZ;
        String captureTargetDim = outpostPhase ? war.outpostDim : tableDim;

        sendToLives(server, war.attackerLives, attackerName, defenderName,
                true, phaseOrdinal, graceSec, war.captureProgress, captureTimeSec,
                war.totalAttackerLives(), war.totalDefenderLives(), war,
                tableX, tableZ, tableDim, outpostPhase, captureTargetX, captureTargetZ, captureTargetDim);

        sendToLives(server, war.defenderLives, attackerName, defenderName,
                false, phaseOrdinal, graceSec, war.captureProgress, captureTimeSec,
                war.totalAttackerLives(), war.totalDefenderLives(), war,
                tableX, tableZ, tableDim, outpostPhase, captureTargetX, captureTargetZ, captureTargetDim);
    }

    private static void sendToLives(MinecraftServer server, Map<UUID, Integer> livesMap,
                                    String atkName, String defName,
                                    boolean isAttacker, int phase, int graceSec,
                                    float captureProgress, int captureTimeSec,
                                    int totalAtk, int totalDef, ActiveWar war,
                                    int tableX, int tableZ, String tableDim,
                                    boolean outpostPhase, int captureTargetX, int captureTargetZ,
                                    String captureTargetDim) {
        for (Map.Entry<UUID, Integer> e : livesMap.entrySet()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
            if (sp == null) continue;
            var pkt = new SyncWarStatePacket(
                    war.warId, phase, graceSec, captureProgress, captureTimeSec,
                    e.getValue(), isAttacker, atkName, defName,
                    totalAtk, totalDef, tableX, tableZ, tableDim,
                    outpostPhase, captureTargetX, captureTargetZ, captureTargetDim,
                    war.defenderCaptureProgress
            );
            PacketDistributor.sendToPlayer(sp, pkt);
        }
    }
}
