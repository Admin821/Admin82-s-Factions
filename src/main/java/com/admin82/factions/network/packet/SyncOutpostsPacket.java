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
import java.util.UUID;

/**
 * Server → Client: pushes a summary of every outpost owned by the player's faction.
 * Sent when the Faction Table is opened so the Land tab can show the Outpost list.
 */
public record SyncOutpostsPacket(List<OutpostItem> outposts, long tpCostCopper) implements CustomPacketPayload {

    public record OutpostItem(
            UUID     id,
            BlockPos pos,
            String   dimension,
            boolean  disintegrating,
            float    captureProgress,
            float    captureTimeSeconds,
            String   capturingFactionName) {}

    public static final Type<SyncOutpostsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_outposts"));

    public static final StreamCodec<FriendlyByteBuf, SyncOutpostsPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeLong(pkt.tpCostCopper());
                        buf.writeVarInt(pkt.outposts().size());
                        for (OutpostItem item : pkt.outposts()) {
                            buf.writeUUID(item.id());
                            buf.writeBlockPos(item.pos());
                            buf.writeUtf(item.dimension(), 256);
                            buf.writeBoolean(item.disintegrating());
                            buf.writeFloat(item.captureProgress());
                            buf.writeFloat(item.captureTimeSeconds());
                            buf.writeUtf(item.capturingFactionName(), 64);
                        }
                    },
                    buf -> {
                        long tpCost = buf.readLong();
                        int count = buf.readVarInt();
                        List<OutpostItem> items = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            items.add(new OutpostItem(
                                    buf.readUUID(),
                                    buf.readBlockPos(),
                                    buf.readUtf(256),
                                    buf.readBoolean(),
                                    buf.readFloat(),
                                    buf.readFloat(),
                                    buf.readUtf(64)));
                        }
                        return new SyncOutpostsPacket(items, tpCost);
                    });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncOutpostsPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist != Dist.CLIENT) return;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null
                    && mc.player.containerMenu instanceof com.admin82.factions.menu.FactionTableMenu ftm) {
                ftm.updateOutpostList(pkt.outposts(), pkt.tpCostCopper());
            }
        });
    }
}
