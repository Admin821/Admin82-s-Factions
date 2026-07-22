package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.screen.SupplyDropScreen;
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

public record SyncSupplyDropPacket(List<String> poolNames) implements CustomPacketPayload {
    public static final Type<SyncSupplyDropPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_supply_drop"));

    public static final StreamCodec<FriendlyByteBuf, SyncSupplyDropPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.poolNames().size());
                for (String name : packet.poolNames()) buf.writeUtf(name, 64);
            },
            buf -> {
                int count = buf.readVarInt();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < count; i++) names.add(buf.readUtf(64));
                return new SyncSupplyDropPacket(names);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncSupplyDropPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(packet);
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncSupplyDropPacket packet) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof SupplyDropScreen screen) {
            screen.updatePoolNames(packet.poolNames());
        }
    }
}