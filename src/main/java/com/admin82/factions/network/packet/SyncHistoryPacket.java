package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.Transaction;
import com.admin82.factions.screen.FactionTableScreen;
import com.admin82.factions.screen.MarketScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Server → client: a list of history entries (market activity, wallet or faction vault). */
public record SyncHistoryPacket(HistoryRequestPacket.Scope scope, List<Transaction> entries) implements CustomPacketPayload {
    public static final Type<SyncHistoryPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_history"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncHistoryPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.scope.ordinal());
                Transaction.writeList(buf, packet.entries);
            },
            buf -> new SyncHistoryPacket(HistoryRequestPacket.Scope.values()[buf.readVarInt()], Transaction.readList(buf)));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncHistoryPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            var screen = Minecraft.getInstance().screen;
            if (screen instanceof MarketScreen market) {
                market.getMenu().updateHistory(packet.scope, packet.entries);
            } else if (screen instanceof FactionTableScreen table) {
                table.getMenu().updateHistory(packet.scope, packet.entries);
            }
        });
    }
}
