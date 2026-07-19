package com.admin82.factions.block;

import com.admin82.factions.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class MarketFillerBlock extends Block {

    public static final MapCodec<MarketFillerBlock> CODEC = simpleCodec(MarketFillerBlock::new);

    public MarketFillerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

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
        BlockPos mainPos = pos.east();
        BlockState mainState = level.getBlockState(mainPos);
        if (mainState.is(ModBlocks.MARKET.get())) {
            BlockHitResult mainHit = new BlockHitResult(hit.getLocation(), hit.getDirection(), mainPos, hit.isInside());
            return mainState.useWithoutItem(level, player, mainHit);
        }
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockPos mainPos = pos.east();
            if (level.getBlockState(mainPos).is(ModBlocks.MARKET.get())) {
                level.removeBlock(mainPos, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
