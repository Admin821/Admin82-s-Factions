package com.admin82.factions.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public class MarketDelivery {
    public UUID deliveryId;
    public UUID playerUUID;
    public ItemStack item;
    public String reason;
    public long createdAt;

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("deliveryId", deliveryId);
        tag.putUUID("player", playerUUID);
        tag.put("item", item.save(registries));
        tag.putString("reason", reason);
        tag.putLong("createdAt", createdAt);
        return tag;
    }

    public static MarketDelivery load(CompoundTag tag, HolderLookup.Provider registries) {
        MarketDelivery delivery = new MarketDelivery();
        delivery.deliveryId = tag.hasUUID("deliveryId") ? tag.getUUID("deliveryId") : UUID.randomUUID();
        delivery.playerUUID = tag.hasUUID("player") ? tag.getUUID("player") : tag.getUUID("buyer");
        delivery.item = ItemStack.parseOptional(registries, tag.getCompound("item"));
        delivery.reason = tag.contains("reason") ? tag.getString("reason") : "Market Delivery";
        delivery.createdAt = tag.getLong("createdAt");
        return delivery;
    }

    public void toNetwork(RegistryFriendlyByteBuf buf) {
        buf.writeUUID(deliveryId);
        buf.writeUUID(playerUUID);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, item);
        buf.writeUtf(reason, 64);
        buf.writeLong(createdAt);
    }

    public static MarketDelivery fromNetwork(RegistryFriendlyByteBuf buf) {
        MarketDelivery delivery = new MarketDelivery();
        delivery.deliveryId = buf.readUUID();
        delivery.playerUUID = buf.readUUID();
        delivery.item = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        delivery.reason = buf.readUtf(64);
        delivery.createdAt = buf.readLong();
        return delivery;
    }
}