package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
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

    // Server handler

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

            // Validate terms. A demand with no terms is a white-peace offer.
            long money = Math.max(0, pkt.moneyAmount());
            String itemId = pkt.itemId() == null ? "" : pkt.itemId().trim();
            int itemCount = itemId.isEmpty() ? 0 : Math.max(1, Math.min(6400, pkt.itemCount()));
            if (!itemId.isEmpty()) {
                ResourceLocation id = ResourceLocation.tryParse(
                        itemId.contains(":") ? itemId : "minecraft:" + itemId);
                var item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
                if (item == null || item == Items.AIR) {
                    player.displayClientMessage(Component.literal("§cUnknown item §e" + itemId + "§c."), false);
                    return;
                }
                itemId = id.toString();
            }
            int land = Math.max(0, Math.min(pkt.landChunks(), Math.max(0, receiver.getLandClaims().size() - 1)));
            if (pkt.landChunks() > land) {
                player.displayClientMessage(Component.literal("§c" + receiver.getName() + " only has §e" + land
                        + "§c chunks besides their core chunk."), false);
                return;
            }

            // Only one open demand per side: a new one replaces the previous.
            for (WarDemand old : negMgr.getDemandsForWar(war.warId)) {
                if (old.status == WarDemand.Status.PENDING && old.senderFactionId.equals(sender.getId())) {
                    negMgr.resolveDemand(old.demandId, WarDemand.Status.WITHDRAWN);
                }
            }

            WarDemand demand = new WarDemand(
                    war.warId,
                    sender.getId(), sender.getName(),
                    receiver.getId(), receiver.getName(),
                    money,
                    itemId, itemCount,
                    land,
                    pkt.vassalTerm()
            );
            negMgr.addDemand(demand);

            String what = demand.isWhitePeace() ? "a peace offer" : "a demand";
            notifyFaction(player.server, sender, Component.literal("§e[Negotiations] §7" + player.getName().getString()
                    + " sent " + what + " to §c" + receiver.getName() + "§7: " + demand.termsSummary()));
            notifyFaction(player.server, receiver, Component.literal("§e[Negotiations] §c" + sender.getName()
                    + " §7sent you " + what + ": " + demand.termsSummary()
                    + " §8(open Wars → Negotiate on the Faction Table; expires in 10 min)"));

            broadcastDemandsToFaction(player.server, sender.getId(),   war.warId, negMgr, fmgr);
            broadcastDemandsToFaction(player.server, receiver.getId(), war.warId, negMgr, fmgr);
        });
    }

    // Package-visible helpers

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
