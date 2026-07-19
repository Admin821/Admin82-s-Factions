package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: player submits their selected chunk keys after a Territory War victory.
 */
public record TerritoryClaimActionPacket(
        UUID         defeatedFactionId,
        List<String> selectedKeys  // "chunkX,chunkZ,dimensionId"
) implements CustomPacketPayload {

    public static final Type<TerritoryClaimActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "territory_claim_action")
    );

    public static final StreamCodec<FriendlyByteBuf, TerritoryClaimActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeUUID(pkt.defeatedFactionId());
                buf.writeVarInt(pkt.selectedKeys().size());
                pkt.selectedKeys().forEach(k -> buf.writeUtf(k, 256));
            },
            buf -> {
                UUID id = buf.readUUID();
                int count = buf.readVarInt();
                List<String> keys = new ArrayList<>(count);
                for (int i = 0; i < count; i++) keys.add(buf.readUtf(256));
                return new TerritoryClaimActionPacket(id, List.copyOf(keys));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ── Server handler ────────────────────────────────────────────────────────

    public static void handle(TerritoryClaimActionPacket pkt, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer   player = (ServerPlayer) context.player();
            FactionManager fmgr   = FactionManager.get(player.server);

            Faction winner = fmgr.getFactionForPlayer(player.getUUID());
            if (winner == null) return;

            FactionMember member = winner.getMember(player.getUUID());
            if (member == null || member.getRole().getLevel() < FactionRole.ADMIN.getLevel()) {
                player.displayClientMessage(
                        Component.literal("§cAdmin rank or higher required to claim territory."), false);
                return;
            }

            Faction defeated = fmgr.getAllFactions().get(pkt.defeatedFactionId());
            if (defeated == null) return;

            // Determine core chunk of defeated faction (protected, cannot be claimed)
            FactionManager.TableLocation tableLoc = fmgr.getFactionTable(defeated.getId());
            int coreX = Integer.MIN_VALUE, coreZ = Integer.MIN_VALUE;
            String coreDim = "";
            if (tableLoc != null) {
                coreX   = SectionPos.blockToSectionCoord(tableLoc.pos().getX());
                coreZ   = SectionPos.blockToSectionCoord(tableLoc.pos().getZ());
                coreDim = tableLoc.dimension();
            }

            int transferred = 0;
            for (String key : pkt.selectedKeys()) {
                String[] parts = key.split(",", 3);
                if (parts.length < 3) continue;
                try {
                    int cx  = Integer.parseInt(parts[0]);
                    int cz  = Integer.parseInt(parts[1]);
                    String dim = parts[2];

                    // Safety: never allow claiming the core chunk
                    if (cx == coreX && cz == coreZ && dim.equals(coreDim)) continue;

                    // Verify defeated faction actually owns this chunk
                    LandClaim match = null;
                    for (LandClaim c : defeated.getLandClaims()) {
                        if (c.chunkX() == cx && c.chunkZ() == cz && c.dimension().toString().equals(dim)) {
                            match = c; break;
                        }
                    }
                    if (match == null) continue;

                    // Transfer: unclaim from defeated, add to winner
                    fmgr.unclaimChunk(defeated.getId(), cx, cz, dim);
                    fmgr.claimChunk(winner.getId(), cx, cz, dim);
                    transferred++;
                } catch (NumberFormatException ignored) {}
            }

            final int count = transferred;
            if (count > 0) {
                player.displayClientMessage(Component.literal(
                        "§a[Territory War] §eClaimed §a" + count + " chunk"
                        + (count == 1 ? "" : "s") + " §efrom §c" + defeated.getName() + "§e."), false);
            } else {
                player.displayClientMessage(Component.literal(
                        "§7[Territory War] No valid chunks were claimed."), false);
            }
        });
    }
}
