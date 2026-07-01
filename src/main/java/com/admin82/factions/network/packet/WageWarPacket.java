package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.WarManager;
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
public record WageWarPacket(UUID targetFactionId, List<UUID> attackerUUIDs) implements CustomPacketPayload {

    public static final Type<WageWarPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "wage_war")
    );

    public static final StreamCodec<FriendlyByteBuf, WageWarPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.targetFactionId());
                buf.writeVarInt(pkt.attackerUUIDs().size());
                pkt.attackerUUIDs().forEach(buf::writeUUID);
            },
            buf -> {
                UUID targetId = buf.readUUID();
                int count = buf.readVarInt();
                List<UUID> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) list.add(buf.readUUID());
                return new WageWarPacket(targetId, List.copyOf(list));
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

            // Lives depend on whether the defender has active upkeep
            boolean defHasUpkeep = eco.hasUpkeep(defender.getId());
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
                    validAttackers, defenderUUIDs,
                    attackerLives, defenderLives,
                    defTable.pos(), defTable.dimension()
            );

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
        int captureTimeSec  = Config.WAR_CAPTURE_TIME_SECONDS.get();
        long now            = System.currentTimeMillis();
        int graceSec        = (int) Math.max(0, (war.graceEndsAt - now) / 1000L);
        int phaseOrdinal    = war.phase.ordinal();
        int tableX          = war.defenderTablePos.getX();
        int tableZ          = war.defenderTablePos.getZ();
        String tableDim     = war.defenderDimension;

        sendToLives(server, war.attackerLives, attackerName, defenderName,
                true, phaseOrdinal, graceSec, war.captureProgress, captureTimeSec,
                war.totalAttackerLives(), war.totalDefenderLives(), war, tableX, tableZ, tableDim);

        sendToLives(server, war.defenderLives, attackerName, defenderName,
                false, phaseOrdinal, graceSec, war.captureProgress, captureTimeSec,
                war.totalAttackerLives(), war.totalDefenderLives(), war, tableX, tableZ, tableDim);
    }

    private static void sendToLives(MinecraftServer server, Map<UUID, Integer> livesMap,
                                    String atkName, String defName,
                                    boolean isAttacker, int phase, int graceSec,
                                    float captureProgress, int captureTimeSec,
                                    int totalAtk, int totalDef, ActiveWar war,
                                    int tableX, int tableZ, String tableDim) {
        for (Map.Entry<UUID, Integer> e : livesMap.entrySet()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
            if (sp == null) continue;
            var pkt = new SyncWarStatePacket(
                    war.warId, phase, graceSec, captureProgress, captureTimeSec,
                    e.getValue(), isAttacker, atkName, defName,
                    totalAtk, totalDef, tableX, tableZ, tableDim
            );
            PacketDistributor.sendToPlayer(sp, pkt);
        }
    }
}
