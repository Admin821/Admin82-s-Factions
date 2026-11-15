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

import java.util.List;

/** Server → client: completed sales of one item (oldest first), for price stats and the chart. */
public record SyncPriceStatsPacket(String itemId, List<PricePoint> points) implements CustomPacketPayload {
    public static final Type<SyncPriceStatsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_price_stats"));

    public static final StreamCodec<FriendlyByteBuf, SyncPriceStatsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.itemId, 256);
                PricePoint.writeList(buf, packet.points);
            },
            buf -> new SyncPriceStatsPacket(buf.readUtf(256), PricePoint.readList(buf)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncPriceStatsPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof MarketScreen market) {
                market.getMenu().updatePriceStats(packet.itemId, packet.points);
            }
        });
    }
}
