package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.entity.monument.AiBehaviorData;
import com.admin82.factions.entity.monument.AiBehaviorProfile;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.menu.AiCombatProfileMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: save the behavior profile of one guard class. */
public record AiCombatProfilePacket(int role, AiBehaviorProfile profile) implements CustomPacketPayload {
    public static final Type<AiCombatProfilePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "ai_behavior_profile"));

    public static final StreamCodec<FriendlyByteBuf, AiCombatProfilePacket> STREAM_CODEC = StreamCodec.of(
            (buffer, packet) -> {
                buffer.writeVarInt(packet.role);
                packet.profile.write(buffer);
            },
            buffer -> new AiCombatProfilePacket(buffer.readVarInt(), AiBehaviorProfile.read(buffer)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(AiCombatProfilePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)
                    || !(player.containerMenu instanceof AiCombatProfileMenu)) return;
            if (packet.role < 0 || packet.role >= RoguePmcRole.values().length) return;
            RoguePmcRole role = RoguePmcRole.values()[packet.role];
            AiBehaviorData data = AiBehaviorData.get(player.server);
            data.setProfile(role, packet.profile);
            PacketDistributor.sendToPlayer(player, new SyncAiCombatProfilePacket(packet.role, data.getProfile(role)));
        });
    }
}
