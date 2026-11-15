package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.PricePoint;
import com.admin82.factions.screen.MarketScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Server → client: a summary of every item that has sold on the market, for the Stocks page. */
public record SyncStocksPacket(List<Line> lines) implements CustomPacketPayload {

    /**
     * One item. Changes are percentages, or NaN when there's no sale far enough back to compare with.
     * {@code spark} is the price of each of the latest sales, oldest first.
     */
    public record Line(String itemId, long lastPrice, double change24h, double change7d, long volume7d,
                       int sales, long lastSoldAt, List<Long> spark) {}

    public static final long DAY_MS = 86_400_000L;
    private static final int SPARK_POINTS = 16;

    public static final Type<SyncStocksPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_stocks"));

    public static final StreamCodec<FriendlyByteBuf, SyncStocksPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.lines.size());
                for (Line l : p.lines) {
                    buf.writeUtf(l.itemId, 256);
                    buf.writeVarLong(l.lastPrice);
                    buf.writeDouble(l.change24h);
                    buf.writeDouble(l.change7d);
                    buf.writeVarLong(l.volume7d);
                    buf.writeVarInt(l.sales);
                    buf.writeLong(l.lastSoldAt);
                    buf.writeVarInt(l.spark.size());
                    l.spark.forEach(buf::writeVarLong);
                }
            },
            buf -> {
                int n = buf.readVarInt();
                List<Line> lines = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    String id = buf.readUtf(256);
                    long last = buf.readVarLong();
                    double c24 = buf.readDouble(), c7 = buf.readDouble();
                    long vol = buf.readVarLong();
                    int sales = buf.readVarInt();
                    long lastAt = buf.readLong();
                    int s = buf.readVarInt();
                    List<Long> spark = new ArrayList<>(s);
                    for (int k = 0; k < s; k++) spark.add(buf.readVarLong());
                    lines.add(new Line(id, last, c24, c7, vol, sales, lastAt, spark));
                }
                return new SyncStocksPacket(lines);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Builds one line per item from its sale history (points are oldest first). */
    public static SyncStocksPacket build(Map<String, List<PricePoint>> history) {
        long now = System.currentTimeMillis();
        List<Line> lines = new ArrayList<>();
        history.forEach((itemId, points) -> {
            if (points.isEmpty()) return;
            PricePoint latest = points.get(points.size() - 1);
            long volume = 0;
            for (PricePoint p : points) if (now - p.time() <= 7 * DAY_MS) volume += p.quantity();
            List<Long> spark = new ArrayList<>();
            for (PricePoint p : points.subList(Math.max(0, points.size() - SPARK_POINTS), points.size())) spark.add(p.unitPrice());
            lines.add(new Line(itemId, latest.unitPrice(), change(points, latest, DAY_MS), change(points, latest, 7 * DAY_MS),
                    volume, points.size(), latest.time(), spark));
        });
        return new SyncStocksPacket(lines);
    }

    /** Latest price compared with the last sale at least {@code window} before it, as a percentage. */
    private static double change(List<PricePoint> points, PricePoint latest, long window) {
        PricePoint before = null;
        for (PricePoint p : points) if (p.time() <= latest.time() - window) before = p;
        if (before == null || before.unitPrice() <= 0) return Double.NaN;
        return (latest.unitPrice() - before.unitPrice()) * 100.0 / before.unitPrice();
    }

    public static void handle(SyncStocksPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof MarketScreen market) market.getMenu().updateStocks(p.lines);
        });
    }
}
