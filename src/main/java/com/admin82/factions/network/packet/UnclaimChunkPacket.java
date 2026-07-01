package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record UnclaimChunkPacket(int chunkX, int chunkZ, String dimension) implements CustomPacketPayload {

    public static final Type<UnclaimChunkPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "unclaim_chunk")
    );

    public static final StreamCodec<FriendlyByteBuf, UnclaimChunkPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeInt(pkt.chunkX());
                buf.writeInt(pkt.chunkZ());
                buf.writeUtf(pkt.dimension());
            },
            buf -> new UnclaimChunkPacket(buf.readInt(), buf.readInt(), buf.readUtf(256))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UnclaimChunkPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null) return;

            FactionMember member = faction.getMember(player.getUUID());
            if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) return;

            manager.unclaimChunk(faction.getId(), packet.chunkX(), packet.chunkZ(), packet.dimension());
            PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(manager.getFactionForPlayer(player.getUUID())));
        });
    }
}
