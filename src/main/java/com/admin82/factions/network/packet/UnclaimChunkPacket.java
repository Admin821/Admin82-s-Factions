package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.*;
import com.admin82.factions.outpost.OutpostData;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;

public record UnclaimChunkPacket(int chunkX, int chunkZ, String dimension) implements CustomPacketPayload {

    public static final Type<UnclaimChunkPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "unclaim_chunk")
    );

    public static final StreamCodec<FriendlyByteBuf, UnclaimChunkPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeInt(pkt.chunkX());
                buf.writeInt(pkt.chunkZ());
                buf.writeUtf(pkt.dimension());
            },
            buf -> new UnclaimChunkPacket(buf.readInt(), buf.readInt(), buf.readUtf(256))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UnclaimChunkPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null) return;

            FactionMember member = faction.getMember(player.getUUID());
            if (member == null || !faction.getRolePermission(member.getRole(), FactionPermission.OFFICER_CLAIM)) return;

            // The chunks holding the faction table, the barracks or an outpost can't be given up.
            String reason = null;
            if (inChunk(manager.getFactionTable(faction.getId()), packet)) reason = "your faction table";
            else if (inChunk(manager.getFactionBarracks(faction.getId()), packet)) reason = "your barracks";
            else {
                for (var outpost : OutpostData.get(player.server).getOutpostsForFaction(faction.getId())) {
                    if (outpost.dimension.equals(packet.dimension())
                            && SectionPos.blockToSectionCoord(outpost.managerPos.getX()) == packet.chunkX()
                            && SectionPos.blockToSectionCoord(outpost.managerPos.getZ()) == packet.chunkZ()) {
                        reason = "your outpost";
                        break;
                    }
                }
            }
            if (reason != null) {
                player.displayClientMessage(Component.literal("§cYou can't unclaim the chunk with " + reason + " in it."), true);
                return;
            }
            manager.unclaimChunk(faction.getId(), packet.chunkX(), packet.chunkZ(), packet.dimension());
            ChunkLoadManager.sync(player.server);
            PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(manager.getFactionForPlayer(player.getUUID())));
        });
    }

    private static boolean inChunk(@Nullable FactionManager.TableLocation loc, UnclaimChunkPacket packet) {
        return loc != null && loc.dimension().equals(packet.dimension())
                && SectionPos.blockToSectionCoord(loc.pos().getX()) == packet.chunkX()
                && SectionPos.blockToSectionCoord(loc.pos().getZ()) == packet.chunkZ();
    }
}
