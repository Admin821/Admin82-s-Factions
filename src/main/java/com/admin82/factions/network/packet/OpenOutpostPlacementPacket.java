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

/**
 * Server → Client: open the outpost placement confirmation screen.
 */
public record OpenOutpostPlacementPacket(BlockPos pos, String dimension) implements CustomPacketPayload {

    public static final Type<OpenOutpostPlacementPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "open_outpost_placement"));

    public static final StreamCodec<FriendlyByteBuf, OpenOutpostPlacementPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> { buf.writeBlockPos(pkt.pos()); buf.writeUtf(pkt.dimension()); },
                    buf -> new OpenOutpostPlacementPacket(buf.readBlockPos(), buf.readUtf(256)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenOutpostPlacementPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(OpenOutpostPlacementPacket pkt) {
        net.minecraft.client.Minecraft.getInstance()
                .setScreen(new com.admin82.factions.screen.OutpostPlacementScreen(pkt.pos(), pkt.dimension()));
    }
}
