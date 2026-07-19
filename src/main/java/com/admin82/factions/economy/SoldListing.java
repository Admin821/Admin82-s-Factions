package com.admin82.factions.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * A record of a market sale where proceeds are held until the seller
 * manually claims them from the "My Listings" tab.
 */
public class SoldListing {

    public UUID      saleId;
    public UUID      sellerUUID;
    /** A copy of the item that was sold (used only for display). */
    public ItemStack item;
    /** Cached hover-name of the item so it is readable when item is EMPTY. */
    public String    itemName;
    /** Display name of the buyer. */
    public String    buyerName;
    /** Net proceeds in copper (after all taxes). */
    public long      proceeds;
    /** Epoch-ms when the sale occurred. */
    public long      soldAt;

    public SoldListing() {}

    // ── NBT ───────────────────────────────────────────────────────────────────

    public CompoundTag save(HolderLookup.Provider reg) {
        var tag = new CompoundTag();
        tag.putUUID("saleId",    saleId);
        tag.putUUID("seller",    sellerUUID);
        tag.put("item",          item.isEmpty() ? new CompoundTag() : item.save(reg));
        tag.putString("itemName", itemName);
        tag.putString("buyer",   buyerName);
        tag.putLong("proceeds",  proceeds);
        tag.putLong("soldAt",    soldAt);
        return tag;
    }

    public static SoldListing load(CompoundTag tag, HolderLookup.Provider reg) {
        var s = new SoldListing();
        s.saleId     = tag.getUUID("saleId");
        s.sellerUUID = tag.getUUID("seller");
        s.item       = ItemStack.parseOptional(reg, tag.getCompound("item"));
        s.itemName   = tag.getString("itemName");
        s.buyerName  = tag.getString("buyer");
        s.proceeds   = tag.getLong("proceeds");
        s.soldAt     = tag.getLong("soldAt");
        return s;
    }

    // ── Network ───────────────────────────────────────────────────────────────

    public void toNetwork(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(saleId);
        buf.writeUUID(sellerUUID);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, item.isEmpty() ? ItemStack.EMPTY : item);
        buf.writeUtf(itemName, 256);
        buf.writeUtf(buyerName, 64);
        buf.writeLong(proceeds);
        buf.writeLong(soldAt);
    }

    public static SoldListing fromNetwork(RegistryFriendlyByteBuf buf) {
        var s = new SoldListing();
        s.saleId     = buf.readUUID();
        s.sellerUUID = buf.readUUID();
        s.item       = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        s.itemName   = buf.readUtf(256);
        s.buyerName  = buf.readUtf(64);
        s.proceeds   = buf.readLong();
        s.soldAt     = buf.readLong();
        return s;
    }
}
