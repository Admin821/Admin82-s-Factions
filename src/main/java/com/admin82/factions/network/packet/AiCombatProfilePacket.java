package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.entity.monument.AiCombatProfile;
import com.admin82.factions.entity.monument.AiCombatProfileData;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.menu.AiCombatProfileMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record AiCombatProfilePacket(int role, int difficulty, AiCombatProfile profile)
        implements CustomPacketPayload {
    public static final Type<AiCombatProfilePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "ai_combat_profile"));

    public static final StreamCodec<FriendlyByteBuf, AiCombatProfilePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.role);
                buffer.writeVarInt(packet.difficulty);
                writeProfile(buffer, packet.profile);
            },
            buffer -> new AiCombatProfilePacket(buffer.readVarInt(), buffer.readVarInt(), readProfile(buffer)));

    private static void writeProfile(FriendlyByteBuf buffer, AiCombatProfile profile) {
        buffer.writeDouble(profile.maximumConeDegrees());
        buffer.writeVarInt(profile.lockOnTicks());
        buffer.writeDouble(profile.combatRange());
        buffer.writeDouble(profile.minimumRange());
        buffer.writeDouble(profile.preferredRange());
        buffer.writeDouble(profile.rushRange());
        buffer.writeDouble(profile.coverRange());
        buffer.writeDouble(profile.fireThreshold());
        buffer.writeVarInt(profile.shotCooldownTicks());
    }

    private static AiCombatProfile readProfile(FriendlyByteBuf buffer) {
        return new AiCombatProfile(buffer.readDouble(), buffer.readVarInt(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readVarInt());
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(AiCombatProfilePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)
                    || !(player.containerMenu instanceof AiCombatProfileMenu)) return;
            if (packet.role < 0 || packet.role >= RoguePmcRole.values().length
                    || packet.difficulty < 1 || packet.difficulty > AiCombatProfileData.DIFFICULTY_COUNT) return;
            AiCombatProfile validated = packet.profile.validated();
            AiCombatProfileData data = AiCombatProfileData.get(player.server);
            data.setProfile(RoguePmcRole.values()[packet.role], packet.difficulty, validated);
            PacketDistributor.sendToPlayer(player, new SyncAiCombatProfilePacket(packet.role, packet.difficulty, validated));
        });
    }
}