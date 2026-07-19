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

import java.util.UUID;

/**
 * Server → Client: open the Outpost Manager interaction GUI.
 */
public record OpenOutpostManagerPacket(
        UUID outpostId, String ownerName, boolean disintegrating,
        boolean canSetSpawn, boolean isOwner, BlockPos managerPos, String dimension,
        float captureProgress, String capturingFactionName,
        // 11×11 territory map centred on the outpost chunk.
        // Each byte: 0 = unclaimed, 1 = own faction, 2 = other faction.
        byte[] mapTiles, int centerChunkX, int centerChunkZ)
        implements CustomPacketPayload {

    public static final Type<OpenOutpostManagerPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "open_outpost_manager"));

    public static final StreamCodec<FriendlyByteBuf, OpenOutpostManagerPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeUUID(pkt.outpostId());
                        buf.writeUtf(pkt.ownerName(), 64);
                        buf.writeBoolean(pkt.disintegrating());
                        buf.writeBoolean(pkt.canSetSpawn());
                        buf.writeBoolean(pkt.isOwner());
                        buf.writeBlockPos(pkt.managerPos());
                        buf.writeUtf(pkt.dimension(), 256);
                        buf.writeFloat(pkt.captureProgress());
                        buf.writeUtf(pkt.capturingFactionName(), 64);
                        buf.writeVarInt(pkt.mapTiles().length);
                        buf.writeBytes(pkt.mapTiles());
                        buf.writeInt(pkt.centerChunkX());
                        buf.writeInt(pkt.centerChunkZ());
                    },
                    buf -> {
                        UUID id           = buf.readUUID();
                        String owner      = buf.readUtf(64);
                        boolean disint    = buf.readBoolean();
                        boolean canSpawn  = buf.readBoolean();
                        boolean isOwner   = buf.readBoolean();
                        BlockPos pos      = buf.readBlockPos();
                        String dim        = buf.readUtf(256);
                        float capProg     = buf.readFloat();
                        String capName    = buf.readUtf(64);
                        int tileCount     = buf.readVarInt();
                        byte[] tiles      = new byte[tileCount];
                        buf.readBytes(tiles);
                        int ccx = buf.readInt();
                        int ccz = buf.readInt();
                        return new OpenOutpostManagerPacket(
                                id, owner, disint, canSpawn, isOwner,
                                pos, dim, capProg, capName, tiles, ccx, ccz);
                    });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenOutpostManagerPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(OpenOutpostManagerPacket pkt) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null) return;
        var menu = new com.admin82.factions.menu.OutpostManagerMenu(1, mc.player.getInventory(), pkt);
        mc.player.containerMenu = menu;
        mc.setScreen(new com.admin82.factions.screen.OutpostManagerScreen(
                menu, mc.player.getInventory(),
                net.minecraft.network.chat.Component.literal("Outpost Manager")));
    }
}
