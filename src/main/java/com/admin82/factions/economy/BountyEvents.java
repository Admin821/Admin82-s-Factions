package com.admin82.factions.economy;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.BountyManager;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.AllianceManager;
import com.admin82.factions.faction.AllianceRules;
import com.admin82.factions.faction.FactionManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.UUID;

/**
 * Pays out bounties when an eligible player kills a target, refunds expired bounties,
 * and keeps alliance data tidy.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public final class BountyEvents {

    /** Why a player can or cannot claim a bounty. */
    public enum ClaimStatus { CLAIMABLE, YOU_ARE_TARGET, YOU_PLACED_IT, TEAMMATE, ALLY, LEFT_RECENTLY }

    private static int tick;

    private BountyEvents() {}

    public static ClaimStatus claimStatus(MinecraftServer server, UUID killer, BountyManager.Bounty bounty) {
        if (killer.equals(bounty.targetId)) return ClaimStatus.YOU_ARE_TARGET;
        if (killer.equals(bounty.placerId)) return ClaimStatus.YOU_PLACED_IT;
        FactionManager fmgr = FactionManager.get(server);
        UUID killerFaction = fmgr.getPlayerFactionId(killer);
        UUID targetFaction = fmgr.getPlayerFactionId(bounty.targetId);
        if (killerFaction != null && killerFaction.equals(targetFaction)) return ClaimStatus.TEAMMATE;
        if (AllianceRules.leftRecentlyReason(server, killer, targetFaction) != null) return ClaimStatus.LEFT_RECENTLY;
        if (AllianceRules.playersProtected(server, killer, bounty.targetId)) return ClaimStatus.ALLY;
        return ClaimStatus.CLAIMABLE;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerKilled(LivingDeathEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer) || killer == victim) return;
        MinecraftServer server = victim.server;
        BountyManager bounties = BountyManager.get(server);
        var onTarget = bounties.getOnTarget(victim.getUUID());
        if (onTarget.isEmpty()) return;

        long paid = 0;
        int count = 0;
        boolean blockedByPact = false, blockedByTeam = false, blockedByLeave = false;
        long now = System.currentTimeMillis();
        for (BountyManager.Bounty b : onTarget) {
            if (b.expiresAt <= now) continue; // expired; the expiry sweep refunds it
            ClaimStatus status = claimStatus(server, killer.getUUID(), b);
            if (status != ClaimStatus.CLAIMABLE) {
                blockedByPact |= status == ClaimStatus.ALLY;
                blockedByTeam |= status == ClaimStatus.TEAMMATE;
                blockedByLeave |= status == ClaimStatus.LEFT_RECENTLY;
                continue;
            }
            bounties.remove(b.id);
            paid += b.reward;
            count++;
            MarketManager.get(server).notifyPlayer(server, b.placerId, "§6[Bounty] §e" + killer.getName().getString()
                    + " §7claimed your bounty on §c" + victim.getName().getString() + " §7(" + Currency.format(b.reward) + "§7).");
            MarketManager.get(server).logMarket(b.placerId, 0, "Bounty on " + victim.getName().getString()
                    + " claimed by " + killer.getName().getString(), new ItemStack(Items.SKELETON_SKULL));
        }
        if (count == 0) {
            if (blockedByTeam) killer.displayClientMessage(Component.literal("§7[Bounty] You can't claim bounties on your own teammates."), true);
            else if (blockedByLeave) killer.displayClientMessage(Component.literal("§7[Bounty] You left their faction or an ally of it too recently to claim this."), true);
            else if (blockedByPact) killer.displayClientMessage(Component.literal("§7[Bounty] You can't claim bounties on an allied faction."), true);
            return;
        }
        String victimName = victim.getName().getString();
        EconomyManager.get(server).addWallet(killer.getUUID(), paid,
                "Bounty on " + victimName + (count > 1 ? " (" + count + " bounties)" : ""));
        MarketManager.get(server).logMarket(killer.getUUID(), paid, "Claimed bounty on " + victimName, new ItemStack(Items.SKELETON_SKULL));
        killer.displayClientMessage(Component.literal("§6[Bounty] §aYou claimed §e" + Currency.format(paid)
                + " §afor killing §c" + victimName + "§a. Paid to your wallet."), false);
        victim.displayClientMessage(Component.literal("§6[Bounty] §c" + killer.getName().getString()
                + " §7claimed the bounty on your head."), false);
        server.getPlayerList().broadcastSystemMessage(Component.literal("§6[Bounty] §e" + killer.getName().getString()
                + " §7claimed §e" + Currency.format(paid) + " §7in bounties on §c" + victimName + "§7."), false);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tick < 20 * 30) return; // every 30 seconds
        tick = 0;
        MinecraftServer server = event.getServer();

        BountyManager bounties = BountyManager.get(server);
        for (BountyManager.Bounty b : bounties.takeExpired()) {
            EconomyManager.get(server).addWallet(b.placerId, b.reward, "Bounty on " + b.targetName + " expired (refund)");
            MarketManager.get(server).notifyPlayer(server, b.placerId, "§6[Bounty] §7Your bounty on §c" + b.targetName
                    + " §7expired. §e" + Currency.format(b.reward) + " §7was refunded to your wallet.");
        }

        AllianceManager alliances = AllianceManager.get(server);
        alliances.prune();
        FactionManager fmgr = FactionManager.get(server);
        // Forget alliances, proposals and cooldowns of disbanded factions.
        alliances.retainFactions(fmgr.getAllFactions().keySet());
    }
}
