package com.admin82.factions.network.packet;

import com.admin82.factions.economy.EconomyManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;

import static com.admin82.factions.AdminsFactions.MODID;

/**
 * Server → Client: pushes updated wallet + vault balance to the client.
 */
public record SyncEconomyPacket(long playerWallet, long factionVault) implements CustomPacketPayload {

    public static final Type<SyncEconomyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "sync_economy"));

    public static final StreamCodec<FriendlyByteBuf, SyncEconomyPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> { buf.writeLong(pkt.playerWallet); buf.writeLong(pkt.factionVault); },
                    buf -> new SyncEconomyPacket(buf.readLong(), buf.readLong())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncEconomyPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var screen = net.minecraft.client.Minecraft.getInstance().screen;
            if (screen instanceof com.admin82.factions.screen.FactionTableScreen fts) {
                fts.updateEconomy(pkt.playerWallet(), pkt.factionVault());
            }
        });
    }
}
