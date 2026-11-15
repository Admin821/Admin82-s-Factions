package com.admin82.factions.network;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.BountyManager;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.AllianceManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.menu.factiontable.FactionTableMenu;
import com.admin82.factions.menu.market.MarketMenu;
import com.admin82.factions.network.packet.SyncAlliancesPacket;
import com.admin82.factions.network.packet.SyncBountiesPacket;
import com.admin82.factions.network.packet.SyncEconomyPacket;
import com.admin82.factions.network.packet.SyncFactionDataPacket;
import com.admin82.factions.network.packet.SyncMarketDeliveriesPacket;
import com.admin82.factions.network.packet.SyncMarketPacket;
import com.admin82.factions.network.packet.SyncSoldListingsPacket;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps open Market and Faction Table screens up to date without reopening them.
 * Twice a second, every player with one of those menus open is sent whatever they can
 * see that changed since the last push: market listings, wallet / vault balances, or
 * their faction's data. Nothing is sent when nothing changed.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public final class LiveSync {

    private static final int INTERVAL_TICKS = 10;
    private static int tick;

    private record MarketSig(long marketVersion, long wallet) {}
    private record EconomySig(long wallet, long vault) {}

    private static final Map<UUID, MarketSig> marketSent = new HashMap<>();
    private static final Map<UUID, EconomySig> economySent = new HashMap<>();
    private static final Map<UUID, Integer> factionSent = new HashMap<>();
    private static final Map<UUID, Long> bountySent = new HashMap<>();
    private static final Map<UUID, Long> allianceSent = new HashMap<>();

    private LiveSync() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tick < INTERVAL_TICKS) return;
        tick = 0;
        MinecraftServer server = event.getServer();

        Set<UUID> marketViewers = new HashSet<>();
        Set<UUID> tableViewers = new HashSet<>();
        Map<UUID, Integer> factionHashes = new HashMap<>(); // computed once per faction per pass

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            if (sp.containerMenu instanceof MarketMenu) {
                marketViewers.add(sp.getUUID());
                pushMarket(server, sp);
            } else if (sp.containerMenu instanceof FactionTableMenu) {
                tableViewers.add(sp.getUUID());
                pushFactionTable(server, sp, factionHashes);
            }
        }
        // Forget players who closed the screen, so reopening starts fresh.
        marketSent.keySet().retainAll(marketViewers);
        economySent.keySet().retainAll(tableViewers);
        bountySent.keySet().retainAll(marketViewers);
        allianceSent.keySet().retainAll(tableViewers);
        factionSent.keySet().retainAll(tableViewers);
    }

    private static void pushMarket(MinecraftServer server, ServerPlayer sp) {
        // Bounty board: re-send when bounties change, and once a minute so online dots stay right.
        // Alliances change who can claim what, so they count too.
        long bountyKey = (BountyManager.get(server).getVersion() * 1_000_003L
                + AllianceManager.get(server).getVersion()) * 31L
                + System.currentTimeMillis() / 60_000L;
        Long previousBounty = bountySent.put(sp.getUUID(), bountyKey);
        if (previousBounty != null && previousBounty != bountyKey) {
            PacketDistributor.sendToPlayer(sp, SyncBountiesPacket.forViewer(server, sp));
        }

        MarketManager market = MarketManager.get(server);
        EconomyManager eco = EconomyManager.get(server);
        UUID id = sp.getUUID();
        MarketSig sig = new MarketSig(market.getVersion(), eco.getWallet(id));
        MarketSig previous = marketSent.put(id, sig);
        if (previous == null) return; // just opened: the market block already sent everything
        if (previous.equals(sig)) return;

        Faction faction = FactionManager.get(server).getFactionForPlayer(id);
        int maxSlots = MarketManager.listingLimit();
        PacketDistributor.sendToPlayer(sp, new SyncMarketPacket(
                market.getListings().stream().toList(), sig.wallet(), market.countPlayerListings(id), maxSlots));
        if (previous.marketVersion() != sig.marketVersion()) {
            PacketDistributor.sendToPlayer(sp, new SyncSoldListingsPacket(market.getSoldListingsForPlayer(id)));
            PacketDistributor.sendToPlayer(sp, new SyncMarketDeliveriesPacket(market.getDeliveriesForPlayer(id)));
        }
    }

    private static void pushFactionTable(MinecraftServer server, ServerPlayer sp, Map<UUID, Integer> factionHashes) {
        UUID id = sp.getUUID();
        EconomyManager eco = EconomyManager.get(server);
        Faction faction = FactionManager.get(server).getFactionForPlayer(id);

        EconomySig econ = new EconomySig(eco.getWallet(id), faction != null ? eco.getVault(faction.getId()) : 0L);
        EconomySig previousEcon = economySent.put(id, econ);
        if (previousEcon != null && !previousEcon.equals(econ)) {
            PacketDistributor.sendToPlayer(sp, new SyncEconomyPacket(econ.wallet(), econ.vault()));
        }

        long allianceKey = AllianceManager.get(server).getVersion()
                + System.currentTimeMillis() / 60_000L * 1_000_003L; // also once a minute for cooldown timers
        Long previousAlliance = allianceSent.put(id, allianceKey);
        if (faction != null && previousAlliance != null && previousAlliance != allianceKey) {
            PacketDistributor.sendToPlayer(sp, SyncAlliancesPacket.forFaction(server, faction.getId()));
        }

        int hash = faction == null ? 0 : factionHashes.computeIfAbsent(faction.getId(), ignored -> hashOf(faction));
        Integer previousHash = factionSent.put(id, hash);
        if (previousHash != null && previousHash != hash) {
            PacketDistributor.sendToPlayer(sp, new SyncFactionDataPacket(faction));
        }
    }

    /** Hash of exactly what the client receives, so only visible changes trigger a push. */
    private static int hashOf(Faction faction) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            faction.toNetwork(buf);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return Arrays.hashCode(bytes);
        } finally {
            buf.release();
        }
    }

    /** Call after sending a full market sync elsewhere, so the next pass doesn't repeat it. */
    public static void noteMarketSent(ServerPlayer sp, long marketVersion, long wallet) {
        marketSent.put(sp.getUUID(), new MarketSig(marketVersion, wallet));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        marketSent.remove(id);
        economySent.remove(id);
        factionSent.remove(id);
        bountySent.remove(id);
        allianceSent.remove(id);
    }
}
