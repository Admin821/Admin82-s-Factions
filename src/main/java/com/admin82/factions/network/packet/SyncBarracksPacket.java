package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.screen.BarracksScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → Client: sync kit names to the barracks screen.
 * Kit items themselves are synced through vanilla container slot sync (staging handler).
 */
public record SyncBarracksPacket(List<String> kitNames) implements CustomPacketPayload {

    public static final Type<SyncBarracksPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_barracks"));

    public static final StreamCodec<FriendlyByteBuf, SyncBarracksPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.kitNames().size());
                        for (String name : pkt.kitNames()) buf.writeUtf(name, 64);
                    },
                    buf -> {
                        int nameCount = buf.readVarInt();
                        List<String> names = new ArrayList<>();
                        for (int i = 0; i < nameCount; i++) names.add(buf.readUtf(64));
                        return new SyncBarracksPacket(names);
                    });

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncBarracksPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncBarracksPacket pkt) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof BarracksScreen bs) {
            bs.updateKitData(pkt.kitNames());
        }
    }
}
