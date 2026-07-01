package com.admin82.factions.network.packet;

import com.admin82.factions.economy.MarketListing;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

import static com.admin82.factions.AdminsFactions.MODID;

/**
 * Server → Client: full market listing sync when a player opens the Market block.
 */
public record SyncMarketPacket(
        List<MarketListing> listings,
        long  playerWallet,
        int   myListingCount,
        int   maxListingSlots
) implements CustomPacketPayload {

    public static final Type<SyncMarketPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "sync_market"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncMarketPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.listings.size());
                        for (var l : pkt.listings) l.toNetwork(buf);
                        buf.writeLong(pkt.playerWallet);
                        buf.writeVarInt(pkt.myListingCount);
                        buf.writeVarInt(pkt.maxListingSlots);
                    },
                    buf -> {
                        int size = buf.readVarInt();
                        var list = new ArrayList<MarketListing>(size);
                        for (int i = 0; i < size; i++) list.add(MarketListing.fromNetwork(buf));
                        return new SyncMarketPacket(list, buf.readLong(), buf.readVarInt(), buf.readVarInt());
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncMarketPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof com.admin82.factions.screen.MarketScreen ms) {
                ms.updateListings(pkt.listings(), pkt.playerWallet(), pkt.myListingCount(), pkt.maxListingSlots());
            }
        });
    }
}
