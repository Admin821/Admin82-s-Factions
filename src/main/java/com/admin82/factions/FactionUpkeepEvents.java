package com.admin82.factions;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

/**
 * Processes faction upkeep once per real-world day.
 * Factions that cannot pay have all their land claims released.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public class FactionUpkeepEvents {

    /** Check upkeep every 100 ticks (5 seconds) — cheap; actual charges only happen daily. */
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < 100) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        FactionManager  fmgr   = FactionManager.get(server.overworld());
        EconomyManager  eco    = EconomyManager.get(server);
        MarketManager   market = MarketManager.get(server);

        long now = System.currentTimeMillis();
        Collection<Faction> factions = fmgr.getAllFactions().values();

        for (Faction faction : factions) {
            UUID fid = faction.getId();
            int claims = faction.getLandClaims().size();
            if (claims == 0) continue;

            long nextDue = eco.getUpkeepNextDue(fid);
            if (nextDue == 0) {
                // First time — schedule next due date
                eco.setUpkeepNextDue(fid, now + Currency.UPKEEP_INTERVAL_MS);
                continue;
            }
            if (now < nextDue) continue;

            // Upkeep is due
            long cost = claims * Currency.UPKEEP_PER_CLAIM_PER_DAY;
            if (eco.deductVault(fid, cost)) {
                // Paid successfully — reschedule
                eco.setUpkeepNextDue(fid, now + Currency.UPKEEP_INTERVAL_MS);
                AdminsFactions.LOGGER.debug("Faction '{}' paid upkeep of {} copper.", faction.getName(), cost);
            } else {
                // Cannot pay — release all claims
                AdminsFactions.LOGGER.info(
                        "Faction '{}' failed upkeep payment ({} copper). Releasing all claims.", faction.getName(), cost);
                var claimsToRelease = new ArrayList<>(faction.getLandClaims());
                String dim = server.overworld().dimension().location().toString();
                for (var claim : claimsToRelease) {
                    fmgr.unclaimChunk(fid, claim.chunkX(), claim.chunkZ(), dim);
                }
                // Reset upkeep timer
                eco.setUpkeepNextDue(fid, now + Currency.UPKEEP_INTERVAL_MS);

                // Notify online faction members
                for (var member : faction.getMembers()) {
                    var sp = server.getPlayerList().getPlayer(member.getUuid());
                    if (sp != null) {
                        sp.displayClientMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "§c[Faction] Your faction failed to pay upkeep! All claimed land has been released."),
                                false);
                    }
                }
            }
        }

        // Also process expired market listings
        market.processExpired(server);
    }
}
