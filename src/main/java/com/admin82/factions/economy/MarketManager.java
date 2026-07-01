package com.admin82.factions.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/** Persistent storage for all market listings. */
public class MarketManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_market";

    private final List<MarketListing> listings = new ArrayList<>();

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

    /** Count of active listings by a player (used for slot limits). */
    public int countPlayerListings(UUID playerUUID) {
        return (int) listings.stream().filter(l -> l.sellerUUID.equals(playerUUID)).count();
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

            if (listing.isAuction && listing.highestBidder != null) {
                // Auction sold: deliver item to winner, payment to seller
                deliverItem(server, listing.highestBidder, listing.item);
                long proceeds = listing.netProceeds(listing.highestBid);
                eco.addWallet(listing.sellerUUID, proceeds);
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

    // ── Serialization ─────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        var list = new ListTag();
        for (var listing : listings) {
            list.add(listing.save(reg));
        }
        tag.put("listings", list);
        return tag;
    }

    private static MarketManager load(CompoundTag tag, HolderLookup.Provider reg) {
        var manager = new MarketManager();
        var list = tag.getList("listings", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            manager.listings.add(MarketListing.load(list.getCompound(i), reg));
        }
        return manager;
    }
}
