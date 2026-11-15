package com.admin82.factions.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Top half of the outpost storage box: holds the space of the computer that sits on the table (the model is drawn by
 * the bottom half), so nothing can be built or flow into it. Using it opens the storage. Unbreakable; it goes with
 * the outpost.
 */
public class OutpostStorageTopBlock extends Block {

    public static final DirectionProperty FACING = OutpostStorageBlock.FACING;

    /**
     * One box round the whole computer (base, keyboard, stand and screen) when facing north, in pixels of this block:
     * {x0, y0, z0, x1, y1, z1}. A single box gives a clean outline rather than a cluster of small ones.
     */
    private static final double[][] PARTS = {
            {2, 0, 2, 14, 13, 12},
    };
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] p : PARTS) shape = Shapes.or(shape, turned(p, d));
            SHAPES.put(d, shape);
        }
    }

    /** One part turned like the model's blockstate "y" rotation (north 0, east 90, south 180, west 270). */
    private static VoxelShape turned(double[] p, Direction facing) {
        double x0 = p[0], z0 = p[2], x1 = p[3], z1 = p[5];
        return switch (facing) {
            case EAST -> box(16 - z1, p[1], x0, 16 - z0, p[4], x1);
            case SOUTH -> box(16 - x1, p[1], 16 - z1, 16 - x0, p[4], 16 - z0);
            case WEST -> box(z0, p[1], 16 - x1, z1, p[4], 16 - x0);
            default -> box(x0, p[1], z0, x1, p[4], z1);
        };
    }

    public OutpostStorageTopBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockState below = level.getBlockState(pos.below());
        if (below.getBlock() instanceof OutpostStorageBlock box) {
            return box.useWithoutItem(below, level, pos.below(), player, hit);
        }
        return InteractionResult.PASS;
    }

    /** Pick block gives an Outpost. */
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level,
                                                                net.minecraft.core.BlockPos pos, BlockState state) {
        return new net.minecraft.world.item.ItemStack(com.admin82.factions.registry.ModItems.OUTPOST.get());
    }
}
