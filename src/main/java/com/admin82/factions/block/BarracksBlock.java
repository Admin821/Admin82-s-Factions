package com.admin82.factions.block;

import com.admin82.factions.barracks.BarracksData;
import com.admin82.factions.barracks.KitData;
import com.admin82.factions.blockentity.BarracksBlockEntity;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.item.TemporaryMoveItem;
import com.admin82.factions.menu.BarracksMenu;
import com.admin82.factions.network.packet.SyncBarracksPacket;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BarracksBlock extends BaseEntityBlock {

    public static final MapCodec<BarracksBlock> CODEC = simpleCodec(BarracksBlock::new);

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public BarracksBlock(Properties properties) { super(properties); }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BarracksBlockEntity(pos, state);
    }

    // ── Placement logic ───────────────────────────────────────────────────────

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                             @Nullable LivingEntity placer, ItemStack stack) {
        if (level.isClientSide || !(placer instanceof ServerPlayer player)) return;

        // Barracks can only be placed in the Overworld
        if (!level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            level.removeBlock(pos, false);
            // Refund the barracks block as an item (state.getBlock() is the barracks block itself)
            player.getInventory().add(new net.minecraft.world.item.ItemStack(state.getBlock()));
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            "§cBarracks can only be placed in the Overworld."), true);
            return;
        }

        FactionManager mgr = FactionManager.get((ServerLevel) level);

        // ── Case 1: completing a barracks move ────────────────────────────────────────────
        FactionManager.PendingMove pendingMove = mgr.getPendingBarracksMove(player.getUUID());
        if (pendingMove != null) {
            int chunkX = SectionPos.blockToSectionCoord(pos.getX());
            int chunkZ = SectionPos.blockToSectionCoord(pos.getZ());
            String dim = ((ServerLevel) level).dimension().location().toString();
            Faction pendingFaction = mgr.getFaction(pendingMove.factionId());
            if (pendingFaction == null || !pendingFaction.hasClaim(chunkX, chunkZ, dim)) {
                level.removeBlock(pos, false);
                player.getInventory().add(TemporaryMoveItem.create(state.getBlock().asItem(), "Barracks"));
                player.displayClientMessage(
                        Component.literal("§cBarracks can only be placed inside a chunk claimed by your faction!"), true);
                return;
            }

            if (level.getBlockEntity(pos) instanceof BarracksBlockEntity be) {
                be.setLinkedFactionId(pendingMove.factionId());
            }

            ResourceKey<net.minecraft.world.level.Level> oldDimKey = ResourceKey.create(
                    Registries.DIMENSION, ResourceLocation.parse(pendingMove.dimension()));
            ServerLevel oldLevel = ((ServerLevel) level).getServer().getLevel(oldDimKey);
            if (oldLevel != null && !pendingMove.originalPos().equals(pos)) {
                oldLevel.removeBlock(pendingMove.originalPos(), false);
            }

            mgr.setFactionBarracks(pendingMove.factionId(), pos, dim);
            mgr.clearPendingBarracksMove(player.getUUID());
            TemporaryMoveItem.removeAll(player, state.getBlock().asItem());
            player.displayClientMessage(Component.literal("§aBarracks moved successfully!"), false);
            return;
        }

        Faction faction = mgr.getFactionForPlayer(player.getUUID());

        // ── Case 2: faction already has a barracks (1-per-faction) ─────────────────────
        if (faction != null && mgr.getFactionBarracks(faction.getId()) != null) {
            level.removeBlock(pos, false);
            player.getInventory().add(new net.minecraft.world.item.ItemStack(state.getBlock()));
            player.displayClientMessage(
                    Component.literal("§cYour faction already has a Barracks! Use the Kit Manager to move it."), true);
            return;
        }

        if (faction != null) {
            // Already in a faction — link immediately
            if (level.getBlockEntity(pos) instanceof BarracksBlockEntity be) {
                be.setLinkedFactionId(faction.getId());
            }
            mgr.setFactionBarracks(faction.getId(), pos, level.dimension().location().toString());
            player.displayClientMessage(
                    Component.literal("§aBarracks linked to §e" + faction.getName() + "§a!"), true);
        } else {
            // No faction yet — register as pending barracks for faction creation
            mgr.setPendingBarracks(player.getUUID(), pos, level.dimension().location().toString());
            player.displayClientMessage(
                    Component.literal("§eBarracks placed! Now create your faction to link it."), true);
        }
    }

    // ── Right-click → open GUI ────────────────────────────────────────────────

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof BarracksBlockEntity be)) return InteractionResult.PASS;

        ServerLevel serverLevel = (ServerLevel) level;
        FactionManager mgr = FactionManager.get(serverLevel);
        UUID linkedId = be.getLinkedFactionId();

        if (linkedId != null) {
            // Linked barracks: only faction members
            Faction faction = mgr.getFaction(linkedId);
            if (faction == null) {
                // Faction disbanded, clear the stale link so the block can be reclaimed
                be.setLinkedFactionId(null);
                mgr.removeFactionBarracks(linkedId);
                linkedId = null; // fall through to unlinked path
            } else if (!faction.hasMember(player.getUUID())) {
                player.displayClientMessage(
                        Component.literal("§cThis Barracks belongs to: §e" + faction.getName()), true);
                return InteractionResult.FAIL;
            }
        } else {
            // Unlinked: check if this is the player's pending barracks
            FactionManager.TableLocation pending = mgr.getPendingBarracks(player.getUUID());
            if (pending != null && pending.pos().equals(pos)) {
                // Valid pending barracks — try to link if they have a faction now
                Faction pFaction = mgr.getFactionForPlayer(player.getUUID());
                if (pFaction != null) {
                    be.setLinkedFactionId(pFaction.getId());
                    mgr.setFactionBarracks(pFaction.getId(), pos, serverLevel.dimension().location().toString());
                    mgr.clearPendingBarracks(player.getUUID());
                    linkedId = pFaction.getId();
                }
                // If still no faction, let them open a mostly-empty GUI
            } else {
                Faction pFaction = mgr.getFactionForPlayer(player.getUUID());
                if (pFaction == null) {
                    player.displayClientMessage(
                            Component.literal("§cCreate a faction to use this Barracks."), true);
                    return InteractionResult.FAIL;
                }
                // Has faction but barracks unlinked — link it now
                be.setLinkedFactionId(pFaction.getId());
                mgr.setFactionBarracks(pFaction.getId(), pos, serverLevel.dimension().location().toString());
                linkedId = pFaction.getId();
            }
        }

        final UUID fId = linkedId;
        ((ServerPlayer) player).openMenu(be, buf -> {
            buf.writeBlockPos(pos);
            buf.writeBoolean(fId != null);
            if (fId != null) buf.writeUUID(fId);

            BarracksData bData = BarracksData.get(serverLevel.getServer());
            List<KitData> kits = bData.getKits(player.getUUID());
            buf.writeVarInt(kits.size());
            for (KitData kit : kits) buf.writeUtf(kit.getName(), 64);

            buf.writeVarInt(0); // LDLib2 UISyncManager initial pack
        });
        return InteractionResult.SUCCESS;
    }

    // ── Prevent vanilla breaking of linked barracks (see BarracksEvents) ─────

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (state.getBlock() != newState.getBlock()
                && !level.isClientSide
                && level.getBlockEntity(pos) instanceof BarracksBlockEntity be
                && be.getLinkedFactionId() != null) {
            // Remove the barracks location from faction records when a linked barracks is destroyed
            FactionManager mgr = FactionManager.get((ServerLevel) level);
            UUID fId = be.getLinkedFactionId();
            FactionManager.TableLocation loc = mgr.getFactionBarracks(fId);
            if (loc != null && loc.pos().equals(pos)) {
                mgr.removeFactionBarracks(fId);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
