package com.admin82.factions.command;

import com.admin82.factions.blockentity.BarracksBlockEntity;
import com.admin82.factions.faction.*;
import com.admin82.factions.faction.FactionCombatEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;

import static com.admin82.factions.util.BypassManager.toggleBypass;

/** /factionreturn and its settings. */
final class ReturnCommand {

    private ReturnCommand() {}

    static int cmdReturnCooldownTime(CommandSourceStack src, int seconds) {
        FactionManager.get(src.getServer()).setFactionReturnCooldownSeconds(seconds);
        FactionCommands.factionReturnCooldowns.clear();
        int cooldownSeconds = FactionManager.get(src.getServer()).getFactionReturnCooldownSeconds();
        src.sendSuccess(() -> Component.literal("§aFaction return cooldown set to §e"
            + cooldownSeconds + "s§a."), true);
        return cooldownSeconds;
    }

    static int cmdReturnCombatTime(CommandSourceStack src, int seconds) {
        FactionManager.get(src.getServer()).setFactionReturnCombatSeconds(seconds);
        FactionCombatEvents.clearReturnCombatLocks();
        int combatSeconds = FactionManager.get(src.getServer()).getFactionReturnCombatSeconds();
        src.sendSuccess(() -> Component.literal("§aFaction return combat lock set to §e"
                + combatSeconds + "s§a."), true);
        return combatSeconds;
    }

    static int cmdReturnToBase(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("§cThis command can only be used by players."));
            return 0;
        }
        FactionManager fmgr = FactionManager.get(src.getServer());
        Faction faction = fmgr.getFactionForPlayer(player.getUUID());
        if (faction == null) { src.sendFailure(Component.literal("§cYou are not in a faction.")); return 0; }
        FactionManager.TableLocation barracks = fmgr.getFactionBarracks(faction.getId());
        if (barracks == null) { src.sendFailure(Component.literal("§cYour faction has no barracks.")); return 0; }
        long combatSecondsLeft = FactionCombatEvents.getReturnCombatRemainingSeconds(player);
        if (combatSecondsLeft > 0) {
            src.sendFailure(Component.literal("§cYou cannot use §f/factionreturn §cwhile in player combat. Wait §e"
                + combatSecondsLeft + "s§c."));
            return 0;
        }
        long now = System.currentTimeMillis();
        int cooldownSeconds = fmgr.getFactionReturnCooldownSeconds();
        Long cooldownUntil = FactionCommands.factionReturnCooldowns.get(player.getUUID());
        if (cooldownSeconds > 0 && cooldownUntil != null && now < cooldownUntil) {
            long secondsLeft = Math.max(1L, (cooldownUntil - now + 999L) / 1000L);
            src.sendFailure(Component.literal("§cYou can use §f/factionreturn §cagain in §e"
                    + secondsLeft + "s§c."));
            return 0;
        }
        ServerLevel targetLevel = null;
        for (ServerLevel lvl : src.getServer().getAllLevels()) {
            if (lvl.dimension().location().toString().equals(barracks.dimension())) { targetLevel = lvl; break; }
        }
        if (targetLevel == null) {
            fmgr.removeFactionBarracks(faction.getId());
            src.sendFailure(Component.literal("§cYour faction has no barracks."));
            return 0;
        }
        BlockPos bp = barracks.pos();
        targetLevel.getChunkSource().getChunk(
                SectionPos.blockToSectionCoord(bp.getX()),
                SectionPos.blockToSectionCoord(bp.getZ()), true);
        if (!(targetLevel.getBlockEntity(bp) instanceof BarracksBlockEntity barracksBe)
                || !faction.getId().equals(barracksBe.getLinkedFactionId())) {
            fmgr.removeFactionBarracks(faction.getId());
            src.sendFailure(Component.literal("§cYour faction has no barracks."));
            return 0;
        }
        BlockPos safePos = findSafeReturnPos(targetLevel, bp);
        if (safePos == null) {
            src.sendFailure(Component.literal("§cCould not find a safe place near your barracks."));
            return 0;
        }
        final ServerLevel fl = targetLevel;
        player.teleportTo(fl, safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        if (cooldownSeconds > 0) {
            FactionCommands.factionReturnCooldowns.put(player.getUUID(), now + (long) cooldownSeconds * 1000L);
        } else {
            FactionCommands.factionReturnCooldowns.remove(player.getUUID());
        }
        src.sendSuccess(() -> Component.literal("§a✔ Teleported to §e" + faction.getName() + " §aBarracks!"), false);
        return 1;
    }

    @Nullable
    private static BlockPos findSafeReturnPos(ServerLevel level, BlockPos barracksPos) {
        int[][] preferredOffsets = {
                {0, 2}, {1, 2}, {-1, 2}, {2, 0}, {-2, 0}, {0, -2},
                {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0, 0}
        };
        for (int[] offset : preferredOffsets) {
            BlockPos pos = safeColumn(level, barracksPos.getX() + offset[0], barracksPos.getZ() + offset[1]);
            if (pos != null) return pos;
        }

        for (int radius = 3; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.abs(dx) != radius && Math.abs(dz) != radius) continue;
                    BlockPos pos = safeColumn(level, barracksPos.getX() + dx, barracksPos.getZ() + dz);
                    if (pos != null) return pos;
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos safeColumn(ServerLevel level, int x, int z) {
        if (!level.getWorldBorder().isWithinBounds(x, z)) return null;
        level.getChunkSource().getChunk(
                SectionPos.blockToSectionCoord(x),
                SectionPos.blockToSectionCoord(z), true);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight() || y >= level.getMaxBuildHeight() - 1) return null;

        BlockPos feet = new BlockPos(x, y, z);
        BlockPos floor = feet.below();
        if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) return null;
        if (!level.getBlockState(floor).isFaceSturdy(level, floor, Direction.UP)) return null;
        if (!level.getFluidState(feet).isEmpty() || !level.getFluidState(floor).isEmpty()) return null;
        return feet;
    }
}
