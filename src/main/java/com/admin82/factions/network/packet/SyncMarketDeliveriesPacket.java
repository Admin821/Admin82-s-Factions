package com.admin82.factions.network.packet;

import com.admin82.factions.economy.MarketDelivery;
import com.admin82.factions.screen.MarketScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

import static com.admin82.factions.AdminsFactions.MODID;

public record SyncMarketDeliveriesPacket(List<MarketDelivery> deliveries) implements CustomPacketPayload {
    public static final Type<SyncMarketDeliveriesPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MODID, "sync_market_deliveries"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMarketDeliveriesPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> {
                buf.writeVarInt(packet.deliveries.size());
                packet.deliveries.forEach(delivery -> delivery.toNetwork(buf));
            }, buf -> {
                int count = buf.readVarInt();
                List<MarketDelivery> deliveries = new ArrayList<>(count);
                for (int i = 0; i < count; i++) deliveries.add(MarketDelivery.fromNetwork(buf));
                return new SyncMarketDeliveriesPacket(List.copyOf(deliveries));
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncMarketDeliveriesPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof MarketScreen screen) {
                screen.updateDeliveries(packet.deliveries);
            }
        });
    }
}