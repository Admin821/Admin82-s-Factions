package com.admin82.factions.network.packet;

import com.admin82.factions.screen.CurrencyExchangeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.admin82.factions.AdminsFactions.MODID;

/** Server → client: current exchange rates, whether buying is enabled, and the player's wallet. */
public record SyncExchangePacket(Map<String, Long> rates, boolean twoWay, long wallet) implements CustomPacketPayload {

    public static final Type<SyncExchangePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "sync_exchange"));

    public static final StreamCodec<FriendlyByteBuf, SyncExchangePacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarInt(pkt.rates.size());
                pkt.rates.forEach((id, rate) -> { buf.writeUtf(id, 256); buf.writeLong(rate); });
                buf.writeBoolean(pkt.twoWay);
                buf.writeLong(pkt.wallet);
            },
            buf -> {
                int size = buf.readVarInt();
                Map<String, Long> rates = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) rates.put(buf.readUtf(256), buf.readLong());
                return new SyncExchangePacket(rates, buf.readBoolean(), buf.readLong());
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncExchangePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof CurrencyExchangeScreen screen) {
                screen.getMenu().updateData(pkt.rates, pkt.twoWay, pkt.wallet);
            }
        });
    }
}
