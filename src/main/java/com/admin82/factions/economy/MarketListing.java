package com.admin82.factions.economy;

import com.admin82.factions.Config;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Represents a single listing on the faction market.
 * Can be either a fixed-price BIN or an auction.
 */
public class MarketListing {

    public enum ListingKind {
        PLAYER_SELL,
        PLAYER_BUY_ORDER,
        ADMIN_SELL,
        ADMIN_BUY_ORDER
    }

    public UUID   listingId;
    public UUID   sellerUUID;
    /** May be null if seller has no faction. */
    @Nullable public UUID sellerFactionId;
    public ItemStack item;
    public boolean isAuction;
    /** BIN price, or auction starting/minimum bid, in copper. */
    public long   price;
    /** Highest bid so far (auction only). */
    public long   highestBid;
    @Nullable public UUID highestBidder;
    /** System time (ms) when this listing expires. */
    public long   expiresAt;
    /** Duration selected by seller in hours (for tax calculation). */
    public int    durationHours;
    /** True if this listing was created by the upkeep system due to unpaid fees. */
    public boolean unpaidUpkeep;
    /** What kind of market row this is: normal sale, buy order, or admin/server listing. */
    public ListingKind kind = ListingKind.PLAYER_SELL;

    // Buy orders
    // For buy orders, {@link #item} is a one-item template and {@link #price} is the price PER ITEM.
    /** Total items wanted. 0 = unlimited (server buy orders). */
    public int orderQuantity;
    /** Items delivered so far. */
    public int orderFilled;
    /** Copper still held from the buyer's wallet to pay future fills (player buy orders). */
    public long escrow;

    /** Largest quantity a single player buy order can ask for. */
    public static final int MAX_ORDER_QUANTITY = 6400;

    public boolean isUnlimitedOrder() {
        return isBuyOrder() && orderQuantity <= 0;
    }

    /** Items still wanted by this buy order. */
    public int remainingQuantity() {
        if (!isBuyOrder()) return 0;
        return orderQuantity <= 0 ? Integer.MAX_VALUE : Math.max(0, orderQuantity - orderFilled);
    }

    public boolean isBuyOrder() {
        return kind == ListingKind.PLAYER_BUY_ORDER || kind == ListingKind.ADMIN_BUY_ORDER;
    }

    public boolean isAdminListing() {
        return kind == ListingKind.ADMIN_SELL || kind == ListingKind.ADMIN_BUY_ORDER;
    }

    // Tax calculation

    /**
     * Tax as a fraction (0.0 – 1.0).
     * BIN: 10 %. Auction: 10 % (4 h) → 24 % (24 h).
     */
    public double taxFraction() {
        // Read from the config (these used to be hard-coded and ignored the config file).
        if (!isAuction) return Config.MARKET_TAX_BIN_PERCENT.get() / 100.0;
        return switch (durationHours) {
            case 8  -> Config.MARKET_TAX_AUCTION_8H.get() / 100.0;
            case 12 -> Config.MARKET_TAX_AUCTION_12H.get() / 100.0;
            case 16 -> Config.MARKET_TAX_AUCTION_16H.get() / 100.0;
            case 24 -> Config.MARKET_TAX_AUCTION_24H.get() / 100.0;
            default -> Config.MARKET_TAX_AUCTION_4H.get() / 100.0;
        };
    }

    /** Net copper received by seller after tax on finalPrice. */
    public long netProceeds(long finalPrice) {
        long tax = (long) Math.ceil(finalPrice * taxFraction());
        return Math.max(0, finalPrice - tax);
    }

    /** Place or raise a bid. Returns false if bid is not higher than current. */
    public boolean placeBid(UUID bidder, long bidAmount) {
        if (bidAmount <= highestBid || bidAmount < price) return false;
        highestBid = bidAmount;
        highestBidder = bidder;
        return true;
    }

    // Serialization (NBT)

    public CompoundTag save(HolderLookup.Provider reg) {
        var tag = new CompoundTag();
        tag.putUUID("listingId", listingId);
        tag.putUUID("sellerUUID", sellerUUID);
        if (sellerFactionId != null) tag.putUUID("sellerFactionId", sellerFactionId);
        tag.put("item", item.isEmpty() ? new CompoundTag() : item.save(reg));
        tag.putBoolean("isAuction", isAuction);
        tag.putLong("price", price);
        tag.putLong("highestBid", highestBid);
        if (highestBidder != null) tag.putUUID("highestBidder", highestBidder);
        tag.putLong("expiresAt", expiresAt);
        tag.putInt("durationHours", durationHours);
        tag.putBoolean("unpaidUpkeep", unpaidUpkeep);
        tag.putString("kind", kind.name());
        tag.putInt("orderQuantity", orderQuantity);
        tag.putInt("orderFilled", orderFilled);
        tag.putLong("escrow", escrow);
        return tag;
    }

    public static MarketListing load(CompoundTag tag, HolderLookup.Provider reg) {
        var l = new MarketListing();
        l.listingId   = tag.getUUID("listingId");
        l.sellerUUID  = tag.getUUID("sellerUUID");
        l.sellerFactionId = tag.hasUUID("sellerFactionId") ? tag.getUUID("sellerFactionId") : null;
        l.item        = ItemStack.parseOptional(reg, tag.getCompound("item"));
        l.isAuction   = tag.getBoolean("isAuction");
        l.price       = tag.getLong("price");
        l.highestBid  = tag.getLong("highestBid");
        l.highestBidder = tag.hasUUID("highestBidder") ? tag.getUUID("highestBidder") : null;
        l.expiresAt   = tag.getLong("expiresAt");
        l.durationHours = tag.getInt("durationHours");
        l.unpaidUpkeep = tag.getBoolean("unpaidUpkeep");
        if (tag.contains("kind", Tag.TAG_STRING)) {
            try { l.kind = ListingKind.valueOf(tag.getString("kind")); }
            catch (IllegalArgumentException ignored) { l.kind = ListingKind.PLAYER_SELL; }
        }
        if (tag.contains("orderQuantity", Tag.TAG_INT)) {
            l.orderQuantity = tag.getInt("orderQuantity");
            l.orderFilled = tag.getInt("orderFilled");
            l.escrow = tag.getLong("escrow");
        } else if (l.isBuyOrder()) {
            // Legacy buy order: one stack for one total price. Convert to quantity + price per item.
            int count = Math.max(1, l.item.getCount());
            l.orderQuantity = l.kind == ListingKind.ADMIN_BUY_ORDER ? 0 : count;
            l.escrow = l.kind == ListingKind.PLAYER_BUY_ORDER ? l.price : 0L;
            l.price = Math.max(1L, l.price / count);
            l.item = l.item.copyWithCount(1);
        }
        return l;
    }

    // Network

    public void toNetwork(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(listingId);
        buf.writeUUID(sellerUUID);
        buf.writeBoolean(sellerFactionId != null);
        if (sellerFactionId != null) buf.writeUUID(sellerFactionId);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, item.isEmpty() ? ItemStack.EMPTY : item);
        buf.writeBoolean(isAuction);
        buf.writeLong(price);
        buf.writeLong(highestBid);
        buf.writeBoolean(highestBidder != null);
        if (highestBidder != null) buf.writeUUID(highestBidder);
        buf.writeLong(expiresAt);
        buf.writeInt(durationHours);
        buf.writeBoolean(unpaidUpkeep);
        buf.writeVarInt(kind.ordinal());
        buf.writeVarInt(orderQuantity);
        buf.writeVarInt(orderFilled);
        buf.writeLong(escrow);
    }

    public static MarketListing fromNetwork(RegistryFriendlyByteBuf buf) {
        var l = new MarketListing();
        l.listingId       = buf.readUUID();
        l.sellerUUID      = buf.readUUID();
        l.sellerFactionId = buf.readBoolean() ? buf.readUUID() : null;
        l.item            = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        l.isAuction       = buf.readBoolean();
        l.price           = buf.readLong();
        l.highestBid      = buf.readLong();
        l.highestBidder   = buf.readBoolean() ? buf.readUUID() : null;
        l.expiresAt       = buf.readLong();
        l.durationHours   = buf.readInt();
        l.unpaidUpkeep    = buf.readBoolean();
        int kindOrdinal    = buf.readVarInt();
        l.kind = kindOrdinal >= 0 && kindOrdinal < ListingKind.values().length
            ? ListingKind.values()[kindOrdinal]
            : ListingKind.PLAYER_SELL;
        l.orderQuantity = buf.readVarInt();
        l.orderFilled = buf.readVarInt();
        l.escrow = buf.readLong();
        return l;
    }
}
