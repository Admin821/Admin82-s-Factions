package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.barracks.BarracksData;
import com.admin82.factions.barracks.KitData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → Server: player has chosen a kit from the selection screen.
 * Server validates, gives items, removes the kit from BarracksData, and
 * if the kit was gone (race), re-sends an updated OpenKitSelectionPacket.
 */
public record SelectKitPacket(String kitName) implements CustomPacketPayload {

    public static final Type<SelectKitPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "select_kit"));

    public static final StreamCodec<FriendlyByteBuf, SelectKitPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeUtf(pkt.kitName(), 64),
                    buf -> new SelectKitPacket(buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SelectKitPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            var server = sp.getServer();
            if (server == null) return;
            var ownerId = sp.getUUID();

            BarracksData bData = BarracksData.get(server);
            KitData kit = bData.getKit(ownerId, pkt.kitName());

            if (kit == null) {
                List<KitData> remaining = new ArrayList<>(bData.getKits(ownerId));
                if (remaining.isEmpty()) {
                    sp.displayClientMessage(
                            Component.literal("§cThat kit was already taken and no others remain."), true);
                } else {
                    sp.displayClientMessage(
                            Component.literal("§e⚠ That kit was taken! Choose another:"), true);
                    PacketDistributor.sendToPlayer(sp, OpenKitSelectionPacket.fromKits(remaining));
                }
                return;
            }

            // Give items to player
            giveKitToPlayer(sp, kit);

            // Consume (delete) the kit
            bData.deleteKit(ownerId, pkt.kitName());

            // Sync updated private kit list to this player (if Barracks GUI is open).
            List<String> names = bData.getKits(ownerId).stream().map(KitData::getName).toList();
            PacketDistributor.sendToPlayer(sp, new SyncBarracksPacket(names));

            sp.displayClientMessage(
                    Component.literal("§a▶ Kit received: §e" + pkt.kitName()), false);
        });
    }

    private static void giveKitToPlayer(ServerPlayer player, KitData kit) {
        EquipmentSlot[] armorSlots = {
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        };
        // Armor slots (indices 36-39)
        for (int i = 0; i < KitData.ARMOR_SLOTS; i++) {
            ItemStack armor = kit.getSlot(KitData.INV_SLOTS + i);
            if (armor.isEmpty()) continue;
            EquipmentSlot eSlot = armorSlots[i];
            if (player.getItemBySlot(eSlot).isEmpty()) {
                player.setItemSlot(eSlot, armor.copy());
            } else {
                if (!player.getInventory().add(armor.copy())) player.drop(armor.copy(), false);
            }
        }
        // Inventory slots (0-35)
        for (int i = 0; i < KitData.INV_SLOTS; i++) {
            ItemStack item = kit.getSlot(i);
            if (item.isEmpty()) continue;
            if (!player.getInventory().add(item.copy())) player.drop(item.copy(), false);
        }
        // Offhand slot
        ItemStack offhand = kit.getSlot(KitData.OFFHAND_SLOT);
        if (!offhand.isEmpty()) {
            if (player.getOffhandItem().isEmpty()) {
                player.setItemSlot(EquipmentSlot.OFFHAND, offhand.copy());
            } else {
                if (!player.getInventory().add(offhand.copy())) player.drop(offhand.copy(), false);
            }
        }
        player.inventoryMenu.broadcastChanges();
    }
}
