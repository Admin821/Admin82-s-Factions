package com.admin82.factions;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.LandClaim;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.*;

/**
 * Gradual vault drain for faction upkeep.
 *
 * Instead of a single lump-sum charge every 24 hours, the daily cost is spread
 * evenly across the entire day.  Every 5 seconds (100 ticks) the fraction of
 * the daily cost that SHOULD have been paid since the period started is compared
 * to what HAS been paid, and the difference is deducted from the vault.
 *
 * If the vault cannot cover the incremental charge:
 *   – The vault is drained to zero.
 *   – The faction is marked INSOLVENT: claims remain but receive NO protection.
 *   – All online members are warned immediately.
 *
 * Once money is deposited into the vault and the next tick successfully deducts
 * the pending amount, the faction becomes SOLVENT again and protection resumes.
 * Members are notified of the recovery.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public class FactionUpkeepEvents {

    /** Tick counter; upkeep is processed every 100 ticks = 5 seconds. */
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

        for (Faction faction : fmgr.getAllFactions().values()) {
            UUID fid = faction.getId();

            // Sum daily upkeep across all claims
            long dailyCost = faction.getLandClaims().stream()
                    .mapToLong(LandClaim::dailyCost).sum();

            if (dailyCost <= 0) {
                // No upkeep owed — ensure the faction is marked solvent
                if (!eco.isProtected(fid)) {
                    eco.setSolvent(fid);
                }
                continue;
            }

            // ── Initialise the 24-hour period if this is the first tick ────────
            long periodStart = eco.getUpkeepPeriodStart(fid);
            if (periodStart <= 0) {
                eco.setUpkeepPeriodStart(fid, now);
                eco.setUpkeepChargedSoFar(fid, 0L);
                continue; // give one tick's grace before charging
            }

            long elapsed = now - periodStart;

            // ── Roll over into the next 24-hour period when the current one ends ─
            if (elapsed >= Currency.UPKEEP_INTERVAL_MS) {
                eco.setUpkeepPeriodStart(fid, now);
                eco.setUpkeepChargedSoFar(fid, 0L);
                elapsed = 0L;
            }

            // ── How much SHOULD have been charged by now vs what HAS been ────────
            long expectedCharged = dailyCost * elapsed / Currency.UPKEEP_INTERVAL_MS;
            long alreadyCharged  = eco.getUpkeepChargedSoFar(fid);
            long toCharge        = expectedCharged - alreadyCharged;

            if (toCharge <= 0) continue; // nothing due yet this tick

            // ── Attempt the incremental deduction ─────────────────────────────
            long vault = eco.getVault(fid);
            if (vault >= toCharge) {
                // Vault can cover the charge — deduct and mark solvent
                eco.setVault(fid, vault - toCharge);
                eco.setUpkeepChargedSoFar(fid, alreadyCharged + toCharge);

                if (!eco.isProtected(fid)) {
                    eco.setSolvent(fid);
                    notify(server, faction,
                            "§a[Faction] Vault funded — land claims are now §lPROTECTED§a again!");
                }
            } else {
                // Vault too low — drain whatever is left and mark insolvent
                eco.setUpkeepChargedSoFar(fid, alreadyCharged + vault);
                eco.setVault(fid, 0L);

                if (eco.isProtected(fid)) {
                    eco.setInsolvent(fid);
                    notify(server, faction,
                            "§c[Faction] §lVault empty!§c Land claims are now §lUNPROTECTED§c — "
                            + "deposit coins into the vault to restore protection.");
                }
            }
        }

        // Process expired market listings
        market.processExpired(server);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void notify(MinecraftServer server, Faction faction, String message) {
        Component msg = Component.literal(message);
        for (var member : faction.getMembers()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(member.getUuid());
            if (sp != null) sp.displayClientMessage(msg, false);
        }
    }
}
