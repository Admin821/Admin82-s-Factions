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

import java.util.UUID;

/**
 * Sent client→server for KICK, SET_ROLE, and INVITE actions on faction members.
 * For INVITE the targetUUID may be zero; the server resolves the UUID from targetName.
 */
public record MemberActionPacket(Action action, UUID targetUUID, String targetName, FactionRole newRole)
        implements CustomPacketPayload {

    public enum Action { INVITE, KICK, SET_ROLE, LEAVE }

    public static final Type<MemberActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "member_action")
    );

    public static final StreamCodec<FriendlyByteBuf, MemberActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUtf(pkt.action().name());
                buf.writeUUID(pkt.targetUUID());
                buf.writeUtf(pkt.targetName());
                buf.writeUtf(pkt.newRole().getId());
            },
            buf -> {
                Action action;
                try { action = Action.valueOf(buf.readUtf(20)); }
                catch (IllegalArgumentException e) { action = Action.KICK; }
                return new MemberActionPacket(action, buf.readUUID(), buf.readUtf(50), FactionRole.fromId(buf.readUtf(20)));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(MemberActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null) return;

            FactionMember actor = faction.getMember(player.getUUID());
            if (actor == null) return;

            switch (packet.action()) {
                case INVITE -> {
                    if (actor.getRole().getLevel() < FactionRole.OFFICER.getLevel()) return;
                    // Resolve the online player by name
                    ServerPlayer target = player.server.getPlayerList().getPlayerByName(packet.targetName());
                    if (target == null) return;
                    if (manager.getFactionForPlayer(target.getUUID()) != null) return;
                    manager.addPlayerToFaction(faction.getId(), target.getUUID(), target.getGameProfile().getName());
                }
                case KICK -> {
                    if (actor.getRole().getLevel() < FactionRole.OFFICER.getLevel()) return;
                    FactionMember target = faction.getMember(packet.targetUUID());
                    if (target == null || target.getRole().getLevel() >= actor.getRole().getLevel()) return;
                    manager.removePlayerFromFaction(packet.targetUUID());
                    // Notify kicked player if online
                    ServerPlayer kicked = player.server.getPlayerList().getPlayer(packet.targetUUID());
                    if (kicked != null) {
                        PacketDistributor.sendToPlayer(kicked, new SyncFactionDataPacket(null));
                    }
                }
                case SET_ROLE -> {
                    if (actor.getRole() != FactionRole.OWNER) return;
                    FactionMember target = faction.getMember(packet.targetUUID());
                    if (target == null || target.getRole() == FactionRole.OWNER) return;
                    FactionRole role = packet.newRole() == FactionRole.OWNER ? FactionRole.OFFICER : packet.newRole();
                    target.setRole(role);
                    manager.setDirty();
                }
                case LEAVE -> {
                    // Player leaves their own faction (owner must disband instead)
                    if (actor.getRole() == FactionRole.OWNER) return;
                    if (!packet.targetUUID().equals(player.getUUID())) return;
                    manager.removePlayerFromFaction(player.getUUID());
                    PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(null));
                    return;
                }
            }

            Faction updated = manager.getFactionForPlayer(player.getUUID());
            PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(updated));
        });
    }
}
