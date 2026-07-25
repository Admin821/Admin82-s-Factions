package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.SupplyDropMenu;
import com.admin82.factions.supplydrop.SupplyDropData;
import com.admin82.factions.supplydrop.SupplyDropEvents;
import com.admin82.factions.supplydrop.SupplyDropPool;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.List;

public record SupplyDropActionPacket(Action action, @Nullable String data, int radius, int fallSeconds,
                                     int maxCount, int rarity) implements CustomPacketPayload {
    public enum Action { CREATE_POOL, DELETE_POOL, LOAD_POOL, SPAWN_DROP, SAVE_SLOT_SETTINGS, SET_SCHEDULE, CLEAR_SCHEDULE, REQUEST_SYNC }

    public SupplyDropActionPacket(Action action, @Nullable String data, int radius, int fallSeconds) {
        this(action, data, radius, fallSeconds, 0, 0);
    }

    public static final Type<SupplyDropActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "supply_drop_action"));

    public static final StreamCodec<FriendlyByteBuf, SupplyDropActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.action().ordinal());
                buf.writeBoolean(packet.data() != null);
                if (packet.data() != null) buf.writeUtf(packet.data(), 64);
                buf.writeVarInt(packet.radius());
                buf.writeVarInt(packet.fallSeconds());
                buf.writeVarInt(packet.maxCount());
                buf.writeVarInt(packet.rarity());
            },
            buf -> {
                Action action = Action.values()[buf.readVarInt()];
                String data = buf.readBoolean() ? buf.readUtf(64) : null;
                int radius = buf.readVarInt();
                int fallSeconds = buf.readVarInt();
                int maxCount = buf.readVarInt();
                int rarity = buf.readVarInt();
                return new SupplyDropActionPacket(action, data, radius, fallSeconds, maxCount, rarity);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SupplyDropActionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || !player.hasPermissions(2)) return;
            var server = player.getServer();
            if (server == null) return;
            SupplyDropData data = SupplyDropData.get(server);

            switch (packet.action()) {
                case CREATE_POOL -> {
                    String name = packet.data();
                    if (!SupplyDropData.isValidName(name)) return;
                    if (data.createPool(name)) syncToPlayer(player, data);
                    else player.displayClientMessage(Component.literal("§cSupply drop pool name already exists."), true);
                }
                case DELETE_POOL -> {
                    if (packet.data() == null) return;
                    data.deletePool(packet.data());
                    if (player.containerMenu instanceof SupplyDropMenu menu && packet.data().equals(menu.getCurrentEditingPoolName())) {
                        menu.serverClearStaging();
                    }
                    syncToPlayer(player, data);
                }
                case LOAD_POOL -> {
                    if (packet.data() == null || !(player.containerMenu instanceof SupplyDropMenu menu)) return;
                    menu.serverLoadPool(packet.data());
                    syncSettingsToPlayer(player, data, packet.data());
                }
                case SPAWN_DROP -> {
                    if (packet.data() == null) return;
                    boolean spawned = SupplyDropEvents.callSupplyDrop(server, player.serverLevel(), packet.data(), packet.radius(), packet.fallSeconds());
                    if (!spawned) player.displayClientMessage(Component.literal("§cSupply drop pool is missing or empty."), true);
                }
                case SAVE_SLOT_SETTINGS -> {
                    if (packet.data() == null || !(player.containerMenu instanceof SupplyDropMenu menu)) return;
                    if (!packet.data().equals(menu.getCurrentEditingPoolName())) return;
                    data.savePoolSlotSettings(packet.data(), packet.radius(), packet.fallSeconds(), packet.maxCount(), packet.rarity());
                    syncSettingsToPlayer(player, data, packet.data());
                }
                case SET_SCHEDULE -> {
                    if (packet.data() == null || packet.maxCount() <= 0) return;
                    SupplyDropPool pool = data.getPool(packet.data());
                    if (pool == null || pool.nonEmptyItems().isEmpty()) {
                        player.displayClientMessage(Component.literal("§cScheduled loot pool is missing or empty."), true);
                        return;
                    }
                    data.setSchedule(packet.data(), packet.maxCount(), packet.radius(), packet.fallSeconds(),
                            System.currentTimeMillis());
                    syncToPlayer(player, data);
                    player.displayClientMessage(Component.literal("§aSupply drop schedule saved."), true);
                }
                case CLEAR_SCHEDULE -> {
                    data.clearSchedule();
                    syncToPlayer(player, data);
                    player.displayClientMessage(Component.literal("§eSupply drop schedule disabled."), true);
                }
                case REQUEST_SYNC -> syncToPlayer(player, data);
            }
        });
    }

    private static void syncToPlayer(ServerPlayer player, SupplyDropData data) {
        List<String> names = data.getPoolNames();
        PacketDistributor.sendToPlayer(player, new SyncSupplyDropPacket(
                names, data.getScheduledPoolName(), data.getScheduleIntervalHours(), data.getScheduleRadius(),
                data.getScheduleFallSeconds(), data.getNextScheduledDropAt()));
    }

    private static void syncSettingsToPlayer(ServerPlayer player, SupplyDropData data, String poolName) {
        SupplyDropPool pool = data.getPool(poolName);
        if (pool == null) return;
        PacketDistributor.sendToPlayer(player, new SyncSupplyDropSettingsPacket(
                poolName, pool.getMinCountsCopy(), pool.getMaxCountsCopy(), pool.getRarityLevelsCopy()));
    }
}