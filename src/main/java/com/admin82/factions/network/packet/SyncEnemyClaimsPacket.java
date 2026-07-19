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
 * Server → Client: sends the target faction's land-claim keys and core-chunk
 * position so the Territory-War selection map can be rendered.
 */
public record SyncEnemyClaimsPacket(
        UUID         targetFactionId,
        List<String> claimKeys,   // "chunkX,chunkZ,dimensionId"
        int          coreCX,
        int          coreCZ,
        String       coreDim
) implements CustomPacketPayload {

    public static final Type<SyncEnemyClaimsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "sync_enemy_claims")
    );

    public static final StreamCodec<FriendlyByteBuf, SyncEnemyClaimsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.targetFactionId());
                buf.writeVarInt(pkt.claimKeys().size());
                pkt.claimKeys().forEach(k -> buf.writeUtf(k, 256));
                buf.writeInt(pkt.coreCX());
                buf.writeInt(pkt.coreCZ());
                buf.writeUtf(pkt.coreDim(), 256);
            },
            buf -> {
                UUID id = buf.readUUID();
                int count = buf.readVarInt();
                List<String> keys = new ArrayList<>(count);
                for (int i = 0; i < count; i++) keys.add(buf.readUtf(256));
                int cx = buf.readInt(), cz = buf.readInt();
                String dim = buf.readUtf(256);
                return new SyncEnemyClaimsPacket(id, List.copyOf(keys), cx, cz, dim);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SyncEnemyClaimsPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) handleClient(pkt);
        });
    }

    @net.neoforged.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncEnemyClaimsPacket pkt) {
        var screen = net.minecraft.client.Minecraft.getInstance().screen;
        if (screen instanceof com.admin82.factions.screen.FactionTableScreen fts) {
            fts.updateEnemyClaims(pkt.targetFactionId(), pkt.claimKeys(),
                    pkt.coreCX(), pkt.coreCZ(), pkt.coreDim());
        }
    }
}
