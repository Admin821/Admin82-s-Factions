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

    private final List<MarketListing> listings     = new ArrayList<>();
    private final List<SoldListing>   soldListings  = new ArrayList<>();

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
        setDirty();
    }

    public List<SoldListing> getSoldListingsForPlayer(UUID playerUUID) {
        return soldListings.stream().filter(s -> s.sellerUUID.equals(playerUUID)).toList();
    }

    public boolean claimSoldListing(UUID saleId) {
        boolean removed = soldListings.removeIf(s -> s.saleId.equals(saleId));
        if (removed) setDirty();
        return removed;
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
                soldListings.add(sale);

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

    /** Deliver an item to a player; drops at their feet if inventory is full. */
    private static void deliverItem(MinecraftServer server, UUID playerUUID, ItemStack item) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerUUID);
        if (player != null) {
            if (!player.getInventory().add(item.copy())) {
                player.drop(item.copy(), false);
            }
        }
        // If offline, item is lost (simplification — could add a mailbox system later)
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
        return tag;
    }

    private static MarketManager load(CompoundTag tag, HolderLookup.Provider reg) {
        var manager = new MarketManager();
        var list = tag.getList("listings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) manager.listings.add(MarketListing.load(list.getCompound(i), reg));
        var sold = tag.getList("soldListings", Tag.TAG_COMPOUND);
        for (int i = 0; i < sold.size(); i++) manager.soldListings.add(SoldListing.load(sold.getCompound(i), reg));
        return manager;
    }
}
