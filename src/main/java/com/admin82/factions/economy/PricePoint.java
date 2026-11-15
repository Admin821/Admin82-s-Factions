package com.admin82.factions.economy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * One completed market sale of an item, used for price history and charts.
 *
 * @param time      when it sold (epoch ms)
 * @param unitPrice price per single item, in copper
 * @param quantity  how many items changed hands
 * @param source    0 = Buy Now, 1 = auction, 2 = buy order
 */
public record PricePoint(long time, long unitPrice, int quantity, int source) {
    public static final int BUY_NOW = 0;
    public static final int AUCTION = 1;
    public static final int BUY_ORDER = 2;
    /** Sales remembered per item. */
    public static final int MAX_POINTS = 300;

    public String sourceName() {
        return switch (source) {
            case AUCTION -> "Auction";
            case BUY_ORDER -> "Buy order";
            default -> "Buy Now";
        };
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("t", time);
        tag.putLong("p", unitPrice);
        tag.putInt("q", quantity);
        tag.putByte("s", (byte) source);
        return tag;
    }

    public static PricePoint load(CompoundTag tag) {
        return new PricePoint(tag.getLong("t"), tag.getLong("p"), tag.getInt("q"), tag.getByte("s"));
    }

    public static void writeList(FriendlyByteBuf buf, List<PricePoint> points) {
        buf.writeVarInt(points.size());
        for (PricePoint point : points) {
            buf.writeLong(point.time);
            buf.writeVarLong(point.unitPrice);
            buf.writeVarInt(point.quantity);
            buf.writeByte(point.source);
        }
    }

    public static List<PricePoint> readList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        var list = new ArrayList<PricePoint>(size);
        for (int i = 0; i < size; i++) list.add(new PricePoint(buf.readLong(), buf.readVarLong(), buf.readVarInt(), buf.readByte()));
        return list;
    }
}
