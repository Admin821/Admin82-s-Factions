package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.Treasury;
import com.admin82.factions.screen.MarketScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client → server: operators ask for the treasury statistics (or reset them). */
public record TreasuryPacket(boolean reset) implements CustomPacketPayload {

    public static final Type<TreasuryPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "treasury_request"));

    public static final StreamCodec<FriendlyByteBuf, TreasuryPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeBoolean(pkt.reset),
            buf -> new TreasuryPacket(buf.readBoolean()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(TreasuryPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!sp.hasPermissions(2)) return; // operators only
            Treasury treasury = Treasury.get(sp.server);
            if (pkt.reset) {
                treasury.reset();
                sp.displayClientMessage(Component.literal("§e[Treasury] Statistics reset."), false);
            }
            PacketDistributor.sendToPlayer(sp, new Sync(treasury.snapshot()));
        });
    }

    /** Server → client: a treasury snapshot. */
    public record Sync(Treasury.Snapshot snapshot) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "treasury_sync"));

        public static final StreamCodec<FriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.of(
                (buf, pkt) -> pkt.snapshot.write(buf),
                buf -> new Sync(Treasury.Snapshot.read(buf)));

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(Sync pkt, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (Minecraft.getInstance().screen instanceof MarketScreen screen) {
                    screen.getMenu().updateTreasury(pkt.snapshot);
                }
            });
        }
    }
}
