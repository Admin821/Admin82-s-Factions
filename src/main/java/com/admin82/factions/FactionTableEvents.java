package com.admin82.factions;

import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.faction.FactionManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

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
}
