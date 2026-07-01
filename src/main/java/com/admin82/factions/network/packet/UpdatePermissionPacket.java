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

public record UpdatePermissionPacket(String permissionKey, boolean value) implements CustomPacketPayload {

    public static final Type<UpdatePermissionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "update_permission")
    );

    public static final StreamCodec<FriendlyByteBuf, UpdatePermissionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUtf(pkt.permissionKey());
                buf.writeBoolean(pkt.value());
            },
            buf -> new UpdatePermissionPacket(buf.readUtf(64), buf.readBoolean())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UpdatePermissionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null) return;

            FactionMember member = faction.getMember(player.getUUID());
            if (member == null || member.getRole() != FactionRole.OWNER) return;

            FactionPermission perm = FactionPermission.fromKey(packet.permissionKey());
            if (perm == null) return;

            faction.setPermission(perm, packet.value());
            manager.setDirty();
            PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(faction));
        });
    }
}
