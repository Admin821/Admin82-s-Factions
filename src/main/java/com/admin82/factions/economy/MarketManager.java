package com.admin82.factions.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.network.PacketDistributor;
import com.admin82.factions.network.packet.SyncSoldListingsPacket;

import javax.annotation.Nullable;
import java.util.*;

/** Persistent storage for all market listings. */
public class MarketManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_market";
    private static final long REMINDER_INTERVAL_MS = 3_600_000L;

    private final List<MarketListing> listings     = new ArrayList<>();
    private final List<SoldListing>   soldListings  = new ArrayList<>();
    private final List<MarketDelivery> pendingDeliveries = new ArrayList<>();
    private final Map<UUID, Long> lastReminderAt = new HashMap<>();

    // ── Singleton ─────────────────────────────────────────────────────────────

    public static MarketManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new Factory<>(MarketManager::new, MarketManager::load),
            DATA_NAME
        );
    }

    // ── API ───────────────────────────────────────────────────────────────────

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

    /** Count of active non-admin listings by a player (used for slot limits). */
    public int countPlayerListings(UUID playerUUID) {
        return (int) listings.stream().filter(l -> l.sellerUUID.equals(playerUUID) && !l.isAdminListing()).count();
    }

    /** Count of active permanent server shop sell listings. */
    public int countServerShopListings() {
        return (int) listings.stream().filter(l -> l.kind == MarketListing.ListingKind.ADMIN_SELL).count();
    }

    // ── Sold listings (unclaimed proceeds) ─────────────────────────────

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
        MarketDelivery delivery = new MarketDelivery();
        delivery.deliveryId = UUID.randomUUID();
        delivery.playerUUID = playerUUID;
        delivery.item = item.copy();
        delivery.reason = reason;
        delivery.createdAt = System.currentTimeMillis();
        pendingDeliveries.add(delivery);
        lastReminderAt.put(playerUUID, System.currentTimeMillis());
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
                eco.addWallet(listing.sellerUUID, listing.price);
                ServerPlayer buyer = server.getPlayerList().getPlayer(listing.sellerUUID);
                if (buyer != null) {
                    buyer.displayClientMessage(Component.literal(
                            "§e[Market] Buy order expired. Refunded §a" + Currency.format(listing.price) + "§e."), false);
                }
            } else if (listing.kind == MarketListing.ListingKind.ADMIN_BUY_ORDER || listing.kind == MarketListing.ListingKind.ADMIN_SELL) {
                // Server/admin listings are not backed by player escrow or inventory.
            } else if (listing.isAuction && listing.highestBidder != null) {
                // Auction sold: deliver item to winner, create a claimable SoldListing for seller
                deliverItem(server, listing.highestBidder, listing.item);
                long proceeds = listing.netProceeds(listing.highestBid);

                String buyerName = resolvePlayerName(server, listing.highestBidder);
                SoldListing sale = makeSoldListing(listing.sellerUUID, listing.item, buyerName, proceeds);
                addSoldListing(sale);

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
        player.inventoryMenu.broadcastChanges();
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
    private static String resolvePlayerName(MinecraftServer server, UUID uuid) {
        ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
        if (sp != null) return sp.getGameProfile().getName();
        return server.getProfileCache()
                .get(uuid)
                .map(com.mojang.authlib.GameProfile::getName)
                .orElse(uuid.toString().substring(0, 8));
    }

    // ── Serialization ─────────────────────────────────────────────────────────

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
        manager.cleanReminderState();
        return manager;
    }
}
