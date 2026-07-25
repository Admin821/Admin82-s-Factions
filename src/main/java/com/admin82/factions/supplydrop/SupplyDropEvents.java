package com.admin82.factions.supplydrop;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.block.CarePackageBlock;
import com.admin82.factions.blockentity.CarePackageBlockEntity;
import com.admin82.factions.entity.SupplyDropVisualEntity;
import com.admin82.factions.registry.ModBlocks;
import com.admin82.factions.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

@EventBusSubscriber(modid = AdminsFactions.MODID)
public class SupplyDropEvents {
    private static final int PARACHUTE_FALL_TICKS = 45 * 20;
    private static final int SCHEDULE_CHECK_INTERVAL_TICKS = 20;

    private static final List<PendingDrop> PENDING_DROPS = new ArrayList<>();
    private static final Random RANDOM = new Random();
    private static int scheduleCheckTicks;

    public static boolean callSupplyDrop(MinecraftServer server, ServerLevel level, String poolName, int radius, int fallSeconds) {
        SupplyDropPool pool = SupplyDropData.get(server).getPool(poolName);
        if (pool == null || pool.nonEmptyItems().isEmpty()) return false;

        int clampedRadius = Math.max(0, radius);
        int x = clampedRadius == 0 ? 0 : RANDOM.nextInt(clampedRadius * 2 + 1) - clampedRadius;
        int z = clampedRadius == 0 ? 0 : RANDOM.nextInt(clampedRadius * 2 + 1) - clampedRadius;
        BlockPos groundPos = findLandingPos(level, x, z);
        loadLandingChunk(level, groundPos);
        int ticks = Math.max(0, fallSeconds) * 20;
        int startY = findStartY(level, groundPos);

        PendingDrop drop = new PendingDrop(level.dimension().location().toString(), groundPos, poolName, ticks, startY);
        PENDING_DROPS.add(drop);
        server.getPlayerList().broadcastSystemMessage(
            Component.literal("§6Supply drop inbound at ")
                .append(clickableCoords(groundPos))
                .append(Component.literal(" §6and will start falling in §e" + formatTime(fallSeconds) + "§6.")),
            false);
        return true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++scheduleCheckTicks >= SCHEDULE_CHECK_INTERVAL_TICKS) {
            scheduleCheckTicks = 0;
            tickSchedule(event.getServer());
        }
        if (PENDING_DROPS.isEmpty()) return;
        MinecraftServer server = event.getServer();
        Iterator<PendingDrop> iterator = PENDING_DROPS.iterator();
        while (iterator.hasNext()) {
            PendingDrop drop = iterator.next();
            ServerLevel level = findLevel(server, drop.dimension);
            if (level == null) {
                iterator.remove();
                continue;
            }
            loadLandingChunk(level, drop.pos);
            if (!drop.falling) {
                if (--drop.countdownTicks > 0) continue;
                drop.falling = true;
                drop.fallTicksRemaining = PARACHUTE_FALL_TICKS;
                spawnDropVisuals(level, drop);
                tickDropVisuals(level, drop);
                server.getPlayerList().broadcastSystemMessage(
                    Component.literal("§6Supply drop is parachuting down at ")
                        .append(clickableCoords(drop.pos))
                        .append(Component.literal("§6.")),
                    false);
                continue;
            }
            if (--drop.fallTicksRemaining > 0) {
                tickDropVisuals(level, drop);
                continue;
            }
            iterator.remove();
            discardDropVisuals(drop);
            landDrop(server, level, drop);
        }
    }

    private static void tickSchedule(MinecraftServer server) {
        SupplyDropData data = SupplyDropData.get(server);
        long now = System.currentTimeMillis();
        if (!data.isScheduleDue(now)) return;

        String poolName = data.getScheduledPoolName();
        if (poolName != null) {
            callSupplyDrop(server, server.overworld(), poolName,
                    data.getScheduleRadius(), data.getScheduleFallSeconds());
        }
        data.advanceSchedule(now);
    }

    private static void spawnDropVisuals(ServerLevel level, PendingDrop drop) {
        drop.crateEntity = spawnDropVisual(level, drop.pos.getX() + 0.5, drop.startY, drop.pos.getZ() + 0.5);
    }

    private static void tickDropVisuals(ServerLevel level, PendingDrop drop) {
        if (drop.totalFallTicks <= 0) return;
        double progress = 1.0 - (drop.fallTicksRemaining / (double) drop.totalFallTicks);
        double easedProgress = 1.0 - Math.pow(1.0 - progress, 1.35);
        double sway = Math.sin(progress * Math.PI * 8.0) * 0.35;
        double drift = Math.cos(progress * Math.PI * 5.0) * 0.20;
        double y = drop.startY + (drop.pos.getY() + 1.0 - drop.startY) * easedProgress;
        double x = drop.pos.getX() + 0.5 + sway;
        double z = drop.pos.getZ() + 0.5 + drift;

        if (drop.crateEntity == null || !drop.crateEntity.isAlive()) {
            drop.crateEntity = spawnDropVisual(level, x, y, z);
        }
        if (drop.crateEntity != null) {
            drop.crateEntity.teleportTo(x, y, z);
            drop.crateEntity.setDeltaMovement(0.0, 0.0, 0.0);
        }
    }

    private static SupplyDropVisualEntity spawnDropVisual(ServerLevel level, double x, double y, double z) {
        SupplyDropVisualEntity entity = ModEntities.SUPPLY_DROP_VISUAL.get().create(level);
        if (entity == null) return null;
        entity.setPos(x, y, z);
        entity.setGlowingTag(true);
        level.addFreshEntity(entity);
        return entity;
    }

    private static void discardDropVisuals(PendingDrop drop) {
        if (drop.crateEntity != null) drop.crateEntity.discard();
    }

    private static void landDrop(MinecraftServer server, ServerLevel level, PendingDrop drop) {
        SupplyDropPool pool = SupplyDropData.get(server).getPool(drop.poolName);
        if (pool == null) return;
        BlockPos pos = findLandingPos(level, drop.pos.getX(), drop.pos.getZ());
        Direction facing = findAvailableFacing(level, pos);
        while (facing == null && pos.getY() < level.getMaxBuildHeight() - 2) {
            pos = pos.above();
            facing = findAvailableFacing(level, pos);
        }
        if (facing == null) return;
        BlockState carePackageState = ModBlocks.CARE_PACKAGE.get().defaultBlockState()
                .setValue(CarePackageBlock.OPEN, false)
                .setValue(CarePackageBlock.FACING, facing);
        level.setBlock(pos, carePackageState, 3);
        CarePackageBlock.placeFiller(level, pos, carePackageState);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof CarePackageBlockEntity carePackage) {
            carePackage.setSupplyDrop(true);
        }
        if (blockEntity instanceof Container container) {
            List<ItemStack> items = new ArrayList<>(pool.generateItems(RANDOM));
            Collections.shuffle(items, RANDOM);
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < container.getContainerSize(); i++) slots.add(i);
            Collections.shuffle(slots, RANDOM);
            for (int i = 0; i < Math.min(slots.size(), items.size()); i++) {
                container.setItem(slots.get(i), items.get(i).copy());
            }
        }
        server.getPlayerList().broadcastSystemMessage(
            Component.literal("§aSupply drop landed at ")
                .append(clickableCoords(pos))
                .append(Component.literal("§a.")),
            false);
    }

    private static Direction findAvailableFacing(ServerLevel level, BlockPos pos) {
        List<Direction> directions = new ArrayList<>(List.of(
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST));
        Collections.shuffle(directions, RANDOM);
        for (Direction direction : directions) {
            if (level.getBlockState(pos.relative(direction.getClockWise())).canBeReplaced()) return direction;
        }
        return null;
    }

    private static void loadLandingChunk(ServerLevel level, BlockPos pos) {
        level.getChunkSource().getChunk(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()),
                true);
    }

    private static BlockPos findLandingPos(ServerLevel level, int x, int z) {
        loadLandingChunk(level, new BlockPos(x, level.getMinBuildHeight(), z));
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight() + 2) {
            y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        }
        if (y <= level.getMinBuildHeight() + 2) {
            y = level.getSeaLevel() + 1;
        }
        return new BlockPos(x, Math.max(level.getMinBuildHeight() + 1, y), z);
    }

    private static Component clickableCoords(BlockPos pos) {
        String coords = pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        String command = "/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        return Component.literal("§e§n" + coords + "§r")
                .withStyle(style -> style
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND,
                                command))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal("§7Operators: click to teleport to this supply drop"))));
    }

    private static int findStartY(ServerLevel level, BlockPos groundPos) {
        int travelHeight = 96;
        return Math.min(level.getMaxBuildHeight() - 5, groundPos.getY() + travelHeight);
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) return level;
        }
        return null;
    }

    private static String formatTime(int seconds) {
        if (seconds < 60) return seconds + "s";
        int minutes = seconds / 60;
        int extraSeconds = seconds % 60;
        return extraSeconds == 0 ? minutes + "m" : minutes + "m " + extraSeconds + "s";
    }

    private static class PendingDrop {
        final String dimension;
        final BlockPos pos;
        final String poolName;
        final int totalFallTicks;
        final int startY;
        int countdownTicks;
        int fallTicksRemaining;
        boolean falling;
        SupplyDropVisualEntity crateEntity;

        PendingDrop(String dimension, BlockPos pos, String poolName, int countdownTicks, int startY) {
            this.dimension = dimension;
            this.pos = pos;
            this.poolName = poolName;
            this.totalFallTicks = PARACHUTE_FALL_TICKS;
            this.countdownTicks = countdownTicks;
            this.fallTicksRemaining = PARACHUTE_FALL_TICKS;
            this.startY = startY;
        }
    }
}