package com.admin82.factions.block;

import com.admin82.factions.blockentity.CarePackageBlockEntity;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.registry.ModBlockEntities;
import com.admin82.factions.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

public class CarePackageBlock extends BaseEntityBlock {
    public static final MapCodec<CarePackageBlock> CODEC = simpleCodec(CarePackageBlock::new);
    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final VoxelShape SHAPE = Shapes.box(0.03125, 0.0, 0.03125, 0.96875, 0.875, 0.96875);

    public CarePackageBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OPEN, false).setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(OPEN, FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()).setValue(OPEN, false);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        if (!placeFiller(level, pos, state) && placer instanceof Player player) {
            level.removeBlock(pos, false);
            if (!player.getAbilities().instabuild) player.getInventory().add(new ItemStack(this));
            player.displayClientMessage(Component.literal("§cNot enough space! Care Package needs a clear 1×2 area."), true);
        }
    }

    public static boolean placeFiller(Level level, BlockPos mainPos, BlockState mainState) {
        Direction fillerDirection = getFillerDirection(mainState);
        BlockPos fillerPos = mainPos.relative(fillerDirection);
        BlockState existing = level.getBlockState(fillerPos);
        if (!existing.canBeReplaced() && !existing.is(ModBlocks.CARE_PACKAGE_FILLER.get())) return false;

        BlockPos legacyFillerPos = mainPos.relative(mainState.getValue(FACING));
        BlockState legacyFillerState = level.getBlockState(legacyFillerPos);
        if (!legacyFillerPos.equals(fillerPos)
            && legacyFillerState.is(ModBlocks.CARE_PACKAGE_FILLER.get())
            && legacyFillerState.getValue(CarePackageFillerBlock.FACING) == mainState.getValue(FACING)) {
            level.removeBlock(legacyFillerPos, false);
        }
        level.setBlock(fillerPos, ModBlocks.CARE_PACKAGE_FILLER.get().defaultBlockState()
                .setValue(CarePackageFillerBlock.FACING, fillerDirection), 3);
        return true;
    }

    public static Direction getFillerDirection(BlockState state) {
        return state.getValue(FACING).getClockWise();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CarePackageBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (!state.getValue(OPEN)) {
            level.setBlock(pos, state.setValue(OPEN, true), 3);
            if (level.getBlockEntity(pos) instanceof CarePackageBlockEntity carePackage && carePackage.isSupplyDrop()) {
            Faction faction = FactionManager.get(serverPlayer.server).getFactionForPlayer(serverPlayer.getUUID());
            Component claimant = faction != null
                ? Component.literal(faction.getName())
                : serverPlayer.getDisplayName();
            serverPlayer.server.getPlayerList().broadcastSystemMessage(
                Component.literal("§6").append(claimant).append(" §ehas claimed the supply drop!"), false);
            }
        }
        if (level.getBlockEntity(pos) instanceof CarePackageBlockEntity blockEntity) {
            serverPlayer.openMenu(blockEntity);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CarePackageBlockEntity blockEntity) {
            BlockPos fillerPos = pos.relative(getFillerDirection(state));
            if (level.getBlockState(fillerPos).is(ModBlocks.CARE_PACKAGE_FILLER.get())) {
                level.removeBlock(fillerPos, false);
            }
            Containers.dropContents(level, pos, blockEntity);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> blockEntityType) {
        return null;
    }
}
