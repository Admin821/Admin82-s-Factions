package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.WarManager;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: player requests the target faction's land-claim list so the
 * client can render the Territory-War chunk-selection map.
 */
public record RequestEnemyClaimsPacket(UUID targetFactionId) implements CustomPacketPayload {

    public static final Type<RequestEnemyClaimsPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "request_enemy_claims")
    );

    public static final StreamCodec<FriendlyByteBuf, RequestEnemyClaimsPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> buf.writeUUID(pkt.targetFactionId()),
            buf -> new RequestEnemyClaimsPacket(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(RequestEnemyClaimsPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer   player = (ServerPlayer) context.player();
            FactionManager fmgr   = FactionManager.get(player.server);

            Faction target = fmgr.getAllFactions().get(pkt.targetFactionId());
            if (target == null) return;

            // Build list of all claim keys
            List<String> keys = new ArrayList<>();
            for (LandClaim c : target.getLandClaims()) {
                keys.add(c.chunkX() + "," + c.chunkZ() + "," + c.dimension().toString());
            }

            // Locate the faction's core chunk (table position)
            int coreCX = 0, coreCZ = 0;
            String coreDim = player.level().dimension().location().toString();
            FactionManager.TableLocation tableLoc = fmgr.getFactionTable(target.getId());
            if (tableLoc != null) {
                coreCX  = SectionPos.blockToSectionCoord(tableLoc.pos().getX());
                coreCZ  = SectionPos.blockToSectionCoord(tableLoc.pos().getZ());
                coreDim = tableLoc.dimension();
            } else if (!keys.isEmpty()) {
                // Fallback: average of all claims
                long sumX = 0, sumZ = 0;
                for (String k : keys) {
                    String[] p = k.split(",", 3);
                    try { sumX += Long.parseLong(p[0]); sumZ += Long.parseLong(p[1]); }
                    catch (NumberFormatException ignored) {}
                }
                coreCX = (int)(sumX / keys.size());
                coreCZ = (int)(sumZ / keys.size());
            }

            PacketDistributor.sendToPlayer(player,
                    new SyncEnemyClaimsPacket(pkt.targetFactionId(), keys, coreCX, coreCZ, coreDim));
        });
    }
}
