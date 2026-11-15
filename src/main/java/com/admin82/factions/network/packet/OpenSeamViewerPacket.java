package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.seam.SeamCell;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Server → client: the seams a Seam Viewer scanned, opening its map. */
public record OpenSeamViewerPacket(int centerX, int centerZ, int size, List<SeamCell> cells) implements CustomPacketPayload {

    public static final Type<OpenSeamViewerPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "open_seam_viewer"));

    public static final StreamCodec<FriendlyByteBuf, OpenSeamViewerPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.centerX); buf.writeVarInt(p.centerZ); buf.writeVarInt(p.size);
                SeamCell.writeList(buf, p.cells);
            },
            buf -> new OpenSeamViewerPacket(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), SeamCell.readList(buf)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenSeamViewerPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) open(p);
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void open(OpenSeamViewerPacket p) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var menu = new com.admin82.factions.menu.SeamViewerMenu(com.admin82.factions.menu.UiKit.CLIENT_MENU_ID, mc.player.getInventory(), p);
        mc.player.containerMenu = menu;
        mc.setScreen(new com.admin82.factions.screen.SeamViewerScreen(menu, mc.player.getInventory(), Component.literal("Seam Viewer")));
    }
}
