package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

/**
 * Client → Server: player opens the Negotiations view for a given war.
 * The server responds with a fresh {@link SyncWarDemandsPacket}.
 */
public record OpenNegotiationsPacket(UUID targetFactionId) implements CustomPacketPayload {

    public static final Type<OpenNegotiationsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "open_negotiations")
    );

    public static final StreamCodec<FriendlyByteBuf, OpenNegotiationsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeUUID(pkt.targetFactionId()),
            buf -> new OpenNegotiationsPacket(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenNegotiationsPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer           player = (ServerPlayer) context.player();
            FactionManager         fmgr   = FactionManager.get(player.server);
            WarManager             wmgr   = WarManager.get(player.server);
            WarNegotiationsManager negMgr = WarNegotiationsManager.get(player.server);

            Faction myFaction = fmgr.getFactionForPlayer(player.getUUID());
            if (myFaction == null) return;

            Faction target = fmgr.getAllFactions().get(pkt.targetFactionId());
            if (target == null) return;

            ActiveWar war = wmgr.getWarBetween(myFaction.getId(), target.getId());
            if (war == null) return;

            List<WarDemand> demands = negMgr.getDemandsForWar(war.warId);
            PacketDistributor.sendToPlayer(player, new SyncWarDemandsPacket(war.warId, demands));
        });
    }
}
