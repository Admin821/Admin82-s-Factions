package com.admin82.factions;

import com.admin82.factions.blockentity.BarracksBlockEntity;
import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.blockentity.OutpostManagerBlockEntity;
import com.admin82.factions.block.FactionTableFillerBlock;
import com.admin82.factions.registry.ModBlocks;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionMember;
import com.admin82.factions.faction.FactionPermission;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.item.TemporaryMoveItem;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.war.ResourceWarAccess;
import com.admin82.factions.war.WarManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
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
     * Prevents linked Faction Tables and Barracks from being manually broken.
     * The only way to remove linked blocks is via disband cleanup or the move flows.
     */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        // ── Protect Faction Table filler blocks ──────────────────────────────────────────
        BlockState brokenState = event.getState();
        if (brokenState.is(ModBlocks.FACTION_TABLE_FILLER.get())) {
            BlockPos mainPos = brokenState.getValue(FactionTableFillerBlock.PART).getMainPos(event.getPos());
            if (level.getBlockEntity(mainPos) instanceof FactionTableBlockEntity mainBe) {
                UUID linkedId = mainBe.getLinkedFactionId();
                if (linkedId != null && FactionManager.get(level).getFaction(linkedId) != null) {
                    Player player = event.getPlayer();
                    if (player.hasPermissions(2)) {
                        FactionCommands.performDisband(level.getServer(), linkedId,
                                Component.literal("§cThe Faction Table was destroyed by an admin — faction has been disbanded."));
                    } else {
                        player.displayClientMessage(
                                Component.literal("§cFaction Tables cannot be broken. Use the faction menu to move it or disband the faction."), true);
                    }
                    event.setCanceled(true);
                    return;
                }
            }
            // Unlinked or stale-linked filler — allow break; FactionTableFillerBlock.onRemove cascades to main block.
            return;
        }

        // ── Protect linked Faction Tables ─────────────────────────────────────────────────
        if (level.getBlockEntity(event.getPos()) instanceof FactionTableBlockEntity be) {
            UUID linkedId = be.getLinkedFactionId();
            if (linkedId == null) return;
            if (FactionManager.get(level).getFaction(linkedId) == null) return;
            Player player = event.getPlayer();
                if (player.hasPermissions(2)) {
                FactionCommands.performDisband(level.getServer(), linkedId,
                    Component.literal("§cThe Faction Table was destroyed by an admin — faction has been disbanded."));
                return;
                }
            event.setCanceled(true);
            player.displayClientMessage(
                Component.literal("§cFaction Tables cannot be broken. Use the faction menu to move it or disband the faction."), true);
            return;
        }

        // Protect linked Barracks
        if (level.getBlockEntity(event.getPos()) instanceof BarracksBlockEntity barrBe) {
            UUID linkedId = barrBe.getLinkedFactionId();
            if (linkedId == null) return;
            if (FactionManager.get(level).getFaction(linkedId) == null) return;
            Player player = event.getPlayer();
            event.setCanceled(true);
            player.displayClientMessage(
                Component.literal("§cBarracks cannot be broken. Use the Kit Manager to move it or disband the faction."), true);
        }
    }

    /**
     * Protects placed Outpost manager blocks from being broken by non-operators.
     * When an operator breaks one, the outpost is immediately deleted and its
     * tracked structure blocks are removed from the world.
     */
    @SubscribeEvent
    public static void onOutpostBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(level.getBlockEntity(event.getPos()) instanceof OutpostManagerBlockEntity)) return;

        Player player = event.getPlayer();
        if (!player.hasPermissions(2)) {
            event.setCanceled(true);
            player.displayClientMessage(
                    Component.literal("§cOutpost blocks cannot be broken. Only server operators may remove them."), true);
            return;
        }

        // Op is breaking the outpost — delete it cleanly
        String dim = level.dimension().location().toString();
        OutpostData outposts = OutpostData.get(level.getServer());
        OutpostEntry entry = outposts.getOutpostAtPos(event.getPos(), dim);
        if (entry != null) {
            // Remove all registered structure blocks from the world
            for (net.minecraft.core.BlockPos bp : entry.structureBlocks) {
                level.removeBlock(bp, false);
            }
            outposts.removeOutpost(entry.id);
            player.displayClientMessage(
                    Component.literal("§a[Admin] Outpost deleted and structure removed."), false);
        }
        // Allow the actual block break to continue normally
    }

    /** Cancels any in-progress move mode when the player logs out. */
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        FactionManager manager = FactionManager.get(player.server);
        manager.clearPendingMove(player.getUUID());
        manager.clearPendingCreation(player.getUUID());
        manager.clearPendingBarracks(player.getUUID());
        manager.clearPendingBarracksMove(player.getUUID());
        removeTemporaryMoveItems(player);
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
        if (manager.getPendingBarracksMove(player.getUUID()) != null) {
            manager.clearPendingBarracksMove(player.getUUID());
            player.displayClientMessage(Component.literal("§cBarracks move mode cancelled (you died)."), false);
        }
        manager.clearPendingCreation(player.getUUID());
        manager.clearPendingBarracks(player.getUUID());
        removeTemporaryMoveItems(player);
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
        if (manager.getPendingBarracksMove(player.getUUID()) != null) {
            manager.clearPendingBarracksMove(player.getUUID());
            player.displayClientMessage(Component.literal("§cBarracks move mode cancelled (dimension change)."), false);
        }
        manager.clearPendingCreation(player.getUUID());
        removeTemporaryMoveItems(player);
    }

    private static void removeTemporaryMoveItems(ServerPlayer player) {
        TemporaryMoveItem.removeAll(player, com.admin82.factions.registry.ModItems.FACTION_TABLE.get());
        TemporaryMoveItem.removeAll(player, com.admin82.factions.registry.ModItems.BARRACKS.get());
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
            if (f.hasClaim(cx, cz, dim) && (eco.isProtected(f.getId()) || f.isInGracePeriod())) return f;
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

            // Allow Resource-War winners to open containers in enemy territory
            if (m == null) {
                WarManager warmgr = WarManager.get(level.getServer());
                ResourceWarAccess rwa = warmgr.getResourceWarAccess(owner.getId());
                if (rwa != null && !rwa.isExpired()) {
                    Faction playerFaction = FactionManager.get(level.getServer())
                            .getFactionForPlayer(player.getUUID());
                    if (playerFaction != null && playerFaction.getId().equals(rwa.winnerFactionId)) {
                        return; // resource war winner — allow interaction
                    }
                }
            }

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
            if (isLinkedCoreBlockProtected(level, mgr, pos)) return true;

            int cx = SectionPos.blockToSectionCoord(pos.getX());
            int cz = SectionPos.blockToSectionCoord(pos.getZ());
            for (Faction f : mgr.getAllFactions().values()) {
                if (f.hasClaim(cx, cz, dim) && (eco.isProtected(f.getId()) || f.isInGracePeriod())) return true;
            }
            return false;
        });
    }

    private static boolean isLinkedCoreBlockProtected(ServerLevel level, FactionManager mgr, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof FactionTableBlockEntity tableBe) {
            UUID linkedId = tableBe.getLinkedFactionId();
            return linkedId != null && mgr.getFaction(linkedId) != null;
        }

        if (level.getBlockEntity(pos) instanceof BarracksBlockEntity barracksBe) {
            UUID linkedId = barracksBe.getLinkedFactionId();
            return linkedId != null && mgr.getFaction(linkedId) != null;
        }

        BlockState state = level.getBlockState(pos);
        if (state.is(ModBlocks.FACTION_TABLE_FILLER.get())) {
            BlockPos mainPos = state.getValue(FactionTableFillerBlock.PART).getMainPos(pos);
            if (level.getBlockEntity(mainPos) instanceof FactionTableBlockEntity tableBe) {
                UUID linkedId = tableBe.getLinkedFactionId();
                return linkedId != null && mgr.getFaction(linkedId) != null;
            }
        }

        return false;
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

    /**
     * Prevents mobs from griefing blocks in claimed chunks.
     * Covers endermen picking up/placing blocks, creepers igniting,
     * ravagers destroying crops, villager farming, etc.
     */
    @SubscribeEvent
    public static void onMobGriefing(net.neoforged.neoforge.event.entity.EntityMobGriefingEvent event) {
        net.minecraft.world.entity.Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) return;
        if (getProtectedClaimAt(level, entity.blockPosition()) != null) {
            event.setCanGrief(false);
        }
    }
}
