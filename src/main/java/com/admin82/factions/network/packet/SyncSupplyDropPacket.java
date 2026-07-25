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

import javax.annotation.Nullable;

public record SyncSupplyDropPacket(List<String> poolNames, @Nullable String scheduledPoolName,
                                   int intervalHours, int radius, int fallSeconds,
                                   long nextDropAt) implements CustomPacketPayload {
    public static final Type<SyncSupplyDropPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_supply_drop"));

    public static final StreamCodec<FriendlyByteBuf, SyncSupplyDropPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.poolNames().size());
                for (String name : packet.poolNames()) buf.writeUtf(name, 64);
                buf.writeBoolean(packet.scheduledPoolName() != null);
                if (packet.scheduledPoolName() != null) buf.writeUtf(packet.scheduledPoolName(), 64);
                buf.writeVarInt(packet.intervalHours());
                buf.writeVarInt(packet.radius());
                buf.writeVarInt(packet.fallSeconds());
                buf.writeLong(packet.nextDropAt());
            },
            buf -> {
                int count = buf.readVarInt();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < count; i++) names.add(buf.readUtf(64));
                String poolName = buf.readBoolean() ? buf.readUtf(64) : null;
                int intervalHours = buf.readVarInt();
                int radius = buf.readVarInt();
                int fallSeconds = buf.readVarInt();
                long nextDropAt = buf.readLong();
                return new SyncSupplyDropPacket(names, poolName, intervalHours, radius, fallSeconds, nextDropAt);
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
            screen.updateSupplyDropData(packet.poolNames(), packet.scheduledPoolName(), packet.intervalHours(),
                    packet.radius(), packet.fallSeconds(), packet.nextDropAt());
        }
    }
}