package com.admin82.factions.block;

import com.admin82.factions.blockentity.OutpostManagerBlockEntity;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.war.ActiveWar;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarPhase;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

import com.admin82.factions.network.packet.OpenOutpostManagerPacket;

public class OutpostManagerBlock extends BaseEntityBlock {

    public static final MapCodec<OutpostManagerBlock> CODEC = simpleCodec(OutpostManagerBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public OutpostManagerBlock(Properties properties) { super(properties); }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OutpostManagerBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        ServerLevel sLevel = (ServerLevel) level;
        String dim = sLevel.dimension().location().toString();

        OutpostData outposts = OutpostData.get(sLevel.getServer());
        OutpostEntry entry   = outposts.getOutpostAtPos(pos, dim);
        if (entry == null) {
            sp.displayClientMessage(Component.literal("§cThis outpost has no registered data."), true);
            return InteractionResult.FAIL;
        }

        FactionManager fmgr     = FactionManager.get(sLevel.getServer());
        Faction        myFaction = fmgr.getFactionForPlayer(sp.getUUID());
        WarManager     warmgr   = WarManager.get(sLevel.getServer());
        ActiveWar      war      = warmgr.getWarForPlayer(sp.getUUID());

        boolean inWar = war != null && war.phase == WarPhase.ACTIVE;
        Faction ownerFaction = fmgr.getAllFactions().get(entry.ownerFactionId);
        String  ownerName    = ownerFaction != null ? ownerFaction.getName() : "Unknown";

        // Only allow faction members to set war spawn on their own outpost
        boolean isOwner     = myFaction != null && myFaction.getId().equals(entry.ownerFactionId);
        boolean canSetSpawn = inWar && isOwner;

        Faction capturingFaction = entry.capturingFactionId != null
                ? fmgr.getAllFactions().get(entry.capturingFactionId) : null;
        String capturingName = capturingFaction != null ? capturingFaction.getName() : "";

        // ── Build 11×11 territory map centred on the outpost chunk ────────────
        int MAP_RADIUS = 5; // gives 11×11 grid
        int MAP_SIZE   = MAP_RADIUS * 2 + 1; // 11
        int centerCX   = SectionPos.blockToSectionCoord(pos.getX());
        int centerCZ   = SectionPos.blockToSectionCoord(pos.getZ());
        UUID ownerFactionId = entry.ownerFactionId;
        byte[] mapTiles = new byte[MAP_SIZE * MAP_SIZE];
        for (int dz = -MAP_RADIUS; dz <= MAP_RADIUS; dz++) {
            for (int dx = -MAP_RADIUS; dx <= MAP_RADIUS; dx++) {
                int cx = centerCX + dx;
                int cz = centerCZ + dz;
                int idx = (dz + MAP_RADIUS) * MAP_SIZE + (dx + MAP_RADIUS);
                mapTiles[idx] = 0; // unclaimed default
                for (Faction f : fmgr.getAllFactions().values()) {
                    if (f.hasClaim(cx, cz, dim)) {
                        mapTiles[idx] = f.getId().equals(ownerFactionId) ? (byte) 1 : (byte) 2;
                        break;
                    }
                }
            }
        }

        PacketDistributor.sendToPlayer(sp, new OpenOutpostManagerPacket(
                entry.id, ownerName, entry.disintegrating, canSetSpawn, isOwner,
                pos, dim, entry.captureProgress, capturingName, mapTiles, centerCX, centerCZ));
        return InteractionResult.CONSUME;
    }
}
