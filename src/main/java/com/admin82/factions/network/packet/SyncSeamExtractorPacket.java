package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.SeamExtractorMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server → client: what a Seam Extractor's screen shows, sent twice a second while it's open. */
public record SyncSeamExtractorPacket(BlockPos pos, String ore, String seamText, boolean endless, int tier, long remaining,
                                      long tierAmount, double secondsPerOre, double orePerExtraction, double wear,
                                      double progress, long energy, long capacity, long powerPerTick, String status,
                                      boolean running, String path, int pathLevel, int miners, int minerLimit)
        implements CustomPacketPayload {

    public static final Type<SyncSeamExtractorPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_seam_extractor"));

    public static final StreamCodec<FriendlyByteBuf, SyncSeamExtractorPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos); buf.writeUtf(p.ore, 256); buf.writeUtf(p.seamText, 128); buf.writeBoolean(p.endless);
                buf.writeVarInt(p.tier); buf.writeVarLong(p.remaining); buf.writeVarLong(p.tierAmount);
                buf.writeDouble(p.secondsPerOre); buf.writeDouble(p.orePerExtraction); buf.writeDouble(p.wear);
                buf.writeDouble(p.progress); buf.writeVarLong(p.energy); buf.writeVarLong(p.capacity); buf.writeVarLong(p.powerPerTick);
                buf.writeUtf(p.status, 128); buf.writeBoolean(p.running); buf.writeUtf(p.path, 32); buf.writeVarInt(p.pathLevel);
                buf.writeVarInt(p.miners); buf.writeVarInt(p.minerLimit);
            },
            buf -> new SyncSeamExtractorPacket(buf.readBlockPos(), buf.readUtf(256), buf.readUtf(128), buf.readBoolean(),
                    buf.readVarInt(), buf.readVarLong(), buf.readVarLong(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readUtf(128), buf.readBoolean(),
                    buf.readUtf(32), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncSeamExtractorPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null && player.containerMenu instanceof SeamExtractorMenu menu && menu.pos.equals(p.pos)) menu.update(p);
        });
    }
}
