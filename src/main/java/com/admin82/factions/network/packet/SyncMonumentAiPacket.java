package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.screen.MonumentScreen;
import com.admin82.factions.supplydrop.SupplyDropPool;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record SyncMonumentAiPacket(UUID monumentId, int[] minimumCounts, int[] maximumCounts, int[] difficultyLevels,
                                   int deadCount, int selectedRole, boolean editingDrops,
                                   int[] dropMinimumCounts, int[] dropMaximumCounts, int[] dropRarityLevels)
        implements CustomPacketPayload {
    public static final Type<SyncMonumentAiPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_monument_ai"));

    public static final StreamCodec<FriendlyByteBuf, SyncMonumentAiPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeUUID(packet.monumentId);
                for (int index = 0; index < RoguePmcRole.values().length; index++) {
                    buf.writeVarInt(packet.minimumCounts[index]);
                    buf.writeVarInt(packet.maximumCounts[index]);
                    buf.writeVarInt(packet.difficultyLevels[index]);
                }
                buf.writeVarInt(packet.deadCount);
                buf.writeVarInt(packet.selectedRole);
                buf.writeBoolean(packet.editingDrops);
                for (int index = 0; index < SupplyDropPool.SLOT_COUNT; index++) {
                    buf.writeVarInt(packet.dropMinimumCounts[index]);
                    buf.writeVarInt(packet.dropMaximumCounts[index]);
                    buf.writeVarInt(packet.dropRarityLevels[index]);
                }
            },
            buf -> {
                UUID monumentId = buf.readUUID();
                int[] minimum = new int[RoguePmcRole.values().length];
                int[] maximum = new int[RoguePmcRole.values().length];
                int[] difficulty = new int[RoguePmcRole.values().length];
                for (int index = 0; index < minimum.length; index++) {
                    minimum[index] = buf.readVarInt();
                    maximum[index] = buf.readVarInt();
                    difficulty[index] = buf.readVarInt();
                }
                int deadCount = buf.readVarInt();
                int selectedRole = buf.readVarInt();
                boolean editingDrops = buf.readBoolean();
                int[] dropMinimum = new int[SupplyDropPool.SLOT_COUNT];
                int[] dropMaximum = new int[SupplyDropPool.SLOT_COUNT];
                int[] dropRarity = new int[SupplyDropPool.SLOT_COUNT];
                for (int index = 0; index < SupplyDropPool.SLOT_COUNT; index++) {
                    dropMinimum[index] = buf.readVarInt();
                    dropMaximum[index] = buf.readVarInt();
                    dropRarity[index] = buf.readVarInt();
                }
                return new SyncMonumentAiPacket(monumentId, minimum, maximum, difficulty, deadCount, selectedRole,
                        editingDrops, dropMinimum, dropMaximum, dropRarity);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncMonumentAiPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().player == null
                    || !(Minecraft.getInstance().player.containerMenu instanceof MonumentMenu menu)) return;
            menu.updateAiEditor(packet.monumentId, packet.minimumCounts, packet.maximumCounts, packet.difficultyLevels,
                    packet.deadCount, packet.selectedRole, packet.editingDrops,
                    packet.dropMinimumCounts, packet.dropMaximumCounts, packet.dropRarityLevels);
            if (Minecraft.getInstance().screen instanceof MonumentScreen screen) screen.refreshContent();
        });
    }
}