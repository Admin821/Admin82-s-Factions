package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.entity.monument.AiBehaviorProfile;
import com.admin82.factions.menu.AiCombatProfileMenu;
import com.admin82.factions.screen.AiCombatProfileScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server → client: the saved behavior profile of one guard class. */
public record SyncAiCombatProfilePacket(int role, AiBehaviorProfile profile) implements CustomPacketPayload {
    public static final Type<SyncAiCombatProfilePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_ai_behavior_profile"));

    public static final StreamCodec<FriendlyByteBuf, SyncAiCombatProfilePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.role);
                packet.profile.write(buffer);
            },
            buffer -> new SyncAiCombatProfilePacket(buffer.readVarInt(), AiBehaviorProfile.read(buffer)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncAiCombatProfilePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.containerMenu instanceof AiCombatProfileMenu menu) {
                menu.updateProfile(packet.role, packet.profile);
                if (Minecraft.getInstance().screen instanceof AiCombatProfileScreen screen) screen.refreshContent();
            }
        });
    }
}
