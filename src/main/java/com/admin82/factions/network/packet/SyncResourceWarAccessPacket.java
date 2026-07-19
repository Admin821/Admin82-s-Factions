package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → Client: current Resource-War access state for the receiving player.
 * Used to drive the winner HUD overlay (timer bar + block counter).
 *
 * Send with {@code expiresAt = 0} to clear the HUD.
 */
public record SyncResourceWarAccessPacket(
        long expiresAt,
        int  blockLimit,
        int  blocksBroken
) implements CustomPacketPayload {

    public static final Type<SyncResourceWarAccessPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_resource_war_access")
    );

    public static final StreamCodec<FriendlyByteBuf, SyncResourceWarAccessPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeLong(pkt.expiresAt());
                buf.writeVarInt(pkt.blockLimit());
                buf.writeVarInt(pkt.blocksBroken());
            },
            buf -> new SyncResourceWarAccessPacket(
                    buf.readLong(),
                    buf.readVarInt(),
                    buf.readVarInt()
            )
    );

    /** Convenience: a packet that clears the client HUD. */
    public static SyncResourceWarAccessPacket clear() {
        return new SyncResourceWarAccessPacket(0L, 0, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncResourceWarAccessPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncResourceWarAccessPacket pkt) {
        com.admin82.factions.screen.ResourceWarHudOverlay.update(pkt);
    }
}
