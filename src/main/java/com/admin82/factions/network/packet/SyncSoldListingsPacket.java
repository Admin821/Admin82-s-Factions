package com.admin82.factions.network.packet;

import com.admin82.factions.economy.SoldListing;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

import static com.admin82.factions.AdminsFactions.MODID;

/**
 * Server → Client: push the player's pending sold-listing proceeds.
 * Received by {@link com.admin82.factions.screen.MarketScreen}.
 */
public record SyncSoldListingsPacket(List<SoldListing> soldListings) implements CustomPacketPayload {

    public static final Type<SyncSoldListingsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "sync_sold_listings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncSoldListingsPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.soldListings().size());
                        pkt.soldListings().forEach(s -> s.toNetwork(buf));
                    },
                    buf -> {
                        int n = buf.readVarInt();
                        var list = new ArrayList<SoldListing>(n);
                        for (int i = 0; i < n; i++) list.add(SoldListing.fromNetwork(buf));
                        return new SyncSoldListingsPacket(List.copyOf(list));
                    }
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncSoldListingsPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof com.admin82.factions.screen.MarketScreen ms) {
                ms.updateSoldListings(pkt.soldListings());
            }
        });
    }
}
