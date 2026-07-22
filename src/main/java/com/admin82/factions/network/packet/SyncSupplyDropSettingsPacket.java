package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.screen.SupplyDropScreen;
import com.admin82.factions.supplydrop.SupplyDropPool;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SyncSupplyDropSettingsPacket(String poolName, int[] minCounts, int[] maxCounts,
                                           int[] rarityLevels) implements CustomPacketPayload {
    public static final Type<SyncSupplyDropSettingsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_supply_drop_settings"));

    public static final StreamCodec<FriendlyByteBuf, SyncSupplyDropSettingsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.poolName(), 64);
                for (int i = 0; i < SupplyDropPool.SLOT_COUNT; i++) {
                    buf.writeVarInt(packet.minCounts()[i]);
                    buf.writeVarInt(packet.maxCounts()[i]);
                    buf.writeVarInt(packet.rarityLevels()[i]);
                }
            },
            buf -> {
                String poolName = buf.readUtf(64);
                int[] minCounts = new int[SupplyDropPool.SLOT_COUNT];
                int[] maxCounts = new int[SupplyDropPool.SLOT_COUNT];
                int[] rarityLevels = new int[SupplyDropPool.SLOT_COUNT];
                for (int i = 0; i < SupplyDropPool.SLOT_COUNT; i++) {
                    minCounts[i] = buf.readVarInt();
                    maxCounts[i] = buf.readVarInt();
                    rarityLevels[i] = buf.readVarInt();
                }
                return new SyncSupplyDropSettingsPacket(poolName, minCounts, maxCounts, rarityLevels);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncSupplyDropSettingsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(packet);
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncSupplyDropSettingsPacket packet) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof SupplyDropScreen screen) {
            screen.updatePoolSettings(packet.poolName(), packet.minCounts(), packet.maxCounts(), packet.rarityLevels());
        }
    }
}