package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import com.admin82.factions.item.TemporaryMoveItem;
import com.admin82.factions.registry.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent client→server when the faction owner clicks "Move Barracks" in the Kit Manager.
 * Records a pending barracks-move entry and gives the player a Barracks item.
 * Placing the item at the new location completes the move (BarracksBlock.setPlacedBy).
 */
public record RequestMoveBarracksPacket() implements CustomPacketPayload {

    public static final Type<RequestMoveBarracksPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "request_move_barracks")
    );

    public static final StreamCodec<FriendlyByteBuf, RequestMoveBarracksPacket> STREAM_CODEC =
            StreamCodec.unit(new RequestMoveBarracksPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestMoveBarracksPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null) return;

            FactionMember member = faction.getMember(player.getUUID());
            if (member == null || member.getRole() != FactionRole.OWNER) return;

            if (manager.getPendingBarracksMove(player.getUUID()) != null) {
                player.displayClientMessage(
                        Component.literal("§eAlready in barracks move mode! Place a new Barracks to complete it."), false);
                return;
            }

            FactionManager.TableLocation barracks = manager.getFactionBarracks(faction.getId());
            if (barracks == null) {
                player.displayClientMessage(
                        Component.literal("§cYour faction does not have a registered Barracks."), false);
                return;
            }

            manager.startPendingBarracksMove(
                    player.getUUID(), faction.getId(), barracks.pos(), barracks.dimension());

                        TemporaryMoveItem.removeAll(player, ModItems.BARRACKS.get());
                        player.getInventory().add(TemporaryMoveItem.create(ModItems.BARRACKS.get(), "Barracks"));

            player.displayClientMessage(
                    Component.literal(
                            "§eBARRACKS MOVE MODE ACTIVE — Place a Barracks in a claimed chunk to relocate it.\n" +
                            "§7Move mode cancels on logout, death, or dimension change."), false);
        });
    }
}
