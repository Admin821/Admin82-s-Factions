package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * Server → Client: tells the highest-ranked online member of the winning faction
 * to open the conquest decision screen.
 */
public record OpenConquestGuiPacket(
        UUID   defeatedFactionId,
        String defeatedFactionName,
        int    defenderClaims,
        long   defenderVault
) implements CustomPacketPayload {

    public static final Type<OpenConquestGuiPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "open_conquest_gui")
    );

    public static final StreamCodec<FriendlyByteBuf, OpenConquestGuiPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.defeatedFactionId());
                buf.writeUtf(pkt.defeatedFactionName(), 64);
                buf.writeVarInt(pkt.defenderClaims());
                buf.writeLong(pkt.defenderVault());
            },
            buf -> new OpenConquestGuiPacket(
                    buf.readUUID(),
                    buf.readUtf(64),
                    buf.readVarInt(),
                    buf.readLong()
            )
    );

    /** Client-side handler — only called on the client. */
    public static void handle(OpenConquestGuiPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                handleClient(pkt);
            }
        });
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(OpenConquestGuiPacket pkt) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.admin82.factions.screen.ConquestDecisionScreen(
                        pkt.defeatedFactionId(),
                        pkt.defeatedFactionName(),
                        pkt.defenderClaims(),
                        pkt.defenderVault()
                )
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
