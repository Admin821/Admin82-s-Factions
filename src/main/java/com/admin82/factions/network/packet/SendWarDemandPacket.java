package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

/**
 * Client → Server: send a peace / surrender demand to the enemy faction during an active war.
 */
public record SendWarDemandPacket(
        UUID    targetFactionId,
        long    moneyAmount,
        String  itemId,
        int     itemCount,
        int     landChunks,
        boolean vassalTerm
) implements CustomPacketPayload {

    public static final Type<SendWarDemandPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "send_war_demand")
    );

    public static final StreamCodec<FriendlyByteBuf, SendWarDemandPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.targetFactionId());
                buf.writeLong(pkt.moneyAmount());
                buf.writeUtf(pkt.itemId(), 256);
                buf.writeVarInt(pkt.itemCount());
                buf.writeVarInt(pkt.landChunks());
                buf.writeBoolean(pkt.vassalTerm());
            },
            buf -> new SendWarDemandPacket(
                    buf.readUUID(),
                    buf.readLong(),
                    buf.readUtf(256),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean()
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ── Server handler ────────────────────────────────────────────────────────

    public static void handle(SendWarDemandPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer           player = (ServerPlayer) context.player();
            FactionManager         fmgr   = FactionManager.get(player.server);
            WarManager             wmgr   = WarManager.get(player.server);
            WarNegotiationsManager negMgr = WarNegotiationsManager.get(player.server);

            Faction sender = fmgr.getFactionForPlayer(player.getUUID());
            if (sender == null) return;

            FactionMember member = sender.getMember(player.getUUID());
            if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) {
                player.displayClientMessage(
                        Component.literal("§cOfficer rank or higher required to send demands."), false);
                return;
            }

            Faction receiver = fmgr.getAllFactions().get(pkt.targetFactionId());
            if (receiver == null) return;

            ActiveWar war = wmgr.getWarBetween(sender.getId(), receiver.getId());
            if (war == null) {
                player.displayClientMessage(Component.literal("§cNot at war with that faction."), false);
                return;
            }

            // Require at least one non-zero term
            if (pkt.moneyAmount() <= 0
                    && pkt.itemId().isEmpty()
                    && pkt.landChunks() <= 0
                    && !pkt.vassalTerm()) {
                player.displayClientMessage(Component.literal("§cSpecify at least one demand term."), false);
                return;
            }

            WarDemand demand = new WarDemand(
                    war.warId,
                    sender.getId(), sender.getName(),
                    receiver.getId(), receiver.getName(),
                    pkt.moneyAmount(),
                    pkt.itemId(), pkt.itemCount(),
                    pkt.landChunks(),
                    pkt.vassalTerm()
            );
            negMgr.addDemand(demand);

            notifyFaction(player.server, sender,
                    Component.literal("§e[Negotiations] §7Demand sent to §c" + receiver.getName() + "§7."));
            notifyFaction(player.server, receiver,
                    Component.literal("§e[Negotiations] §c" + sender.getName()
                            + " §7sent you a demand — check the §eWars §7tab!"));

            broadcastDemandsToFaction(player.server, sender.getId(),   war.warId, negMgr, fmgr);
            broadcastDemandsToFaction(player.server, receiver.getId(), war.warId, negMgr, fmgr);
        });
    }

    // ── Package-visible helpers ───────────────────────────────────────────────

    static void notifyFaction(MinecraftServer server, Faction faction, Component msg) {
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) sp.displayClientMessage(msg, false);
        }
    }

    static void broadcastDemandsToFaction(MinecraftServer server,
                                          UUID factionId, UUID warId,
                                          WarNegotiationsManager negMgr,
                                          FactionManager fmgr) {
        Faction f = fmgr.getFaction(factionId);
        if (f == null) return;
        List<WarDemand> warDemands = negMgr.getDemandsForWar(warId);
        SyncWarDemandsPacket pkt = new SyncWarDemandsPacket(warId, warDemands);
        for (FactionMember m : f.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(m.getUuid());
            if (sp != null) PacketDistributor.sendToPlayer(sp, pkt);
        }
    }
}
