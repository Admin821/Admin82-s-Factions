package com.admin82.factions.block;

import com.admin82.factions.FactionBlockProtection;
import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.registry.ModBlocks;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.item.TemporaryMoveItem;
import com.admin82.factions.faction.FactionSummary;
import com.admin82.factions.menu.FactionTableMenu;
import com.admin82.factions.network.packet.SyncAllFactionsPacket;
import com.admin82.factions.network.packet.SyncFactionDataPacket;
import com.admin82.factions.war.VassalManager;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.UUID;

public class FactionTableBlock extends BaseEntityBlock {

    public static final MapCodec<FactionTableBlock> CODEC = simpleCodec(FactionTableBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public FactionTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof FactionTableBlockEntity factionBe) {
            ServerLevel serverLevel = (ServerLevel) level;

            // Faction tables only function in the Overworld
            if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                player.displayClientMessage(
                        Component.literal("§cFaction Tables can only be used in the Overworld."), true);
                return InteractionResult.FAIL;
            }

            FactionManager manager = FactionManager.get(serverLevel);
            UUID linkedId = factionBe.getLinkedFactionId();

            // If the table is linked, only members of that faction may open it
            if (linkedId != null) {
                Faction linkedFaction = manager.getFaction(linkedId);
                if (linkedFaction == null) {
                    // Faction was disbanded but block wasn't removed (e.g. chunk was unloaded).
                    // Clear the stale link so the block can be reclaimed.
                    factionBe.setLinkedFactionId(null);
                    manager.removeFactionTable(linkedId);
                    linkedId = null; // fall through to unlinked path
                } else if (!linkedFaction.hasMember(player.getUUID())) {
                    player.displayClientMessage(
                            Component.literal("§cThis Faction Table belongs to: §e" + linkedFaction.getName()), true);
                    return InteractionResult.FAIL;
                }
            }

            // Open the menu — send faction data if player has one
            Faction faction = manager.getFactionForPlayer(player.getUUID());
            VassalManager vmgr = VassalManager.get(serverLevel.getServer());
            ((ServerPlayer) player).openMenu(factionBe, buf -> {
                buf.writeBlockPos(pos);
                if (faction != null) {
                    buf.writeBoolean(true);
                    faction.toNetwork(buf);
                } else {
                    buf.writeBoolean(false);
                }
                // Vassal data for the Vassal tab
                boolean isVassal   = faction != null && vmgr.isVassal(faction.getId());
                boolean isSuzerain = faction != null && vmgr.isSuzerain(faction.getId());
                buf.writeBoolean(isVassal);
                buf.writeBoolean(isSuzerain);
                // Suzerain name (empty if not a vassal)
                if (isVassal && faction != null) {
                    UUID suzerainId = vmgr.getSuzerain(faction.getId());
                    Faction suzerain = suzerainId != null ? manager.getFaction(suzerainId) : null;
                    buf.writeUtf(suzerain != null ? suzerain.getName() : "");
                } else {
                    buf.writeUtf("");
                }
                // Pending tax for this vassal
                long pendingTax = faction != null && isVassal ? vmgr.getPendingTax(faction.getId()) : 0L;
                buf.writeLong(pendingTax);
                // Vassal subjects if suzerain
                if (isSuzerain && faction != null) {
                    var subjects = vmgr.getVassals(faction.getId());
                    buf.writeVarInt(subjects.size());
                    for (UUID vid : subjects) {
                        Faction vf = manager.getFaction(vid);
                        buf.writeUUID(vid);
                        buf.writeUtf(vf != null ? vf.getName() : "Unknown");
                        buf.writeLong(vmgr.getPendingTax(vid));
                    }
                } else {
                    buf.writeVarInt(0);
                }
                buf.writeVarInt(0); // LDLib2 UISyncManager initial pack: 0 sync values
            });
            // Send all other factions to the client for the Wars tab
            final UUID ownFactionId = faction != null ? faction.getId() : null;
            var ecoMgr = com.admin82.factions.economy.EconomyManager.get(serverLevel.getServer());
            List<FactionSummary> summaries = manager.getAllFactions().values().stream()
                    .filter(f -> !f.getId().equals(ownFactionId))
                    .map(f -> {
                        int online = (int) ((ServerPlayer) player).server.getPlayerList().getPlayers().stream()
                                .filter(p -> f.getId().equals(manager.getPlayerFactionId(p.getUUID())))
                                .count();
                        long wealth = ecoMgr.getFactionVaultBalance(f.getId());
                        return new FactionSummary(f.getId(), f.getName(), f.getMembers().size(), online, wealth);
                    })
                    .sorted(Comparator.comparing(FactionSummary::name))
                    .collect(Collectors.toList());
            List<String> otherClaims = new java.util.ArrayList<>();
            for (com.admin82.factions.faction.Faction f : manager.getAllFactions().values()) {
                if (f.getId().equals(ownFactionId)) continue;
                // Regular land claims
                for (com.admin82.factions.faction.LandClaim c : f.getLandClaims()) {
                    otherClaims.add(c.chunkX() + "," + c.chunkZ() + "," + c.dimension().toString());
                }
                // Defensive: always include the table chunk even if the land-claim record
                // is missing (e.g. legacy data). This ensures enemy tables never appear as
                // free/unclaimed land on the viewer's map — only the chunk key is sent,
                // NOT the block position, so no table location is leaked.
                FactionManager.TableLocation tbl = manager.getFactionTable(f.getId());
                if (tbl != null) {
                    int tcx = net.minecraft.core.SectionPos.blockToSectionCoord(tbl.pos().getX());
                    int tcz = net.minecraft.core.SectionPos.blockToSectionCoord(tbl.pos().getZ());
                    otherClaims.add(tcx + "," + tcz + "," + tbl.dimension());
                }
            }
            List<String> availablePlayers = ((ServerPlayer) player).server.getPlayerList().getPlayers().stream()
                    .filter(p -> manager.getFactionForPlayer(p.getUUID()) == null)
                    .map(p -> p.getGameProfile().getName())
                    .collect(Collectors.toList());
            PacketDistributor.sendToPlayer((ServerPlayer) player, new SyncAllFactionsPacket(summaries, otherClaims, availablePlayers));

            // Send economy balances
            long walletBal = ecoMgr.getWallet(player.getUUID());
            long vaultBal  = faction != null ? ecoMgr.getVault(faction.getId()) : 0L;
            PacketDistributor.sendToPlayer((ServerPlayer) player,
                    new com.admin82.factions.network.packet.SyncEconomyPacket(walletBal, vaultBal));

            // Send outpost list for this faction
            if (faction != null) {
                com.admin82.factions.outpost.OutpostData outpostData =
                        com.admin82.factions.outpost.OutpostData.get(serverLevel.getServer());
                java.util.List<com.admin82.factions.network.packet.SyncOutpostsPacket.OutpostItem> outpostItems
                        = new java.util.ArrayList<>();
                for (com.admin82.factions.outpost.OutpostEntry e :
                        outpostData.getOutpostsForFaction(faction.getId())) {
                    com.admin82.factions.faction.Faction capFaction =
                            e.capturingFactionId != null
                                    ? manager.getAllFactions().get(e.capturingFactionId) : null;
                    outpostItems.add(new com.admin82.factions.network.packet.SyncOutpostsPacket.OutpostItem(
                            e.id,
                            e.managerPos, e.dimension, e.disintegrating,
                            e.captureProgress,
                            (float) com.admin82.factions.war.WarManager.get(serverLevel.getServer()).getOutpostKothTime(),
                            capFaction != null ? capFaction.getName() : ""));
                }
                long tpCost = com.admin82.factions.economy.EconomyManager.get(serverLevel.getServer()).getTpCostToOutpost();
                PacketDistributor.sendToPlayer((ServerPlayer) player,
                        new com.admin82.factions.network.packet.SyncOutpostsPacket(outpostItems, tpCost));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Called after the block is placed in the world.
     * Handles: move-mode completion and duplicate-table prevention.
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (!(placer instanceof ServerPlayer player)) return;

        // Faction tables can only exist in the Overworld
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            serverLevel.removeBlock(pos, false);
            player.getInventory().add(new ItemStack(ModItems.FACTION_TABLE.get()));
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "§cFaction Tables can only be placed in the Overworld."), true);
            return;
        }
        // ── Validate 2×2 footprint ────────────────────────────────────────────────────────────
        if (!has2x2Space(level, pos)) {
            serverLevel.removeBlock(pos, false);
            player.getInventory().add(new ItemStack(ModItems.FACTION_TABLE.get()));
            player.displayClientMessage(
                    Component.literal("§cNot enough space! The Faction Table needs a clear 2×2 area."), true);
            return;
        }
        if (!(serverLevel.getBlockEntity(pos) instanceof FactionTableBlockEntity be)) return;

        FactionManager manager = FactionManager.get(serverLevel);

        // ── Case 1: player is completing a move ───────────────────────────────
        FactionManager.PendingMove pending = manager.getPendingMove(player.getUUID());
        if (pending != null) {
            // Gate: new position must be inside a chunk claimed by the faction
            int chunkX = SectionPos.blockToSectionCoord(pos.getX());
            int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
            String dim  = serverLevel.dimension().location().toString();
            Faction pendingFaction = manager.getFaction(pending.factionId());
            if (pendingFaction == null || !pendingFaction.hasClaim(chunkX, chunkZ, dim)) {
                serverLevel.removeBlock(pos, false);
                player.getInventory().add(TemporaryMoveItem.create(ModItems.FACTION_TABLE.get(), "Faction Table"));
                player.displayClientMessage(
                        Component.literal("§cYou can only place the Faction Table inside a chunk claimed by your faction!"), true);
                return;
            }

            be.setLinkedFactionId(pending.factionId());

            // Remove the OLD table block
            ResourceKey<Level> oldDimKey = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(pending.dimension()));
            ServerLevel oldLevel = serverLevel.getServer().getLevel(oldDimKey);
            if (oldLevel != null && !pending.originalPos().equals(pos)) {
                FactionBlockProtection.allowProtectedRemoval(() -> oldLevel.removeBlock(pending.originalPos(), false));
            }

            manager.setFactionTable(pending.factionId(), pos, dim);
            manager.clearPendingMove(player.getUUID());
            TemporaryMoveItem.removeAll(player, ModItems.FACTION_TABLE.get());
            player.displayClientMessage(Component.literal("§aFaction Table moved successfully!"), false);
            placeFillers(level, pos);
            return;
        }

        // ── Case 2: player's faction already has a registered table ───────────
        Faction faction = manager.getFactionForPlayer(player.getUUID());
        if (faction != null && manager.getFactionTable(faction.getId()) != null) {
            serverLevel.removeBlock(pos, false);
            player.getInventory().add(new ItemStack(ModItems.FACTION_TABLE.get()));
            player.displayClientMessage(
                    Component.literal("§cYour faction already has a Faction Table! Use the menu to move it."), true);
            return;
        }
        // Case 3: faction exists but no table yet — link this one directly
        //   (e.g. placed via /setblock, or FactionTableItem fallback for faction-already member)
        if (faction != null) {
            be.setLinkedFactionId(faction.getId());
            manager.setFactionTable(faction.getId(), pos, serverLevel.dimension().location().toString());
        }
        // Case 4: no faction at all — handled by FactionTableItem which opens Create Faction UI
        //   without placing the block. If somehow we reach here (e.g. /setblock), leave unlinked.
        placeFillers(level, pos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactionTableBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // Prevent this block from occluding the faces of adjacent blocks.
    // Without this, the terrain directly below the table becomes invisible
    // because Minecraft culls the ground block's top face.
    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    // ── Multi-block removal cascade ───────────────────────────────────────────

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide
                    && !FactionBlockProtection.canRemoveProtectedBlock()
                    && level.getBlockEntity(pos) instanceof FactionTableBlockEntity be
                    && be.getLinkedFactionId() != null) {
                UUID linkedId = be.getLinkedFactionId();
                FactionManager manager = FactionManager.get((ServerLevel) level);
                if (manager.getFaction(linkedId) != null) {
                    level.setBlock(pos, state, 3);
                    if (level.getBlockEntity(pos) instanceof FactionTableBlockEntity restoredBe) {
                        restoredBe.setLinkedFactionId(linkedId);
                    }
                    placeFillers(level, pos);
                    return;
                }
            }
            // Main block removed — cascade-remove all three filler blocks.
            BlockPos[] fillerPos = { pos.east(), pos.south(), pos.east().south() };
            FactionTableFillerBlock.Part[] parts = {
                FactionTableFillerBlock.Part.NE,
                FactionTableFillerBlock.Part.SW,
                FactionTableFillerBlock.Part.SE
            };
            for (int i = 0; i < fillerPos.length; i++) {
                BlockState fs = level.getBlockState(fillerPos[i]);
                if (fs.is(ModBlocks.FACTION_TABLE_FILLER.get())
                        && fs.getValue(FactionTableFillerBlock.PART) == parts[i]) {
                    level.removeBlock(fillerPos[i], false);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // ── 2×2 helpers (used by FactionTableItem, CreateFactionPacket, etc.) ─────

    /** Returns {@code true} when the east, south, and south-east positions are all replaceable. */
    public static boolean has2x2Space(Level level, BlockPos mainPos) {
        return level.getBlockState(mainPos.east()).canBeReplaced()
            && level.getBlockState(mainPos.south()).canBeReplaced()
            && level.getBlockState(mainPos.east().south()).canBeReplaced();
    }

    /** Places the three invisible filler blocks that complete the 2×2 footprint. */
    public static void placeFillers(Level level, BlockPos mainPos) {
        FactionTableFillerBlock filler = ModBlocks.FACTION_TABLE_FILLER.get();
        level.setBlock(mainPos.east(),
                filler.defaultBlockState().setValue(FactionTableFillerBlock.PART, FactionTableFillerBlock.Part.NE), 3);
        level.setBlock(mainPos.south(),
                filler.defaultBlockState().setValue(FactionTableFillerBlock.PART, FactionTableFillerBlock.Part.SW), 3);
        level.setBlock(mainPos.east().south(),
                filler.defaultBlockState().setValue(FactionTableFillerBlock.PART, FactionTableFillerBlock.Part.SE), 3);
    }
}

