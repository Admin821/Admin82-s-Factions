package com.admin82.factions.block;

import com.admin82.factions.blockentity.OutpostManagerBlockEntity;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.outpost.OutpostService;
import com.admin82.factions.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

public class OutpostManagerBlock extends BaseEntityBlock {

    public static final MapCodec<OutpostManagerBlock> CODEC = simpleCodec(OutpostManagerBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    public OutpostManagerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OutpostManagerBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        removeLegacyHorizontalFillers(level, pos);

        ServerLevel sLevel = (ServerLevel) level;
        String dim = sLevel.dimension().location().toString();

        OutpostData outposts = OutpostData.get(sLevel.getServer());
        OutpostEntry entry   = outposts.getOutpostAtPos(pos, dim);
        if (entry == null) {
            sp.displayClientMessage(Component.literal("§cThis outpost has no registered data."), true);
            return InteractionResult.FAIL;
        }

        OutpostService.openManager(sp, entry);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            removeAllFillers(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void removeAllFillers(Level level, BlockPos pos) {
        BlockPos topFiller = pos.above();
        if (level.getBlockState(topFiller).is(ModBlocks.OUTPOST_MANAGER_FILLER.get())) {
            level.removeBlock(topFiller, false);
        }
        removeLegacyHorizontalFillers(level, pos);
    }

    private static void removeLegacyHorizontalFillers(Level level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos fillerPos = pos.relative(direction);
            if (level.getBlockState(fillerPos).is(ModBlocks.OUTPOST_MANAGER_FILLER.get())) {
                level.removeBlock(fillerPos, false);
            }
        }
    }
}
