package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.screen.OutpostPlacementScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: open the outpost placement confirmation screen.
 */
public record OpenOutpostPlacementPacket(BlockPos pos, String dimension, String problem, int slotsUsed, int slots, long upkeep) implements CustomPacketPayload {

    public static final Type<OpenOutpostPlacementPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "open_outpost_placement"));

    public static final StreamCodec<FriendlyByteBuf, OpenOutpostPlacementPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeBlockPos(pkt.pos()); buf.writeUtf(pkt.dimension()); buf.writeUtf(pkt.problem(), 256);
                        buf.writeVarInt(pkt.slotsUsed()); buf.writeVarInt(pkt.slots()); buf.writeVarLong(pkt.upkeep());
                    },
                    buf -> new OpenOutpostPlacementPacket(buf.readBlockPos(), buf.readUtf(256), buf.readUtf(256),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarLong()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenOutpostPlacementPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(OpenOutpostPlacementPacket pkt) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var menu = new com.admin82.factions.menu.OutpostPlacementMenu(com.admin82.factions.menu.UiKit.CLIENT_MENU_ID, mc.player.getInventory(), pkt);
        mc.player.containerMenu = menu;
        mc.setScreen(new OutpostPlacementScreen(menu, mc.player.getInventory(), net.minecraft.network.chat.Component.literal("Place Outpost")));
    }
}
