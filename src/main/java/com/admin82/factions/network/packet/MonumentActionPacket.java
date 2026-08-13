package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.monument.MonumentView;
import com.admin82.factions.faction.FactionManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record MonumentActionPacket(Action action, UUID monumentId, String name,
                                   int tier, int radius, int respawnSeconds) implements CustomPacketPayload {
    public enum Action { SELECT, SAVE, REFILL, DELETE, TOGGLE_CHUNK }

    public static final Type<MonumentActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "monument_action"));

    public static final StreamCodec<FriendlyByteBuf, MonumentActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.action.ordinal());
                buf.writeUUID(packet.monumentId);
                buf.writeUtf(packet.name, 64);
                buf.writeVarInt(packet.tier);
                buf.writeVarInt(packet.radius);
                buf.writeVarInt(packet.respawnSeconds);
            },
            buf -> new MonumentActionPacket(Action.values()[buf.readVarInt()], buf.readUUID(), buf.readUtf(64),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(MonumentActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !player.hasPermissions(2)
                    || !(player.containerMenu instanceof MonumentMenu menu)) return;
            MonumentData data = MonumentData.get(player.server);
            MonumentEntry monument = data.get(packet.monumentId);
            if (monument == null) {
                sync(player, data, null);
                return;
            }

            switch (packet.action) {
                case SELECT -> {
                    menu.serverSelect(monument.id);
                    sync(player, data, monument.id);
                }
                case SAVE -> {
                    String cleanName = packet.name.trim();
                    MonumentEntry existing = data.getByName(cleanName);
                    if (cleanName.isEmpty() || cleanName.length() > 64 || existing != null && existing != monument) {
                        player.displayClientMessage(Component.literal("§cThat monument name is empty or already in use."), true);
                        return;
                    }
                    monument.setName(cleanName);
                    monument.setTier(packet.tier);
                    monument.setRadius(packet.radius);
                    monument.setBaseRespawnTicks(Math.max(10, packet.respawnSeconds) * 20L);
                    data.changed();
                    sync(player, data, monument.id);
                    player.displayClientMessage(Component.literal("§aMonument settings saved."), true);
                }
                case REFILL -> {
                    monument.setRemainingRespawnTicks(0.0);
                    data.changed();
                    sync(player, data, monument.id);
                    player.displayClientMessage(Component.literal("§aLoot will refill as soon as the monument is unoccupied."), true);
                }
                case DELETE -> {
                    removeBlocks(player, monument);
                    data.remove(monument.id);
                    menu.serverClearSelection();
                    sync(player, data, null);
                    player.displayClientMessage(Component.literal("§aMonument deleted."), true);
                }
                case TOGGLE_CHUNK -> {
                    int chunkX = packet.tier;
                    int chunkZ = packet.radius;
                    int controllerChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(monument.controllerPos.getX());
                    int controllerChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(monument.controllerPos.getZ());
                    if (Math.abs(chunkX - controllerChunkX) > 64 || Math.abs(chunkZ - controllerChunkZ) > 64) {
                        player.displayClientMessage(Component.literal("§cMonument chunks must be within 64 chunks of the controller."), true);
                        return;
                    }
                    MonumentEntry owner = data.getByChunk(chunkX, chunkZ, monument.dimension);
                    if (owner != null && owner != monument) {
                        player.displayClientMessage(Component.literal("§cThat chunk already belongs to §e" + owner.getName() + "§c."), true);
                        return;
                    }
                    if (monument.hasChunk(chunkX, chunkZ)) {
                        boolean containsCrate = monument.getCrates().keySet().stream().anyMatch(pos ->
                                net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()) == chunkX
                                        && net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ()) == chunkZ);
                        boolean containsGenerator = monument.getOreGenerators().keySet().stream().anyMatch(pos ->
                                net.minecraft.core.SectionPos.blockToSectionCoord(pos.getX()) == chunkX
                                        && net.minecraft.core.SectionPos.blockToSectionCoord(pos.getZ()) == chunkZ);
                        if (containsCrate || containsGenerator) {
                            player.displayClientMessage(Component.literal(
                                    "§cRemove linked crates and ore generators before removing this chunk."), true);
                            return;
                        }
                    }
                    if (!monument.toggleChunk(chunkX, chunkZ)) {
                        player.displayClientMessage(Component.literal("§cThe controller chunk must remain part of its monument."), true);
                        return;
                    }
                    data.changed();
                    sync(player, data, monument.id);
                }
            }
        });
    }

    private static void removeBlocks(ServerPlayer player, MonumentEntry monument) {
        ResourceLocation id = ResourceLocation.tryParse(monument.dimension);
        ServerLevel level = id == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) return;
        monument.getCrates().keySet().forEach(pos -> level.removeBlock(pos, false));
        monument.getOreGenerators().keySet().forEach(pos -> level.removeBlock(pos, false));
        level.removeBlock(monument.controllerPos, false);
    }

    public static void sync(ServerPlayer player, MonumentData data, UUID selectedId) {
        FactionManager factions = FactionManager.get(player.server);
        List<MonumentView> views = data.getAll().stream().map(entry ->
                MonumentView.from(entry, factions, entry.id.equals(selectedId)))
                .sorted(Comparator.comparing(MonumentView::name, String.CASE_INSENSITIVE_ORDER)).toList();
        if (player.containerMenu instanceof MonumentMenu menu) menu.updateViews(views, selectedId);
        PacketDistributor.sendToPlayer(player, new SyncMonumentsPacket(views, selectedId));
    }
}