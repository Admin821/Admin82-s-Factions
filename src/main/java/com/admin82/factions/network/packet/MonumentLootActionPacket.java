package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.supplydrop.SupplyDropPool;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record MonumentLootActionPacket(Action action, UUID monumentId, String poolName,
                                       int slot, int minCount, int maxCount, int rarity)
        implements CustomPacketPayload {
    public enum Action { CREATE_POOL, DELETE_POOL, SELECT_POOL, SAVE_SLOT_SETTINGS }

    public static final Type<MonumentLootActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "monument_loot_action"));

    public static final StreamCodec<FriendlyByteBuf, MonumentLootActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.action.ordinal());
                buf.writeUUID(packet.monumentId);
                buf.writeUtf(packet.poolName, 32);
                buf.writeVarInt(packet.slot);
                buf.writeVarInt(packet.minCount);
                buf.writeVarInt(packet.maxCount);
                buf.writeVarInt(packet.rarity);
            },
            buf -> new MonumentLootActionPacket(Action.values()[buf.readVarInt()], buf.readUUID(), buf.readUtf(32),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(MonumentLootActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)
                    || !(player.containerMenu instanceof MonumentMenu menu)
                    || !packet.monumentId.equals(menu.getSelectedId())) return;
            MonumentData data = MonumentData.get(player.server);
            MonumentEntry monument = data.get(packet.monumentId);
            if (monument == null) return;

            switch (packet.action) {
                case CREATE_POOL -> {
                    if (!monument.createLootPool(packet.poolName)) {
                        player.displayClientMessage(Component.literal("§cPool name is invalid, already used, or the 8-pool limit was reached."), true);
                        return;
                    }
                    data.changed();
                    menu.serverLoadLootPool(monument.id, packet.poolName);
                    MonumentActionPacket.sync(player, data, monument.id);
                }
                case DELETE_POOL -> {
                    if (!monument.deleteLootPool(packet.poolName)) {
                        player.displayClientMessage(Component.literal("§cA monument must keep at least one loot pool."), true);
                        return;
                    }
                    data.changed();
                    menu.serverLoadLootPool(monument.id, monument.getLootPoolNames().getFirst());
                    MonumentActionPacket.sync(player, data, monument.id);
                }
                case SELECT_POOL -> menu.serverLoadLootPool(monument.id, packet.poolName);
                case SAVE_SLOT_SETTINGS -> {
                    SupplyDropPool pool = monument.getLootPool(packet.poolName);
                    if (pool == null || !packet.poolName.equalsIgnoreCase(menu.getCurrentLootPoolName())) return;
                    pool.setGenerationSettings(packet.slot, packet.minCount, packet.maxCount, packet.rarity);
                    data.changed();
                    sync(player, monument, pool);
                }
            }
        });
    }

    public static void sync(ServerPlayer player, MonumentEntry monument, SupplyDropPool pool) {
        PacketDistributor.sendToPlayer(player, new SyncMonumentLootPacket(monument.id, pool.getName(),
                monument.getLootPoolNames(), pool.getMinCountsCopy(), pool.getMaxCountsCopy(),
                pool.getRarityLevelsCopy()));
    }
}