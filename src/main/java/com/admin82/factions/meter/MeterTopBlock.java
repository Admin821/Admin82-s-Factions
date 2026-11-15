package com.admin82.factions.meter;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.BlockGetter;

/** Top half of a meter: the dial with the networks and rates. Breaking it breaks the whole meter. */
public class MeterTopBlock extends Block {

    /** The monitor, stand and keyboard (the model is drawn by the bottom half). */
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 12, 14);

    public MeterTopBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) MeterService.open(sp, pos.below(), false);
        return InteractionResult.CONSUME;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // The bottom half carries the item and the storage: break it too (dropping the meter unless in creative).
        if (!level.isClientSide && level.getBlockState(pos.below()).getBlock() instanceof MeterBlock) {
            level.destroyBlock(pos.below(), !player.isCreative());
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Pick block gives the meter below it. */
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level,
                                                                net.minecraft.core.BlockPos pos, BlockState state) {
        return new net.minecraft.world.item.ItemStack(level.getBlockState(pos.below()).getBlock().asItem());
    }
}
