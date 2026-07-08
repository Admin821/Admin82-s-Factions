package com.admin82.factions;

import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionMember;
import com.admin82.factions.faction.FactionPermission;
import com.admin82.factions.faction.FactionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.UUID;

@EventBusSubscriber(modid = AdminsFactions.MODID)
public class FactionTableEvents {

    /**
     * Prevents any player (except server ops) from manually breaking a linked Faction Table.
     * The only way to remove a linked table is via Disband Faction or the Move Table flow.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(level.getBlockEntity(event.getPos()) instanceof FactionTableBlockEntity be)) return;

        UUID linkedId = be.getLinkedFactionId();
        if (linkedId == null) return; // Unlinked table — breakable normally

        Player player = event.getPlayer();
        if (player.hasPermissions(2)) {
            // Op breaking a linked table: automatically disband the faction
            FactionCommands.performDisband(level.getServer(), linkedId,
                    Component.literal("§cThe Faction Table was destroyed by an admin — faction has been disbanded."));
            return; // Allow the break to proceed
        }

        event.setCanceled(true);
        player.displayClientMessage(
                Component.literal("§cFaction Tables cannot be broken. Use the faction menu to move or disband."), true);
    }

    /** Cancels any in-progress move mode when the player logs out. */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        FactionManager manager = FactionManager.get(player.server);
        manager.clearPendingMove(player.getUUID());
        manager.clearPendingCreation(player.getUUID());
    }

    /** Cancels move mode and pending creation when the player dies. */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        FactionManager manager = FactionManager.get(player.server);
        if (manager.getPendingMove(player.getUUID()) != null) {
            manager.clearPendingMove(player.getUUID());
            player.displayClientMessage(Component.literal("§cMove mode cancelled (you died)."), false);
        }
        manager.clearPendingCreation(player.getUUID());
    }

    /** Cancels move mode and pending creation when the player changes dimension. */
    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        FactionManager manager = FactionManager.get(player.server);
        if (manager.getPendingMove(player.getUUID()) != null) {
            manager.clearPendingMove(player.getUUID());
            player.displayClientMessage(Component.literal("§cMove mode cancelled (dimension change)."), false);
        }
        manager.clearPendingCreation(player.getUUID());
    }

    // ── Land claim protection ──────────────────────────────────────────────────
    //   Upkeep required (same rule as FactionWarEvents.onBlockBreak).
    //   Block-breaking is handled by FactionWarEvents (includes war overrides).

    /**
     * Returns the faction protecting the given position, or {@code null} if unclaimed
     * or the owning faction has no active upkeep (unprotected land).
     */
    private static Faction getProtectedClaimAt(ServerLevel level, BlockPos pos) {
        FactionManager mgr = FactionManager.get(level);
        EconomyManager eco = EconomyManager.get(level.getServer());
        int cx = SectionPos.blockToSectionCoord(pos.getX());
        int cz = SectionPos.blockToSectionCoord(pos.getZ());
        String dim = level.dimension().location().toString();
        for (Faction f : mgr.getAllFactions().values()) {
            if (f.hasClaim(cx, cz, dim) && (eco.hasUpkeep(f.getId()) || f.isInGracePeriod())) return f;
        }
        return null;
    }

    /**
     * Prevents non-members from right-clicking / interacting with blocks in protected chunks.
     * Also blocks unauthorised block placement (placing a block is a RightClickBlock action).
     */
    @SubscribeEvent
    public static void onBlockInteractProtection(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player player = event.getEntity();
        if (player.hasPermissions(2)) return;

        Faction owner = getProtectedClaimAt(level, event.getPos());
        if (owner == null) return;

        FactionMember m = owner.getMember(player.getUUID());
        if (m == null || !owner.getRolePermission(m.getRole(), FactionPermission.MEMBER_INTERACT)) {
            event.setCanceled(true);
            player.displayClientMessage(
                    Component.literal("§cThis land is claimed by §e" + owner.getName() + "§c."), true);
        }
    }

    /**
     * Strips protected claimed blocks from every explosion's affected-block list.
     * Covers TNT, creepers, ghast fireballs, wither skulls, bed/anchor explosions, etc.
     * No player-permission bypass — nobody can blow up claimed land.
     */
    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        FactionManager mgr = FactionManager.get(level);
        EconomyManager eco = EconomyManager.get(level.getServer());
        String dim = level.dimension().location().toString();
        event.getAffectedBlocks().removeIf(pos -> {
            int cx = SectionPos.blockToSectionCoord(pos.getX());
            int cz = SectionPos.blockToSectionCoord(pos.getZ());
            for (Faction f : mgr.getAllFactions().values()) {
                if (f.hasClaim(cx, cz, dim) && (eco.hasUpkeep(f.getId()) || f.isInGracePeriod())) return true;
            }
            return false;
        });
    }

    /**
     * Blocks non-player entities — pistons, dispensers, modded drills/miners,
     * and fake players used by automation mods — from placing blocks in protected chunks.
     * For real players the BUILD role-permission is checked normally.
     */
    @SubscribeEvent
    public static void onEntityBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (entity == null) return; // world-internal operation — skip

        Faction owner = getProtectedClaimAt(level, event.getPos());
        if (owner == null) return;

        if (entity instanceof Player player) {
            // Covers fake players from automation/tech mods as well as real players
            if (player.hasPermissions(2)) return;
            FactionMember m = owner.getMember(player.getUUID());
            if (m == null || !owner.getRolePermission(m.getRole(), FactionPermission.MEMBER_BUILD)) {
                event.setCanceled(true);
            }
        } else {
            // Piston, dispenser, falling block, modded machine — always protect
            event.setCanceled(true);
        }
    }

    /**
     * Prevents entities from trampling protected farmland in claimed chunks.
     * Faction members are exempt; outsiders and mobs are blocked.
     */
    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Faction owner = getProtectedClaimAt(level, event.getPos());
        if (owner == null) return;

        Entity entity = event.getEntity();
        if (entity instanceof Player player) {
            if (player.hasPermissions(2)) return;
            if (owner.getMember(player.getUUID()) != null) return; // members can run freely
        }
        event.setCanceled(true);
    }
}
