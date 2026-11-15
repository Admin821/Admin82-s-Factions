package com.admin82.factions.monument;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.blockentity.MonumentControllerBlockEntity;
import com.admin82.factions.entity.monument.RoguePmcEntity;
import com.admin82.factions.registry.ModBlocks;
import com.admin82.factions.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Moving a monument to a new spot. An operator picks a monument, then the next Monument
 * Controller they place becomes its new controller. Everything linked to the monument
 * (chunks, crate spots, ore generators) moves with it, keeping the same layout around the
 * controller. Mainly for monuments imported from another world.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public final class MonumentMoves {

    /** Operator UUID to the monument they're about to place. Forgotten on logout and when the server stops. */
    private static final Map<UUID, UUID> PENDING = new HashMap<>();

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PENDING.clear();
    }

    private MonumentMoves() {}

    public static void start(ServerPlayer player, MonumentEntry monument) {
        PENDING.put(player.getUUID(), monument.id);
        boolean hasController = player.getInventory().items.stream().anyMatch(s -> s.is(ModItems.MONUMENT_CONTROLLER.get()));
        if (!hasController) player.getInventory().add(new ItemStack(ModItems.MONUMENT_CONTROLLER.get()));
        player.displayClientMessage(Component.literal("§aPlace a §eMonument Controller§a where §e" + monument.getName()
                + "§a should go. Its chunks, crates and generators move with it."), false);
    }

    public static void cancel(ServerPlayer player) {
        if (PENDING.remove(player.getUUID()) != null) {
            player.displayClientMessage(Component.literal("§7Monument move cancelled."), false);
        }
    }

    @Nullable
    public static UUID pending(UUID player) { return PENDING.get(player); }

    /** Called when an operator places a controller. Returns true if it was used for a move. */
    public static boolean tryPlace(ServerPlayer player, ServerLevel level, BlockPos pos) {
        UUID id = PENDING.remove(player.getUUID());
        if (id == null) return false;
        MonumentData data = MonumentData.get(level.getServer());
        MonumentEntry monument = data.get(id);
        if (monument == null) {
            player.displayClientMessage(Component.literal("§cThat monument no longer exists. Placed a new one instead."), false);
            return false;
        }
        MonumentEntry moved = move(level.getServer(), monument, pos, level.dimension().location().toString());
        player.displayClientMessage(Component.literal("§aMoved §e" + moved.getName() + "§a here. "
                + moved.getCrates().size() + " crate spots and " + moved.getOreGenerators().size()
                + " generators kept their place around the controller."), false);
        return true;
    }

    /** Moves a monument so its controller is at {@code newPos}, shifting everything linked to it. */
    public static MonumentEntry move(MinecraftServer server, MonumentEntry monument, BlockPos newPos, String newDimension) {
        BlockPos oldPos = monument.controllerPos;
        String oldDimension = monument.dimension;
        if (oldPos.equals(newPos) && oldDimension.equals(newDimension)) {
            return monument; // placed back on the same spot: nothing moves, and the guards stay as they are
        }
        int dx = newPos.getX() - oldPos.getX(), dy = newPos.getY() - oldPos.getY(), dz = newPos.getZ() - oldPos.getZ();

        CompoundTag tag = monument.save(server.registryAccess());
        tag.putLong("controllerPos", newPos.asLong());
        tag.putString("dimension", newDimension);
        shiftPositions(tag.getList("crates", Tag.TAG_COMPOUND), dx, dy, dz);
        shiftPositions(tag.getList("oreGenerators", Tag.TAG_COMPOUND), dx, dy, dz);
        // Every block keeps its exact place around the controller, so each old chunk is moved by the same
        // block offset. When the offset isn't a whole number of chunks, a moved chunk straddles up to four
        // chunks; all of them are included so no crate or generator ends up outside the monument.
        Set<Long> moved = new LinkedHashSet<>();
        for (Tag raw : tag.getList("designatedChunks", Tag.TAG_LONG)) {
            ChunkPos c = new ChunkPos(((LongTag) raw).getAsLong());
            int minX = SectionPos.blockToSectionCoord(c.getMinBlockX() + dx), maxX = SectionPos.blockToSectionCoord(c.getMaxBlockX() + dx);
            int minZ = SectionPos.blockToSectionCoord(c.getMinBlockZ() + dz), maxZ = SectionPos.blockToSectionCoord(c.getMaxBlockZ() + dz);
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) moved.add(ChunkPos.asLong(x, z));
        }
        ListTag chunks = new ListTag();
        moved.forEach(key -> chunks.add(LongTag.valueOf(key)));
        tag.put("designatedChunks", chunks);
        // Guards start over at the new spot.
        tag.putBoolean("aiGenerationStarted", false);
        for (Tag raw : tag.getList("aiRoles", Tag.TAG_COMPOUND)) ((CompoundTag) raw).putInt("dead", 0);

        MonumentEntry result = MonumentEntry.load(tag, server.registryAccess());
        MonumentData data = MonumentData.get(server);
        data.remove(monument.id);
        data.add(result);

        ServerLevel oldLevel = level(server, oldDimension);
        if (oldLevel != null) {
            // Remove the old controller block if it's loaded, and send the old guards away. Guards in
            // unloaded chunks remove themselves when they load and find they're outside the monument.
            if (oldLevel.isLoaded(oldPos) && oldLevel.getBlockEntity(oldPos) instanceof MonumentControllerBlockEntity) {
                oldLevel.setBlock(oldPos, Blocks.AIR.defaultBlockState(), 3);
            }
            // The old crates and generators belong to the old spot; leaving them would leave linked,
            // unbreakable blocks behind.
            for (BlockPos p : monument.getCrates().keySet()) {
                if (oldLevel.isLoaded(p) && oldLevel.getBlockState(p).is(ModBlocks.MONUMENT_CRATE.get())) {
                    oldLevel.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
                }
            }
            for (BlockPos p : monument.getOreGenerators().keySet()) {
                if (oldLevel.isLoaded(p)) oldLevel.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            }
            oldLevel.getEntities(EntityTypeTest.forClass(RoguePmcEntity.class), e -> monument.id.equals(e.getMonumentId()))
                    .forEach(e -> e.remove(Entity.RemovalReason.DISCARDED));
        }
        return result;
    }

    private static void shiftPositions(ListTag list, int dx, int dy, int dz) {
        for (Tag raw : list) {
            CompoundTag entry = (CompoundTag) raw;
            entry.putLong("pos", BlockPos.of(entry.getLong("pos")).offset(dx, dy, dz).asLong());
        }
    }

    @Nullable
    public static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** Short status for the admin panel: whether the controller block is actually there. */
    public static String status(MinecraftServer server, MonumentEntry monument) {
        ServerLevel level = level(server, monument.dimension);
        if (level == null) return "§cDimension missing";
        if (!level.isLoaded(monument.controllerPos)) return "§7Not loaded";
        return level.getBlockEntity(monument.controllerPos) instanceof MonumentControllerBlockEntity
                ? "§aPlaced" : "§cNo controller block";
    }
}
