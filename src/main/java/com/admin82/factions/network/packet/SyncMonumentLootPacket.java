package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.screen.MonumentScreen;
import com.admin82.factions.supplydrop.SupplyDropPool;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record SyncMonumentLootPacket(UUID monumentId, String poolName, List<String> poolNames,
                                     int[] minCounts, int[] maxCounts, int[] rarityLevels)
        implements CustomPacketPayload {
    public static final Type<SyncMonumentLootPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_monument_loot"));

    public static final StreamCodec<FriendlyByteBuf, SyncMonumentLootPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeUUID(packet.monumentId);
                buf.writeUtf(packet.poolName, 32);
                buf.writeVarInt(packet.poolNames.size());
                packet.poolNames.forEach(name -> buf.writeUtf(name, 32));
                for (int i = 0; i < SupplyDropPool.SLOT_COUNT; i++) {
                    buf.writeVarInt(packet.minCounts[i]);
                    buf.writeVarInt(packet.maxCounts[i]);
                    buf.writeVarInt(packet.rarityLevels[i]);
                }
            },
            buf -> {
                UUID monumentId = buf.readUUID();
                String poolName = buf.readUtf(32);
                int nameCount = buf.readVarInt();
                List<String> names = new ArrayList<>();
                for (int i = 0; i < nameCount; i++) names.add(buf.readUtf(32));
                int[] min = new int[SupplyDropPool.SLOT_COUNT];
                int[] max = new int[SupplyDropPool.SLOT_COUNT];
                int[] rarity = new int[SupplyDropPool.SLOT_COUNT];
                for (int i = 0; i < SupplyDropPool.SLOT_COUNT; i++) {
                    min[i] = buf.readVarInt();
                    max[i] = buf.readVarInt();
                    rarity[i] = buf.readVarInt();
                }
                return new SyncMonumentLootPacket(monumentId, poolName, names, min, max, rarity);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncMonumentLootPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().player == null
                    || !(Minecraft.getInstance().player.containerMenu instanceof MonumentMenu menu)) return;
            menu.updateLootEditor(packet.monumentId, packet.poolName, packet.poolNames,
                    packet.minCounts, packet.maxCounts, packet.rarityLevels);
            if (Minecraft.getInstance().screen instanceof MonumentScreen screen) screen.refreshContent();
        });
    }
}