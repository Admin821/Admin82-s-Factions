package com.admin82.factions.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Represents a single listing on the faction market.
 * Can be either a fixed-price BIN or an auction.
 */
public class MarketListing {

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

    // ── Tax calculation ───────────────────────────────────────────────────────

    /**
     * Tax as a fraction (0.0 – 1.0).
     * BIN: 10 %. Auction: 10 % (4 h) → 24 % (24 h).
     */
    public double taxFraction() {
        if (!isAuction) return 0.10;
        return switch (durationHours) {
            case 4  -> 0.10;
            case 8  -> 0.12;
            case 12 -> 0.16;
            case 16 -> 0.20;
            case 24 -> 0.24;
            default -> 0.10;
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

    // ── Serialization (NBT) ───────────────────────────────────────────────────

    public CompoundTag save(HolderLookup.Provider reg) {
        var tag = new CompoundTag();
        tag.putUUID("listingId", listingId);
        tag.putUUID("sellerUUID", sellerUUID);
        if (sellerFactionId != null) tag.putUUID("sellerFactionId", sellerFactionId);
        tag.put("item", item.isEmpty() ? new net.minecraft.nbt.CompoundTag() : item.save(reg));
        tag.putBoolean("isAuction", isAuction);
        tag.putLong("price", price);
        tag.putLong("highestBid", highestBid);
        if (highestBidder != null) tag.putUUID("highestBidder", highestBidder);
        tag.putLong("expiresAt", expiresAt);
        tag.putInt("durationHours", durationHours);
        tag.putBoolean("unpaidUpkeep", unpaidUpkeep);
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
        return l;
    }

    // ── Network ───────────────────────────────────────────────────────────────

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
        return l;
    }
}
