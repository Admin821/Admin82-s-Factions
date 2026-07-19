package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client: open the Territory Claim screen after a {@code TERRITORY} war victory.
 * Sends the list of the defeated faction's non-core chunk keys (format "cx,cz,dim").
 */
public record OpenTerritoryClaimPacket(
        UUID   defeatedFactionId,
        String defeatedFactionName,
        List<String> chunkKeys   // "chunkX,chunkZ,dimensionId" for each claimable chunk
) implements CustomPacketPayload {

    public static final Type<OpenTerritoryClaimPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "open_territory_claim")
    );

    public static final StreamCodec<FriendlyByteBuf, OpenTerritoryClaimPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.defeatedFactionId());
                buf.writeUtf(pkt.defeatedFactionName(), 64);
                buf.writeVarInt(pkt.chunkKeys().size());
                pkt.chunkKeys().forEach(k -> buf.writeUtf(k, 256));
            },
            buf -> {
                UUID id   = buf.readUUID();
                String name = buf.readUtf(64);
                int count = buf.readVarInt();
                List<String> keys = new ArrayList<>(count);
                for (int i = 0; i < count; i++) keys.add(buf.readUtf(256));
                return new OpenTerritoryClaimPacket(id, name, List.copyOf(keys));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(OpenTerritoryClaimPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(OpenTerritoryClaimPacket pkt) {
        net.minecraft.client.Minecraft.getInstance().setScreen(
                new com.admin82.factions.screen.TerritoryClaimScreen(
                        pkt.defeatedFactionId(), pkt.defeatedFactionName(), pkt.chunkKeys()));
    }
}
