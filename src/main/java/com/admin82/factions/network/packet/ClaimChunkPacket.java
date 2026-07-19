package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.VassalManager;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClaimChunkPacket(int chunkX, int chunkZ, String dimension) implements CustomPacketPayload {

    public static final Type<ClaimChunkPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "claim_chunk")
    );

    public static final StreamCodec<FriendlyByteBuf, ClaimChunkPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeInt(pkt.chunkX());
                buf.writeInt(pkt.chunkZ());
                buf.writeUtf(pkt.dimension());
            },
            buf -> new ClaimChunkPacket(buf.readInt(), buf.readInt(), buf.readUtf(256))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ClaimChunkPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            FactionManager manager = FactionManager.get(player.server);
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            if (faction == null) return;

            FactionMember member = faction.getMember(player.getUUID());
            if (member == null || member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) return;

            // ── Adjacency check: must connect to core or existing territory ──
            int tx = packet.chunkX(), tz = packet.chunkZ();
            boolean adjacent = false;

            // Check adjacency to faction table (core chunk)
            FactionManager.TableLocation tableLoc = manager.getFactionTable(faction.getId());
            if (tableLoc != null && tableLoc.dimension().equals(packet.dimension())) {
                int coreCX = SectionPos.blockToSectionCoord(tableLoc.pos().getX());
                int coreCZ = SectionPos.blockToSectionCoord(tableLoc.pos().getZ());
                adjacent = (Math.abs(tx - coreCX) == 1 && tz == coreCZ)
                        || (tx == coreCX && Math.abs(tz - coreCZ) == 1)
                        || (tx == coreCX && tz == coreCZ); // core chunk itself
            }

            // Check adjacency to any existing claim in the same dimension
            if (!adjacent) {
                for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    if (faction.hasClaim(tx + d[0], tz + d[1], packet.dimension())) {
                        adjacent = true;
                        break;
                    }
                }
            }

            if (!adjacent) {
                player.displayClientMessage(Component.literal(
                        "§cClaims must connect to your base or existing territory."), true);
                return;
            }

            // ── Economy gate: cost doubles for every chunk already owned ──────
            EconomyManager eco = EconomyManager.get(player.server);
            int claimCount = faction.getLandClaims().size();
            long baseCost  = Config.CLAIM_COST_COPPER.get();
            double rate    = eco.getClaimRateMultiplier();
            long cost      = (long) (baseCost * Math.pow(rate, claimCount));
            if (!eco.deductVault(faction.getId(), cost)) {
                player.displayClientMessage(Component.literal(
                        "§cNot enough funds in the faction vault!"
                        + " §7Need §e" + com.admin82.factions.economy.Currency.format(cost)
                        + " §7(vault: §e" + com.admin82.factions.economy.Currency.format(eco.getVault(faction.getId())) + "§7)"),
                        true);
                return;
            }

            manager.claimChunk(faction.getId(), packet.chunkX(), packet.chunkZ(), packet.dimension(), cost);

            // Vassal tax on claim cost
            VassalManager vmgr = VassalManager.get(player.server);
            if (vmgr.isVassal(faction.getId())) {
                long tax = Math.max(1, cost * Config.VASSAL_TAX_PERCENT.get() / 100);
                vmgr.accumulateTax(faction.getId(), tax);
            }

            PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(manager.getFactionForPlayer(player.getUUID())));
        });
    }
}
