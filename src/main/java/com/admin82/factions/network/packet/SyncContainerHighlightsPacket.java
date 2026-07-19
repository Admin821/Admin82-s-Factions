package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: sends a list of container/storage block positions in the losing faction's
 * territory so the winning faction can see them highlighted through walls.
 * Sending an empty list clears all highlights.
 */
public record SyncContainerHighlightsPacket(List<BlockPos> positions) implements CustomPacketPayload {

    public static final Type<SyncContainerHighlightsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_container_highlights")
    );

    public static final StreamCodec<FriendlyByteBuf, SyncContainerHighlightsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarInt(pkt.positions().size());
                pkt.positions().forEach(buf::writeBlockPos);
            },
            buf -> {
                int count = buf.readVarInt();
                List<BlockPos> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) list.add(buf.readBlockPos());
                return new SyncContainerHighlightsPacket(List.copyOf(list));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncContainerHighlightsPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncContainerHighlightsPacket pkt) {
        if (pkt.positions().isEmpty()) {
            com.admin82.factions.screen.ContainerHighlightRenderer.clearHighlights();
        } else {
            com.admin82.factions.screen.ContainerHighlightRenderer.setHighlights(pkt.positions());
        }
    }
}
