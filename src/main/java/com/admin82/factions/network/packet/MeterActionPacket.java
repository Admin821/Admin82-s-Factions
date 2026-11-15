package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.meter.MeterService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Client → server: something done in the meter GUI. {@code x}/{@code y} are amounts or price and units,
 * {@code access} is 0 public, 1 password, 2 faction only.
 */
public record MeterActionPacket(BlockPos pos, int action, @Nullable UUID network, String text, String password,
                                long x, long y, int access) implements CustomPacketPayload {

    public static final Type<MeterActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "meter_action"));

    public static final StreamCodec<FriendlyByteBuf, MeterActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeVarInt(p.action);
                buf.writeBoolean(p.network != null);
                if (p.network != null) buf.writeUUID(p.network);
                buf.writeUtf(p.text, 64);
                buf.writeUtf(p.password, 64);
                buf.writeVarLong(p.x);
                buf.writeVarLong(p.y);
                buf.writeVarInt(p.access);
            },
            buf -> new MeterActionPacket(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean() ? buf.readUUID() : null,
                    buf.readUtf(64), buf.readUtf(64), buf.readVarLong(), buf.readVarLong(), buf.readVarInt()));

    public static MeterActionPacket of(BlockPos pos, MeterService.Action action) {
        return new MeterActionPacket(pos, action.ordinal(), null, "", "", 0, 0, 0);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(MeterActionPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (p.action < 0 || p.action >= MeterService.Action.values().length) return;
            MeterService.handle(sp, p.pos, MeterService.Action.values()[p.action], p.network, p.text, p.password, p.x, p.y, p.access);
        });
    }
}
