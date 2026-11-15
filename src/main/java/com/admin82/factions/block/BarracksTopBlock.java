package com.admin82.factions.block;

import com.admin82.factions.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Top half of the Barracks locker. The locker is drawn by the barracks below; this only gives the top its hitbox.
 * Using it uses the barracks; it can't be broken by itself (break the bottom) and goes when the barracks goes.
 */
public class BarracksTopBlock extends Block {

    public static final MapCodec<BarracksTopBlock> CODEC = simpleCodec(BarracksTopBlock::new);

    public BarracksTopBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        // The Medieval barracks is a one-block tent: nothing up here to hit or select
        return com.admin82.factions.SkinState.medieval ? Shapes.empty() : BarracksBlock.SHAPE;
    }

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockState below = level.getBlockState(pos.below());
        if (below.is(ModBlocks.BARRACKS.get())) {
            return below.useWithoutItem(level, player, new BlockHitResult(hit.getLocation(), hit.getDirection(), pos.below(), hit.isInside()));
        }
        if (!level.isClientSide) level.removeBlock(pos, false);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** A top left without its barracks removes itself. */
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.getBlockState(pos.below()).is(ModBlocks.BARRACKS.get())) level.removeBlock(pos, false);
    }

    /** Pick block gives the Barracks. */
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level,
                                                                net.minecraft.core.BlockPos pos, BlockState state) {
        return new net.minecraft.world.item.ItemStack(com.admin82.factions.registry.ModItems.BARRACKS.get());
    }
}
