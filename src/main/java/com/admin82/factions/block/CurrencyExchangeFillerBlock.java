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

/** Top half of the Currency Exchange (the screen and sign); the model is drawn by the bottom half below it. */
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
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        // The Medieval market stall only reaches 3 pixels into this block
        return com.admin82.factions.SkinState.medieval ? MEDIEVAL_SHAPE : CurrencyExchangeBlock.SHAPE;
    }

    private static final VoxelShape MEDIEVAL_SHAPE = Block.box(0, 0, 0, 16, 3, 16);

    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    /**
     * Exchanges placed before it became 2 tall left their filler beside them; a filler with no exchange under it
     * removes itself (on a random tick, or as soon as someone clicks it).
     */
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, net.minecraft.server.level.ServerLevel level, BlockPos pos, net.minecraft.util.RandomSource random) {
        if (!level.getBlockState(pos.below()).is(ModBlocks.CURRENCY_EXCHANGE.get())) level.removeBlock(pos, false);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide && !level.getBlockState(pos.below()).is(ModBlocks.CURRENCY_EXCHANGE.get())) {
            level.removeBlock(pos, false);
            return InteractionResult.CONSUME;
        }
        BlockPos mainPos = pos.below();
        BlockState mainState = level.getBlockState(mainPos);
        if (mainState.is(ModBlocks.CURRENCY_EXCHANGE.get())) {
            BlockHitResult mainHit = new BlockHitResult(hit.getLocation(), hit.getDirection(), mainPos, hit.isInside());
            return mainState.useWithoutItem(level, player, mainHit);
        }
        return InteractionResult.PASS;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos mainPos = pos.below();
            if (level.getBlockState(mainPos).is(ModBlocks.CURRENCY_EXCHANGE.get())) {
                level.destroyBlock(mainPos, !player.getAbilities().instabuild, player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockPos mainPos = pos.below();
            if (level.getBlockState(mainPos).is(ModBlocks.CURRENCY_EXCHANGE.get())) {
                level.removeBlock(mainPos, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Pick block gives the Currency Exchange. */
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level,
                                                                net.minecraft.core.BlockPos pos, BlockState state) {
        return new net.minecraft.world.item.ItemStack(com.admin82.factions.registry.ModItems.CURRENCY_EXCHANGE_BLOCK.get());
    }
}
