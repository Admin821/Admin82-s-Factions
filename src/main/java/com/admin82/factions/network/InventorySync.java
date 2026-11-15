package com.admin82.factions.network;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Sends a player's inventory after the server changed it.
 * <p>
 * {@code inventoryMenu.broadcastChanges()} alone isn't enough while another screen is open: the client only applies
 * the player-inventory updates for hotbar slots then, so items taken from (or added to) the three main rows would
 * still show on the client. Most of this mod's screens draw the inventory themselves without slots of their own, so
 * here every slot is also sent directly (container -2, which the client always applies).
 */
public final class InventorySync {

    private InventorySync() {}

    public static void push(Player player) {
        if (!(player instanceof ServerPlayer sp)) return;
        sp.getInventory().setChanged();
        sp.inventoryMenu.broadcastChanges();
        if (sp.containerMenu == sp.inventoryMenu) return;
        var items = sp.getInventory().items;
        for (int i = 0; i < items.size(); i++) {
            sp.connection.send(new ClientboundContainerSetSlotPacket(-2, 0, i, items.get(i).copy()));
        }
    }
}
