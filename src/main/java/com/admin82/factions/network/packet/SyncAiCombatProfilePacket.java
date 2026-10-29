package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.entity.monument.AiCombatProfile;
import com.admin82.factions.menu.AiCombatProfileMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SyncAiCombatProfilePacket(int role, int difficulty, AiCombatProfile profile)
        implements CustomPacketPayload {
    public static final Type<SyncAiCombatProfilePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_ai_combat_profile"));
    public static final StreamCodec<FriendlyByteBuf, SyncAiCombatProfilePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.role);
                buffer.writeVarInt(packet.difficulty);
                buffer.writeDouble(packet.profile.maximumConeDegrees());
                buffer.writeVarInt(packet.profile.lockOnTicks());
                buffer.writeDouble(packet.profile.combatRange());
                buffer.writeDouble(packet.profile.minimumRange());
                buffer.writeDouble(packet.profile.preferredRange());
                buffer.writeDouble(packet.profile.rushRange());
                buffer.writeDouble(packet.profile.coverRange());
                buffer.writeDouble(packet.profile.fireThreshold());
                buffer.writeVarInt(packet.profile.shotCooldownTicks());
            },
            buffer -> new SyncAiCombatProfilePacket(buffer.readVarInt(), buffer.readVarInt(),
                    new AiCombatProfile(buffer.readDouble(), buffer.readVarInt(), buffer.readDouble(), buffer.readDouble(),
                            buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readVarInt())));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncAiCombatProfilePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.containerMenu instanceof AiCombatProfileMenu menu) {
                menu.updateProfile(packet.role, packet.difficulty, packet.profile);
            }
        });
    }
}