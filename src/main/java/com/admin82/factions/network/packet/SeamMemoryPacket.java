package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.SeamViewerMenu;
import com.admin82.factions.seam.SeamCell;
import com.admin82.factions.seam.SeamMemory;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * The Seam Viewer map asking for (client → server, {@code cells} empty) and getting (server → client) the chunks
 * the player has scanned around a spot they dragged the map to.
 */
public record SeamMemoryPacket(int centerX, int centerZ, List<SeamCell> cells) implements CustomPacketPayload {

    /** Chunks across the square sent each time (bigger than the map, so a short drag needs nothing new). */
    public static final int WINDOW = 21;

    public static final Type<SeamMemoryPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "seam_memory"));

    public static final StreamCodec<FriendlyByteBuf, SeamMemoryPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeVarInt(p.centerX); buf.writeVarInt(p.centerZ); SeamCell.writeList(buf, p.cells); },
            buf -> new SeamMemoryPacket(buf.readVarInt(), buf.readVarInt(), SeamCell.readList(buf)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Server: answer with what this player has scanned there. */
    public static void handleServer(SeamMemoryPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            var cells = SeamMemory.get(sp.server).around(sp, new ChunkPos(p.centerX, p.centerZ), WINDOW);
            PacketDistributor.sendToPlayer(sp, new SeamMemoryPacket(p.centerX, p.centerZ, cells));
        });
    }

    /** Client: add them to the open map. */
    public static void handleClient(SeamMemoryPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null && player.containerMenu instanceof SeamViewerMenu menu) menu.addCells(p.cells);
            if (player != null && player.containerMenu instanceof com.admin82.factions.menu.factiontable.FactionTableMenu table) table.addSeams(p.cells);
        });
    }
}
