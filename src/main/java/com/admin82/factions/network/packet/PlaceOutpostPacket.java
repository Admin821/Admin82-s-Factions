package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.blockentity.OutpostManagerBlockEntity;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.admin82.factions.registry.ModBlocks;

/**
 * Client → Server: player confirmed they want to place an Outpost here.
 */
public record PlaceOutpostPacket(boolean confirmed) implements CustomPacketPayload {

    public static final Type<PlaceOutpostPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "place_outpost"));

    public static final StreamCodec<FriendlyByteBuf, PlaceOutpostPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> buf.writeBoolean(pkt.confirmed()),
                    buf -> new PlaceOutpostPacket(buf.readBoolean()));

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(PlaceOutpostPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            OutpostData outposts = OutpostData.get(sp.server);

            BlockPos pending = outposts.getPendingPos(sp.getUUID());
            String   dim     = outposts.getPendingDim(sp.getUUID());
            outposts.clearPending(sp.getUUID());

            if (!pkt.confirmed() || pending == null || dim == null) return;

            FactionManager fmgr   = FactionManager.get(sp.server);
            Faction        faction = fmgr.getFactionForPlayer(sp.getUUID());
            if (faction == null) return;

            // ── Overworld-only restriction ─────────────────────────────────────────
            if (!dim.equals(net.minecraft.world.level.Level.OVERWORLD.location().toString())) {
                sp.displayClientMessage(Component.literal(
                        "§c[Outpost] Outposts can only be placed in the Overworld."), true);
                return;
            }

            // ── 1-per-faction limit ───────────────────────────────────────────
            if (!outposts.getOutpostsForFaction(faction.getId()).isEmpty()) {
                sp.displayClientMessage(Component.literal(
                        "§c[Outpost] Your faction already has an outpost. Destroy it before placing a new one."), true);
                return;
            }

            // ── Post-war cooldown ─────────────────────────────────────────────
            long cooldownExpiry = outposts.getOutpostCooldown(faction.getId());
            long now = System.currentTimeMillis();
            if (now < cooldownExpiry) {
                long minsLeft = Math.max(1, (cooldownExpiry - now) / 60_000L);
                sp.displayClientMessage(Component.literal(
                        "§c[Outpost] Your faction cannot place an outpost for §e" + minsLeft
                        + " §cmore minute" + (minsLeft == 1 ? "" : "s")
                        + " §c(outpost was destroyed in war)."), true);
                return;
            }

            // Locate the target level
            ServerLevel targetLevel = null;
            for (ServerLevel lvl : sp.server.getAllLevels()) {
                if (lvl.dimension().location().toString().equals(dim)) { targetLevel = lvl; break; }
            }
            if (targetLevel == null) return;

            // Position must still be free
            if (!targetLevel.getBlockState(pending).canBeReplaced()) {
                sp.displayClientMessage(Component.literal("§cThat position is now blocked."), true);
                return;
            }

            Direction facing = sp.getDirection().getOpposite();
            BlockPos fillerPos = pending.above();
            if (!targetLevel.getBlockState(fillerPos).canBeReplaced()) {
                sp.displayClientMessage(Component.literal("§c[Outpost] Not enough space! Outpost needs a clear 2-block-tall area."), true);
                return;
            }

            // ── Build 5×5 cobblestone platform one block below the manager ────
            List<BlockPos> structureBlocks = new ArrayList<>();
            BlockPos platformY = pending.below();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos bp = platformY.offset(dx, 0, dz);
                    if (targetLevel.getBlockState(bp).canBeReplaced()) {
                        targetLevel.setBlockAndUpdate(bp, Blocks.COBBLESTONE.defaultBlockState());
                        structureBlocks.add(bp);
                    }
                }
            }

            // ── Place the outpost manager block ───────────────────────────────
                targetLevel.setBlockAndUpdate(pending, ModBlocks.OUTPOST_MANAGER.get().defaultBlockState()
                    .setValue(com.admin82.factions.block.OutpostManagerBlock.FACING, facing));
                targetLevel.setBlockAndUpdate(fillerPos, ModBlocks.OUTPOST_MANAGER_FILLER.get().defaultBlockState()
                    .setValue(com.admin82.factions.block.OutpostManagerFillerBlock.FACING, facing));
            if (targetLevel.getBlockEntity(pending) instanceof OutpostManagerBlockEntity be) {
                be.setLinkedFactionId(faction.getId());
            }

            // ── Register outpost entry ────────────────────────────────────────
            UUID entryId = UUID.randomUUID();
            OutpostEntry entry = new OutpostEntry(entryId, faction.getId(), pending, dim, structureBlocks);
            outposts.addOutpost(entry);

            // ── Auto-claim the chunk the outpost is placed in ─────────────────
            int outpostChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(pending.getX());
            int outpostChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(pending.getZ());
            fmgr.claimChunk(faction.getId(), outpostChunkX, outpostChunkZ, dim);

            // Consume one item from player's hand
            sp.getMainHandItem().shrink(1);

            sp.displayClientMessage(Component.literal(
                    "§a[Outpost] Outpost placed! §75 silver/day upkeep from the faction vault."), false);
        });
    }
}
