package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → Client: current state of the war a player is committed to.
 * Drives the in-world HUD overlay.
 *
 * If {@code phase == 2} (ENDED) the client should show an end result briefly, then clear.
 */
public record SyncWarStatePacket(
        UUID   warId,
        int    phase,
        int    graceSecondsLeft,
        float  captureProgress,
        int    captureTimeSeconds,
        int    myLives,
        boolean isAttacker,
        String attackerFactionName,
        String defenderFactionName,
        int    totalAttackerLives,
        int    totalDefenderLives,
        int    defenderTableX,
        int    defenderTableZ,
        String defenderDimension,
        // Outpost-phase fields
        boolean outpostPhase,
        int     captureTargetX,
        int     captureTargetZ,
        String  captureTargetDim,
        // Defender counter-attack progress
        float   defenderCaptureProgress
) implements CustomPacketPayload {

    public static final Type<SyncWarStatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_war_state")
    );

    public static final StreamCodec<FriendlyByteBuf, SyncWarStatePacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.warId());
                buf.writeVarInt(pkt.phase());
                buf.writeVarInt(pkt.graceSecondsLeft());
                buf.writeFloat(pkt.captureProgress());
                buf.writeVarInt(pkt.captureTimeSeconds());
                buf.writeVarInt(pkt.myLives());
                buf.writeBoolean(pkt.isAttacker());
                buf.writeUtf(pkt.attackerFactionName());
                buf.writeUtf(pkt.defenderFactionName());
                buf.writeVarInt(pkt.totalAttackerLives());
                buf.writeVarInt(pkt.totalDefenderLives());
                buf.writeInt(pkt.defenderTableX());
                buf.writeInt(pkt.defenderTableZ());
                buf.writeUtf(pkt.defenderDimension());
                buf.writeBoolean(pkt.outpostPhase());
                buf.writeInt(pkt.captureTargetX());
                buf.writeInt(pkt.captureTargetZ());
                buf.writeUtf(pkt.captureTargetDim());
                buf.writeFloat(pkt.defenderCaptureProgress());
            },
            buf -> new SyncWarStatePacket(
                    buf.readUUID(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readFloat(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readBoolean(),
                    buf.readUtf(64),
                    buf.readUtf(64),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf(256),
                    buf.readBoolean(),
                    buf.readInt(),
                    buf.readInt(),
                    buf.readUtf(256),
                    buf.readFloat()  // defenderCaptureProgress
            )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncWarStatePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(packet);
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncWarStatePacket packet) {
        com.admin82.factions.screen.WarHudOverlay.updateState(packet);
    }
}
