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

public record UpdateRolePermissionPacket(String roleId, String permissionKey, boolean value)
        implements CustomPacketPayload {

    public static final Type<UpdateRolePermissionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "update_role_permission")
    );

    public static final StreamCodec<FriendlyByteBuf, UpdateRolePermissionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUtf(pkt.roleId());
                buf.writeUtf(pkt.permissionKey());
                buf.writeBoolean(pkt.value());
            },
            buf -> new UpdateRolePermissionPacket(buf.readUtf(20), buf.readUtf(64), buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UpdateRolePermissionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null) return;

            FactionMember member = faction.getMember(player.getUUID());
            if (member == null || member.getRole() != FactionRole.OWNER) return;

            FactionRole role = FactionRole.fromId(packet.roleId());
            if (role == FactionRole.OWNER) return;

            FactionPermission perm = FactionPermission.fromKey(packet.permissionKey());
            if (perm == null) return;

            faction.setRolePermission(role, perm, packet.value());
            manager.setDirty();
            PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(faction));
        });
    }
}
