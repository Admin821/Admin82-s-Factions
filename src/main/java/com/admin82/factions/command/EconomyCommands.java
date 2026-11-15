package com.admin82.factions.command;

import com.admin82.factions.Config;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.network.packet.SyncEconomyPacket;
import com.admin82.factions.network.packet.SyncFactionDataPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

import static com.admin82.factions.util.BypassManager.toggleBypass;

/** Claim rates and outpost prices. */
final class EconomyCommands {

    private EconomyCommands() {}

    // /faction economy claimrates <value>

    static int cmdEconomyClaimRates(CommandSourceStack src, double rate) {
        MinecraftServer server = src.getServer();
        EconomyManager eco = EconomyManager.get(server);
        FactionManager fmgr = FactionManager.get(server);
        eco.setClaimRateMultiplier(rate);
        ClaimRateRecalcResult result = recalculateClaimCostsAndRefunds(server, fmgr, eco, rate);
        syncFactionTables(server, fmgr, eco);
        src.sendSuccess(() -> Component.literal(
                "§aClaim deed cost rate set to §e" + rate
                + "§a. Each additional deed costs base × " + rate + "^n."
                + " §7Updated §e" + result.claimsUpdated + "§7 claims and refunded §a"
                + Currency.format(result.totalRefunded) + "§7 to faction vaults."), true);
        return 1;
    }

    private record ClaimRateRecalcResult(int claimsUpdated, long totalRefunded) {}

    private static ClaimRateRecalcResult recalculateClaimCostsAndRefunds(MinecraftServer server, FactionManager fmgr, EconomyManager eco, double rate) {
        long baseCost = Config.CLAIM_COST_COPPER.get();
        long now = System.currentTimeMillis();
        int claimsUpdated = 0;
        long totalRefunded = 0L;

        for (Faction faction : fmgr.getAllFactions().values()) {
            var claims = faction.getLandClaims();
            if (claims.isEmpty()) continue;

            List<LandClaim> recalculated = new ArrayList<>(claims.size());
            long oldDailyTotal = 0L;
            long newDailyTotal = 0L;
            long deedRefund = 0L;

            for (int i = 0; i < claims.size(); i++) {
                LandClaim oldClaim = claims.get(i);
                long oldCost = oldClaim.dailyCost();
                // A claim's stored price never goes up: raising the rate only affects new claims. Otherwise an
                // admin raising then lowering the rate would refund money nobody paid. Outposts (stored at 0)
                // and other free claims stay free.
                long newCost = Math.min(oldCost, EconomyManager.computeClaimCost(baseCost, i, rate));
                recalculated.add(oldClaim.withDailyCost(newCost));
                oldDailyTotal += oldCost;
                newDailyTotal += newCost;
                if (oldCost > newCost) deedRefund += oldCost - newCost;
                if (oldCost != newCost) claimsUpdated++;
            }

            // Upkeep doesn't depend on the claim rate, so there's no upkeep to refund here.
            long upkeepRefund = 0L;

            if (deedRefund > 0 || upkeepRefund > 0) {
                long refund = deedRefund + upkeepRefund;
                eco.addVault(faction.getId(), refund, "Claim rate recalculation refund");
                totalRefunded += refund;
                notifyFaction(server, faction, "§a[Faction] Claim rates were recalculated. §e"
                        + Currency.format(refund) + " §awas refunded to your faction vault.");
            }
            faction.replaceLandClaims(recalculated);
        }

        fmgr.setDirty();
        return new ClaimRateRecalcResult(claimsUpdated, totalRefunded);
    }

    private static void syncFactionTables(MinecraftServer server, FactionManager fmgr, EconomyManager eco) {
        double claimRate = eco.getClaimRateMultiplier();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            Faction faction = fmgr.getFactionForPlayer(online.getUUID());
            PacketDistributor.sendToPlayer(online, new SyncFactionDataPacket(faction, claimRate));
            PacketDistributor.sendToPlayer(online, new SyncEconomyPacket(
                    eco.getWallet(online.getUUID()), faction != null ? eco.getVault(faction.getId()) : 0L));
        }
    }

    private static void notifyFaction(MinecraftServer server, Faction faction, String message) {
        Component component = Component.literal(message);
        for (FactionMember member : faction.getMembers()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member.getUuid());
            if (player != null) player.displayClientMessage(component, false);
        }
    }

    // /faction economy outpostramp <value>

    static int cmdEconomyOutpostRamp(CommandSourceStack src, double ramp) {
        EconomyManager.get(src.getServer()).setOutpostDistanceRamp(ramp);
        src.sendSuccess(() -> Component.literal(
                "§aOutpost distance ramp set to §e" + ramp
                + "§a per 5-chunk band (e.g. 10 chunks away = +" + (int)(2 * ramp * 100) + "%)." ), true);
        return 1;
    }

    static int cmdEconomyTpCostToOutpost(CommandSourceStack src, int silver) {
        long copper = (long) silver * Currency.COPPER_PER_SILVER;
        EconomyManager.get(src.getServer()).setTpCostToOutpost(copper);
        src.sendSuccess(() -> Component.literal("§aOutpost teleport cost set to §e" + silver + " silver§a."), true);
        return silver;
    }
}
