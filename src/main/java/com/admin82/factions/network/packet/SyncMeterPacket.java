package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.menu.MeterMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Server → client: a meter's state, the listings its viewer can see, and the hourly average prices (one history for
 * power, one per fluid that sold recently). A buyer's {@code maxPriceCopper} and fluid only go to its owner.
 */
public record SyncMeterPacket(BlockPos pos, int kind, boolean isOwner, String ownerName, int role, @Nullable UUID networkId,
                              long coins, long coinCap, String coinEta, long maxPriceCopper, long maxPerUnits, long stored, long capacity, String content, long rate, int efficiency,
                              String status, int[] sides, List<NetworkView> networks,
                              String wantFluid, String tankFluid, List<PriceHistory> histories) implements CustomPacketPayload {

    /** One market's last 24 hours, oldest first: average price (micro per unit, -1 = nothing sold) and units sold. */
    public record PriceHistory(String fluid, long[] micro, long[] units) {}

    /**
     * One network. {@code stock} is what its loaded suppliers hold; {@code earned} is -1 unless it's yours.
     * {@code access}: 0 public, 1 password, 2 faction only.
     */
    public record NetworkView(UUID id, String name, String ownerName, int access, long priceCopper, long perUnits,
                              long stock, int suppliersOnline, int suppliers, String content, long unitsSold,
                              boolean mine, long earned) {}

    public static final Type<SyncMeterPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_meter"));

    public static final StreamCodec<FriendlyByteBuf, SyncMeterPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeVarInt(p.kind);
                buf.writeBoolean(p.isOwner);
                buf.writeUtf(p.ownerName, 64);
                buf.writeVarInt(p.role);
                buf.writeBoolean(p.networkId != null);
                if (p.networkId != null) buf.writeUUID(p.networkId);
                buf.writeVarLong(p.coins);
                buf.writeVarLong(p.coinCap);
                buf.writeUtf(p.coinEta, 128);
                buf.writeLong(p.maxPriceCopper);
                buf.writeVarLong(p.maxPerUnits);
                buf.writeVarLong(p.stored);
                buf.writeVarLong(p.capacity);
                buf.writeUtf(p.content, 128);
                buf.writeVarLong(p.rate);
                buf.writeVarInt(p.efficiency);
                buf.writeUtf(p.status, 128);
                for (int i = 0; i < 4; i++) buf.writeVarInt(p.sides[i]);
                buf.writeVarInt(p.networks.size());
                for (NetworkView n : p.networks) {
                    buf.writeUUID(n.id); buf.writeUtf(n.name, 64); buf.writeUtf(n.ownerName, 64); buf.writeVarInt(n.access);
                    buf.writeVarLong(n.priceCopper); buf.writeVarLong(n.perUnits); buf.writeVarLong(n.stock);
                    buf.writeVarInt(n.suppliersOnline); buf.writeVarInt(n.suppliers); buf.writeUtf(n.content, 128);
                    buf.writeVarLong(n.unitsSold); buf.writeBoolean(n.mine); buf.writeLong(n.earned);
                }
                buf.writeUtf(p.wantFluid, 128);
                buf.writeUtf(p.tankFluid, 128);
                buf.writeVarInt(p.histories.size());
                for (PriceHistory h : p.histories) {
                    buf.writeUtf(h.fluid, 128);
                    buf.writeVarInt(h.micro.length);
                    for (int i = 0; i < h.micro.length; i++) { buf.writeLong(h.micro[i]); buf.writeVarLong(h.units[i]); }
                }
            },
            buf -> {
                BlockPos pos = buf.readBlockPos();
                int kind = buf.readVarInt();
                boolean owner = buf.readBoolean();
                String ownerName = buf.readUtf(64);
                int role = buf.readVarInt();
                UUID net = buf.readBoolean() ? buf.readUUID() : null;
                long coins = buf.readVarLong(), coinCap = buf.readVarLong();
                String coinEta = buf.readUtf(128);
                long maxPrice = buf.readLong(), maxPer = buf.readVarLong(), stored = buf.readVarLong(), cap = buf.readVarLong();
                String content = buf.readUtf(128);
                long rate = buf.readVarLong();
                int eff = buf.readVarInt();
                String status = buf.readUtf(128);
                int[] sides = new int[4];
                for (int i = 0; i < 4; i++) sides[i] = buf.readVarInt();
                int n = buf.readVarInt();
                List<NetworkView> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    list.add(new NetworkView(buf.readUUID(), buf.readUtf(64), buf.readUtf(64), buf.readVarInt(), buf.readVarLong(),
                            buf.readVarLong(), buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(128),
                            buf.readVarLong(), buf.readBoolean(), buf.readLong()));
                }
                String wantFluid = buf.readUtf(128), tankFluid = buf.readUtf(128);
                int count = Math.min(buf.readVarInt(), 512);
                List<PriceHistory> histories = new ArrayList<>(count);
                for (int h = 0; h < count; h++) {
                    String fluid = buf.readUtf(128);
                    int hours = Math.min(buf.readVarInt(), 168);
                    long[] micro = new long[hours], units = new long[hours];
                    for (int i = 0; i < hours; i++) { micro[i] = buf.readLong(); units[i] = buf.readVarLong(); }
                    histories.add(new PriceHistory(fluid, micro, units));
                }
                return new SyncMeterPacket(pos, kind, owner, ownerName, role, net, coins, coinCap, coinEta, maxPrice, maxPer, stored, cap,
                        content, rate, eff, status, sides, list, wantFluid, tankFluid, histories);
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncMeterPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().player != null && Minecraft.getInstance().player.containerMenu instanceof MeterMenu menu) {
                menu.updateData(p);
            }
        });
    }
}
