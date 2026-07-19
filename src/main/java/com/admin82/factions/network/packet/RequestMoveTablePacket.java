package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import com.admin82.factions.item.TemporaryMoveItem;
import com.admin82.factions.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent client→server when the faction owner clicks "Move Table" in the GUI.
 * The server records a pending-move entry; the player then places a new
 * Faction Table which completes the move (handled in FactionTableBlock.setPlacedBy).
 */
public record RequestMoveTablePacket() implements CustomPacketPayload {

    public static final Type<RequestMoveTablePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "request_move_table")
    );

    public static final StreamCodec<FriendlyByteBuf, RequestMoveTablePacket> STREAM_CODEC =
            StreamCodec.unit(new RequestMoveTablePacket());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestMoveTablePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null) return;

            FactionMember member = faction.getMember(player.getUUID());
            if (member == null || member.getRole() != FactionRole.OWNER) return;

            if (manager.getPendingMove(player.getUUID()) != null) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§eYou are already in move mode! Place a new Faction Table to complete it."), false);
                return;
            }

            FactionManager.TableLocation table = manager.getFactionTable(faction.getId());
            if (table == null) return;

            manager.startPendingMove(player.getUUID(), faction.getId(), table.pos(), table.dimension());

            TemporaryMoveItem.removeAll(player, ModItems.FACTION_TABLE.get());
            player.getInventory().add(TemporaryMoveItem.create(ModItems.FACTION_TABLE.get(), "Faction Table"));

            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "§eMOVE MODE ACTIVE — Place a new Faction Table at the desired location.\n" +
                            "§7Move mode will cancel on logout, death, or dimension change."), false);
        });
    }
}
