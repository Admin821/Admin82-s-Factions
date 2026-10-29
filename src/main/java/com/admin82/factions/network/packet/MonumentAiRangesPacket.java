package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record MonumentAiRangesPacket(UUID monumentId, int[] minimumCounts, int[] maximumCounts)
        implements CustomPacketPayload {
    public static final Type<MonumentAiRangesPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "monument_ai_ranges"));

    public static final StreamCodec<FriendlyByteBuf, MonumentAiRangesPacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeUUID(packet.monumentId);
                for (RoguePmcRole role : RoguePmcRole.values()) {
                    int index = role.ordinal();
                    buffer.writeVarInt(valueAt(packet.minimumCounts, index));
                    buffer.writeVarInt(valueAt(packet.maximumCounts, index));
                }
            },
            buffer -> {
                int roleCount = RoguePmcRole.values().length;
                UUID monumentId = buffer.readUUID();
                int[] minimumCounts = new int[roleCount];
                int[] maximumCounts = new int[roleCount];
                for (int index = 0; index < roleCount; index++) {
                    minimumCounts[index] = buffer.readVarInt();
                    maximumCounts[index] = buffer.readVarInt();
                }
                return new MonumentAiRangesPacket(monumentId, minimumCounts, maximumCounts);
            });

    private static int valueAt(int[] values, int index) {
        return index < values.length ? values[index] : 0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MonumentAiRangesPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)
                    || !(player.containerMenu instanceof MonumentMenu menu)
                    || !packet.monumentId.equals(menu.getSelectedId())) return;
            MonumentData data = MonumentData.get(player.server);
            MonumentEntry monument = data.get(packet.monumentId);
            if (monument == null) return;

            for (RoguePmcRole role : RoguePmcRole.values()) {
                int index = role.ordinal();
                monument.setAiCountRange(role, valueAt(packet.minimumCounts, index),
                        valueAt(packet.maximumCounts, index));
            }
            data.changed();
            menu.syncAiEditor(monument);
        });
    }
}