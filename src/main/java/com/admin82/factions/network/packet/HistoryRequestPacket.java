package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.economy.PricePoint;
import com.admin82.factions.economy.Transaction;
import com.admin82.factions.faction.FactionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Client → server: ask for a history list or for an item's price history.
 * The server answers with {@link SyncHistoryPacket} or {@link SyncPriceStatsPacket}.
 */
public record HistoryRequestPacket(Scope scope, String itemId) implements CustomPacketPayload {
    public enum Scope { MARKET, WALLET, VAULT, PRICES, STOCKS }

    public static final Type<HistoryRequestPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "history_request"));

    public static final StreamCodec<FriendlyByteBuf, HistoryRequestPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.scope.ordinal());
                buf.writeUtf(packet.itemId, 256);
            },
            buf -> new HistoryRequestPacket(Scope.values()[Math.clamp(buf.readVarInt(), 0, Scope.values().length - 1)],
                    buf.readUtf(256)));

    public HistoryRequestPacket(Scope scope) {
        this(scope, "");
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(HistoryRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var server = player.server;
            if (packet.scope == Scope.STOCKS) {
                PacketDistributor.sendToPlayer(player, SyncStocksPacket.build(MarketManager.get(server).allPriceHistory()));
                return;
            }
            if (packet.scope == Scope.PRICES) {
                List<PricePoint> points = MarketManager.get(server).getPriceHistory(packet.itemId);
                PacketDistributor.sendToPlayer(player, new SyncPriceStatsPacket(packet.itemId, points));
                return;
            }
            List<Transaction> entries = switch (packet.scope) {
                case MARKET -> MarketManager.get(server).getMarketHistory(player.getUUID());
                case WALLET -> EconomyManager.get(server).getWalletHistory(player.getUUID());
                case VAULT -> {
                    var faction = FactionManager.get(server).getFactionForPlayer(player.getUUID());
                    yield faction == null ? List.of() : EconomyManager.get(server).getVaultHistory(faction.getId());
                }
                default -> List.of();
            };
            PacketDistributor.sendToPlayer(player, new SyncHistoryPacket(packet.scope, entries));
        });
    }
}
