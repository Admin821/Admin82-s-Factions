package com.admin82.factions.block;

import com.admin82.factions.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class CurrencyExchangeFillerBlock extends Block {

    public static final MapCodec<CurrencyExchangeFillerBlock> CODEC = simpleCodec(CurrencyExchangeFillerBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    public CurrencyExchangeFillerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

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
                                               Player player, BlockHitResult hit) {
        BlockPos mainPos = pos.relative(state.getValue(FACING).getClockWise());
        BlockState mainState = level.getBlockState(mainPos);
        if (mainState.is(ModBlocks.CURRENCY_EXCHANGE.get())) {
            BlockHitResult mainHit = new BlockHitResult(hit.getLocation(), hit.getDirection(), mainPos, hit.isInside());
            return mainState.useWithoutItem(level, player, mainHit);
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockPos mainPos = pos.relative(state.getValue(FACING).getClockWise());
            if (level.getBlockState(mainPos).is(ModBlocks.CURRENCY_EXCHANGE.get())) {
                level.removeBlock(mainPos, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
