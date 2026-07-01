package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record DeclareWarPacket(String targetFactionName) implements CustomPacketPayload {

    public static final Type<DeclareWarPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "declare_war")
    );

    public static final StreamCodec<FriendlyByteBuf, DeclareWarPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeUtf(pkt.targetFactionName()),
            buf -> new DeclareWarPacket(buf.readUtf(64))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(DeclareWarPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction attacker = manager.getFactionForPlayer(player.getUUID());
            if (attacker == null) return;

            FactionMember member = attacker.getMember(player.getUUID());
            if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) return;

            Faction defender = manager.getFactionByName(packet.targetFactionName().trim());
            if (defender == null || defender.getId().equals(attacker.getId())) return;

            manager.declareWar(attacker.getId(), defender.getId());
            PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(manager.getFactionForPlayer(player.getUUID())));
        });
    }
}
