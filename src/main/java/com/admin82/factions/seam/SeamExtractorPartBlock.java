package com.admin82.factions.seam;

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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * The other 11 cells of a Seam Extractor. CX/CY/CZ are its place in the machine, counted from the bottom north-west
 * cell; the one on top of that cell draws the roof. Using or breaking a part uses or breaks the extractor.
 */
public class SeamExtractorPartBlock extends Block {

    public static final IntegerProperty CX = IntegerProperty.create("cx", 0, 1);
    public static final IntegerProperty CY = IntegerProperty.create("cy", 0, 2);
    public static final IntegerProperty CZ = IntegerProperty.create("cz", 0, 1);

    public SeamExtractorPartBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CX, 0).setValue(CY, 0).setValue(CZ, 0));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return simpleCodec(SeamExtractorPartBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CX, CY, CZ);
    }

    public static BlockPos origin(BlockPos pos, BlockState state) {
        return SeamExtractorBlock.origin(pos, state.getValue(CX), state.getValue(CY), state.getValue(CZ));
    }

    /** Where this part's extractor is (one of the four bottom cells), or null if it's gone. */
    @Nullable
    public static BlockPos main(BlockGetter level, BlockPos pos, BlockState state) {
        BlockPos o = origin(pos, state);
        for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
            BlockPos c = o.offset(dx, 0, dz);
            BlockState s = level.getBlockState(c);
            if (s.is(ModBlocks.SEAM_EXTRACTOR.get()) && s.getValue(SeamExtractorBlock.CX) == dx && s.getValue(SeamExtractorBlock.CZ) == dz) return c;
        }
        return null;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return SeamExtractorBlock.outline(state.getValue(CX), state.getValue(CY), state.getValue(CZ));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.block();
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos main = main(level, pos, state);
        if (main == null) {
            if (!level.isClientSide) level.removeBlock(pos, false);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        BlockHitResult mainHit = new BlockHitResult(hit.getLocation(), hit.getDirection(), main, hit.isInside());
        return level.getBlockState(main).useWithoutItem(level, player, mainHit);
    }

    /** A part left without its extractor removes itself. */
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (main(level, pos, state) == null) level.removeBlock(pos, false);
    }

    /** Breaking any part breaks the extractor (which drops itself unless in creative). */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos main = main(level, pos, state);
            if (main != null) {
                boolean drop = !player.getAbilities().instabuild && player.hasCorrectToolForDrops(level.getBlockState(main));
                level.destroyBlock(main, drop, player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** Losing a part some other way (an explosion, a command) takes the extractor down too, dropping it. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            BlockPos main = main(level, pos, state);
            if (main != null) level.destroyBlock(main, true);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    /** Pick block gives the Seam Extractor. */
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level,
                                                                net.minecraft.core.BlockPos pos, BlockState state) {
        return new net.minecraft.world.item.ItemStack(com.admin82.factions.registry.ModItems.SEAM_EXTRACTOR.get());
    }
}
