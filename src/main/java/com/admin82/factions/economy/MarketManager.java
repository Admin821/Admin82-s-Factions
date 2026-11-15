package com.admin82.factions.economy;

import com.admin82.factions.Config;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.network.LiveSync;
import com.admin82.factions.network.packet.SyncSoldListingsPacket;
import com.admin82.factions.war.VassalManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import javax.annotation.Nullable;

/** Persistent storage for all market listings. */
public class MarketManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_market";
    private static final long REMINDER_INTERVAL_MS = 3_600_000L;

    private final List<MarketListing> listings     = new ArrayList<>();
    private final List<SoldListing>   soldListings  = new ArrayList<>();
    private final List<MarketDelivery> pendingDeliveries = new ArrayList<>();
    private final Map<UUID, Long> lastReminderAt = new HashMap<>();
    /** Messages for players who were offline when something happened (e.g. buy order fills). */
    private final Map<UUID, List<String>> pendingNotices = new HashMap<>();
    private static final int MAX_NOTICES_PER_PLAYER = 30;
    /** Bumped on every change, so open market screens know when to refresh (see LiveSync). */
    private long version;

    public long getVersion() { return version; }

    @Override
    public void setDirty(boolean dirty) {
        if (dirty) version++;
        super.setDirty(dirty);
    }
    /** Newest-first market activity per player (bought, sold, bids, orders...). */
    private final Map<UUID, Deque<Transaction>> marketHistory = new HashMap<>();
    /** Oldest-first completed sales per item id, for price stats and charts. */
    private final Map<String, Deque<PricePoint>> priceHistory = new HashMap<>();

    // History

    /** Adds a line to a player's market activity. {@code amount} is signed copper (0 = info only). */
    public void logMarket(UUID player, long amount, String text, ItemStack icon) {
        Transaction.push(marketHistory.computeIfAbsent(player, ignored -> new ArrayDeque<>()),
                new Transaction(System.currentTimeMillis(), amount, -1L, text, icon.isEmpty() ? ItemStack.EMPTY : icon.copyWithCount(1)));
        setDirty();
    }

    public List<Transaction> getMarketHistory(UUID player) {
        return List.copyOf(marketHistory.getOrDefault(player, new ArrayDeque<>()));
    }

    /** Records a completed sale for the item's price history. */
    public void recordSale(ItemStack item, long unitPrice, int quantity, int source) {
        if (item.isEmpty() || unitPrice <= 0 || quantity <= 0) return;
        String key = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
        Deque<PricePoint> points = priceHistory.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        points.addLast(new PricePoint(System.currentTimeMillis(), unitPrice, quantity, source));
        while (points.size() > PricePoint.MAX_POINTS) points.removeFirst();
        setDirty();
    }

    /** Every item's sale history, oldest sale first (for the Stocks page). */
    public Map<String, List<PricePoint>> allPriceHistory() {
        Map<String, List<PricePoint>> out = new HashMap<>();
        priceHistory.forEach((id, points) -> out.put(id, List.copyOf(points)));
        return out;
    }

    public List<PricePoint> getPriceHistory(String itemId) {
        return List.copyOf(priceHistory.getOrDefault(itemId, new ArrayDeque<>()));
    }

    // Singleton

    public static MarketManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new Factory<>(MarketManager::new, MarketManager::load),
            DATA_NAME
        );
    }

    // API

    public List<MarketListing> getListings() {
        return Collections.unmodifiableList(listings);
    }

    public void addListing(MarketListing listing) {
        listings.add(listing);
        setDirty();
    }

    public boolean removeListing(UUID listingId) {
        boolean removed = listings.removeIf(l -> l.listingId.equals(listingId));
        if (removed) setDirty();
        return removed;
    }

    @Nullable
    public MarketListing getListing(UUID listingId) {
        return listings.stream().filter(l -> l.listingId.equals(listingId)).findFirst().orElse(null);
    }

    public List<MarketListing> getPlayerListings(UUID playerUUID) {
        return listings.stream().filter(l -> l.sellerUUID.equals(playerUUID)).toList();
    }

    /**
     * Takes vassal tax out of a seller's proceeds (already after market tax) when the seller's faction is a
     * vassal, and returns what's left for the seller. Every kind of sale goes through here.
     */
    public static long afterVassalTax(MinecraftServer server, UUID seller, long proceeds) {
        if (proceeds <= 0) return proceeds;
        var faction = FactionManager.get(server).getFactionForPlayer(seller);
        var vassals = VassalManager.get(server);
        if (faction == null || !vassals.isVassal(faction.getId())) return proceeds;
        long tax = Math.min(proceeds, Math.max(1, proceeds * Config.VASSAL_TAX_PERCENT.get() / 100));
        vassals.accumulateTax(faction.getId(), tax);
        return proceeds - tax;
    }

    /** How many listings and buy orders a player may have up at once (the "Listings per player" setting). */
    public static int listingLimit() {
        return Config.MAX_LISTINGS_PER_PLAYER.get();
    }

    /** Count of active non-admin listings by a player (used for slot limits). */
    public int countPlayerListings(UUID playerUUID) {
        return (int) listings.stream().filter(l -> l.sellerUUID.equals(playerUUID) && !l.isAdminListing()).count();
    }

    /** Count of active permanent server shop sell listings. */
    public int countServerShopListings() {
        return (int) listings.stream().filter(l -> l.kind == MarketListing.ListingKind.ADMIN_SELL).count();
    }

    // Sold listings (unclaimed proceeds)

    public void addSoldListing(SoldListing sale) {
        soldListings.add(sale);
        lastReminderAt.put(sale.sellerUUID, System.currentTimeMillis());
        setDirty();
    }

    public List<SoldListing> getSoldListingsForPlayer(UUID playerUUID) {
        return soldListings.stream().filter(s -> s.sellerUUID.equals(playerUUID)).toList();
    }

    public boolean claimSoldListing(UUID saleId) {
        boolean removed = soldListings.removeIf(s -> s.saleId.equals(saleId));
        if (removed) {
            cleanReminderState();
            setDirty();
        }
        return removed;
    }

    public void queueDelivery(UUID playerUUID, ItemStack item, String reason) {
        if (item.isEmpty()) return;
        int remaining = item.getCount();
        int maxStack = Math.max(1, item.getMaxStackSize());
        while (remaining > 0) {
            int count = Math.min(maxStack, remaining);
            remaining -= count;
            MarketDelivery delivery = new MarketDelivery();
            delivery.deliveryId = UUID.randomUUID();
            delivery.playerUUID = playerUUID;
            delivery.item = item.copyWithCount(count);
            delivery.reason = reason;
            delivery.createdAt = System.currentTimeMillis();
            pendingDeliveries.add(delivery);
        }
        lastReminderAt.put(playerUUID, System.currentTimeMillis());
        setDirty();
    }

    /** Tells the player now if online, otherwise stores the message for their next login. */
    public void notifyPlayer(MinecraftServer server, UUID playerUUID, String message) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
        if (player != null) {
            player.displayClientMessage(Component.literal(message), false);
            return;
        }
        List<String> notices = pendingNotices.computeIfAbsent(playerUUID, ignored -> new ArrayList<>());
        notices.add(message);
        while (notices.size() > MAX_NOTICES_PER_PLAYER) notices.remove(0);
        setDirty();
    }

    public List<MarketDelivery> getDeliveriesForPlayer(UUID playerUUID) {
        return pendingDeliveries.stream().filter(delivery -> delivery.playerUUID.equals(playerUUID)).toList();
    }

    public boolean claimDelivery(ServerPlayer player, UUID deliveryId) {
        MarketDelivery delivery = pendingDeliveries.stream()
                .filter(candidate -> candidate.deliveryId.equals(deliveryId)
                        && candidate.playerUUID.equals(player.getUUID()))
                .findFirst().orElse(null);
        if (delivery == null) return false;
        deliverToInventory(player, delivery.item);
        pendingDeliveries.remove(delivery);
        cleanReminderState();
        setDirty();
        return true;
    }

    public void notifyLoginSummary(ServerPlayer player) {
        List<String> notices = pendingNotices.remove(player.getUUID());
        if (notices != null && !notices.isEmpty()) {
            notices.forEach(notice -> player.displayClientMessage(Component.literal(notice), false));
            setDirty();
        }
        if (sendClaimSummary(player, "§e[Market] While you were away:")) {
            lastReminderAt.put(player.getUUID(), System.currentTimeMillis());
            setDirty();
        }
    }

    public void processClaimReminders(MinecraftServer server) {
        long now = System.currentTimeMillis();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            if (!hasPendingClaims(playerId)) continue;
            if (now - lastReminderAt.getOrDefault(playerId, 0L) < REMINDER_INTERVAL_MS) continue;
            if (sendClaimSummary(player, "§e[Market] Unclaimed items reminder:")) {
                lastReminderAt.put(playerId, now);
                setDirty();
            }
        }
    }

    private boolean sendClaimSummary(ServerPlayer player, String prefix) {
        List<SoldListing> sales = getSoldListingsForPlayer(player.getUUID());
        int deliveries = getDeliveriesForPlayer(player.getUUID()).size();
        if (sales.isEmpty() && deliveries == 0) return false;
        long proceeds = sales.stream().mapToLong(sale -> sale.proceeds).sum();
        String salesText = sales.isEmpty() ? ""
                : " §a" + sales.size() + " sale" + (sales.size() == 1 ? "" : "s")
                + " worth §e" + Currency.format(proceeds) + "§a";
        String deliveryText = deliveries == 0 ? ""
                : (sales.isEmpty() ? "" : " §7and") + " §b" + deliveries + " pending purchase"
                + (deliveries == 1 ? "" : "s");
        player.displayClientMessage(Component.literal(prefix + salesText + deliveryText
                + "§7. Open a Faction Market and use §fMy Listings §7to claim."), false);
        return true;
    }

    private boolean hasPendingClaims(UUID playerId) {
        return soldListings.stream().anyMatch(sale -> sale.sellerUUID.equals(playerId))
                || pendingDeliveries.stream().anyMatch(delivery -> delivery.playerUUID.equals(playerId));
    }

    private void cleanReminderState() {
        lastReminderAt.keySet().removeIf(playerId -> !hasPendingClaims(playerId));
    }

    /**
     * Process any listings that have expired.
     * Delivers items/payment to players if online; stores pending delivery in EconomyManager otherwise.
     */
    public void processExpired(MinecraftServer server) {
        long now = System.currentTimeMillis();
        var toRemove = new ArrayList<UUID>();
        var eco = EconomyManager.get(server);

        for (MarketListing listing : listings) {
            if (listing.expiresAt > now) continue;
            toRemove.add(listing.listingId);

            if (listing.kind == MarketListing.ListingKind.PLAYER_BUY_ORDER) {
                if (listing.escrow > 0) eco.addWallet(listing.sellerUUID, listing.escrow, "Buy order expired (refund)");
                logMarket(listing.sellerUUID, listing.escrow, "Buy order expired: " + listing.orderFilled + "/"
                        + listing.orderQuantity + " " + listing.item.getHoverName().getString() + " (refund)", listing.item);
                notifyPlayer(server, listing.sellerUUID, "§e[Market] Your buy order for §f"
                        + listing.orderQuantity + " " + listing.item.getHoverName().getString()
                        + " §eexpired with §f" + listing.orderFilled + "/" + listing.orderQuantity
                        + " §efilled. Refunded §a" + Currency.format(listing.escrow) + "§e to your wallet.");
            } else if (listing.kind == MarketListing.ListingKind.ADMIN_BUY_ORDER || listing.kind == MarketListing.ListingKind.ADMIN_SELL) {
                // Server/admin listings are not backed by player escrow or inventory.
            } else if (listing.isAuction && listing.highestBidder != null) {
                // Auction sold: deliver item to winner, create a claimable SoldListing for seller
                deliverItem(server, listing.highestBidder, listing.item);
                long proceeds = listing.netProceeds(listing.highestBid);
                Treasury.record(server, Treasury.Source.MARKET_TAX_AUCTION, listing.highestBid - proceeds,
                        resolvePlayerName(server, listing.sellerUUID), listing.item.getCount() + "× "
                        + listing.item.getHoverName().getString() + " sold for " + Currency.format(listing.highestBid).replaceAll("§.", ""));
                proceeds = afterVassalTax(server, listing.sellerUUID, proceeds);

                String buyerName = resolvePlayerName(server, listing.highestBidder);
                SoldListing sale = makeSoldListing(listing.sellerUUID, listing.item, buyerName, proceeds);
                addSoldListing(sale);
                int count = Math.max(1, listing.item.getCount());
                recordSale(listing.item, Math.max(1, listing.highestBid / count), count, PricePoint.AUCTION);
                logMarket(listing.highestBidder, -listing.highestBid, "Won auction: " + count + "× "
                        + sale.itemName, listing.item);
                logMarket(listing.sellerUUID, proceeds, "Auction sold to " + buyerName + ": " + count + "× "
                        + sale.itemName + " (after tax)", listing.item);

                // Notify seller if online
                ServerPlayer seller = server.getPlayerList().getPlayer(listing.sellerUUID);
                if (seller != null) {
                    seller.displayClientMessage(Component.literal(
                            "§a[Market] §eAuction ended! §e" + buyerName
                            + " §cwon §f" + sale.itemName
                            + " §cfor §e" + Currency.format(listing.highestBid)
                            + " §c(§a" + Currency.format(proceeds) + " §cafter tax)§c."
                            + " §eGo to Manage Listings to claim your earnings!"), false);
                    PacketDistributor.sendToPlayer(seller, new SyncSoldListingsPacket(
                            getSoldListingsForPlayer(listing.sellerUUID)));
                }
            } else if (!listing.unpaidUpkeep) {
                // Return unsold item to seller
                deliverItem(server, listing.sellerUUID, listing.item);
                logMarket(listing.sellerUUID, 0, "Listing expired unsold: " + listing.item.getHoverName().getString()
                        + " (returned)", listing.item);
            }
            // Unpaid-upkeep listings that expire unsold are just removed (chunks already freed)
        }

        if (!toRemove.isEmpty()) {
            toRemove.forEach(this::removeListing);
            setDirty();
        }
    }

    /** Deliver now when online, otherwise persist the item until the player logs in. */
    public void deliverItem(MinecraftServer server, UUID playerUUID, ItemStack item) {
        if (item.isEmpty()) return;
        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
        if (player == null) {
            queueDelivery(playerUUID, item, "Market Delivery");
            return;
        }
        deliverToInventory(player, item);
    }

    private static void deliverToInventory(ServerPlayer player, ItemStack item) {
        ItemStack remaining = item.copy();
        player.getInventory().add(remaining);
        if (!remaining.isEmpty()) player.drop(remaining, false);
        com.admin82.factions.network.InventorySync.push(player);
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }

    private static SoldListing makeSoldListing(UUID sellerUUID, ItemStack item,
                                               String buyerName, long proceeds) {
        var sale = new SoldListing();
        sale.saleId     = UUID.randomUUID();
        sale.sellerUUID = sellerUUID;
        sale.item       = item.copy();
        sale.itemName   = item.getHoverName().getString();
        sale.buyerName  = buyerName;
        sale.proceeds   = proceeds;
        sale.soldAt     = System.currentTimeMillis();
        return sale;
    }

    /** Resolves a player's display name; falls back to UUID string. */
    public static String resolvePlayerName(MinecraftServer server, UUID uuid) {
        ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
        if (sp != null) return sp.getGameProfile().getName();
        return server.getProfileCache()
                .get(uuid)
                .map(GameProfile::getName)
                .orElse(uuid.toString().substring(0, 8));
    }

    // Serialization

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        var list = new ListTag();
        for (var listing : listings) list.add(listing.save(reg));
        tag.put("listings", list);
        var sold = new ListTag();
        for (var sale : soldListings) sold.add(sale.save(reg));
        tag.put("soldListings", sold);
        var pending = new ListTag();
        pendingDeliveries.forEach(delivery -> pending.add(delivery.save(reg)));
        tag.put("pendingDeliveries", pending);
        var reminders = new ListTag();
        lastReminderAt.forEach((playerId, timestamp) -> {
            var reminder = new CompoundTag();
            reminder.putUUID("player", playerId);
            reminder.putLong("timestamp", timestamp);
            reminders.add(reminder);
        });
        tag.put("marketReminders", reminders);
        var notices = new ListTag();
        pendingNotices.forEach((playerId, messages) -> {
            var entry = new CompoundTag();
            entry.putUUID("player", playerId);
            var lines = new ListTag();
            messages.forEach(message -> lines.add(StringTag.valueOf(message)));
            entry.put("messages", lines);
            notices.add(entry);
        });
        tag.put("pendingNotices", notices);
        tag.put("marketHistory", EconomyManager.saveHistory(marketHistory, reg));
        var prices = new ListTag();
        priceHistory.forEach((itemId, points) -> {
            var entry = new CompoundTag();
            entry.putString("item", itemId);
            var list2 = new ListTag();
            points.forEach(point -> list2.add(point.save()));
            entry.put("points", list2);
            prices.add(entry);
        });
        tag.put("priceHistory", prices);
        return tag;
    }

    private static MarketManager load(CompoundTag tag, HolderLookup.Provider reg) {
        var manager = new MarketManager();
        var list = tag.getList("listings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) manager.listings.add(MarketListing.load(list.getCompound(i), reg));
        var sold = tag.getList("soldListings", Tag.TAG_COMPOUND);
        for (int i = 0; i < sold.size(); i++) manager.soldListings.add(SoldListing.load(sold.getCompound(i), reg));
        var pending = tag.getList("pendingDeliveries", Tag.TAG_COMPOUND);
        for (int i = 0; i < pending.size(); i++) {
            CompoundTag delivery = pending.getCompound(i);
                if (!delivery.hasUUID("player") && !delivery.hasUUID("buyer")) continue;
                MarketDelivery loaded = MarketDelivery.load(delivery, reg);
                if (!loaded.item.isEmpty()) manager.pendingDeliveries.add(loaded);
        }
        var reminders = tag.getList("marketReminders", Tag.TAG_COMPOUND);
        for (int i = 0; i < reminders.size(); i++) {
            CompoundTag reminder = reminders.getCompound(i);
            if (reminder.hasUUID("player")) {
                manager.lastReminderAt.put(reminder.getUUID("player"), reminder.getLong("timestamp"));
            }
        }
        var notices = tag.getList("pendingNotices", Tag.TAG_COMPOUND);
        for (int i = 0; i < notices.size(); i++) {
            CompoundTag entry = notices.getCompound(i);
            if (!entry.hasUUID("player")) continue;
            var lines = entry.getList("messages", Tag.TAG_STRING);
            List<String> messages = new ArrayList<>();
            for (int line = 0; line < lines.size(); line++) messages.add(lines.getString(line));
            if (!messages.isEmpty()) manager.pendingNotices.put(entry.getUUID("player"), messages);
        }
        EconomyManager.loadHistory(tag.getList("marketHistory", Tag.TAG_COMPOUND), manager.marketHistory, reg);
        var prices = tag.getList("priceHistory", Tag.TAG_COMPOUND);
        for (int i = 0; i < prices.size(); i++) {
            CompoundTag entry = prices.getCompound(i);
            Deque<PricePoint> points = new ArrayDeque<>();
            var pointList = entry.getList("points", Tag.TAG_COMPOUND);
            for (int point = 0; point < pointList.size(); point++) points.addLast(PricePoint.load(pointList.getCompound(point)));
            if (!points.isEmpty()) manager.priceHistory.put(entry.getString("item"), points);
        }
        manager.cleanReminderState();
        return manager;
    }
}
