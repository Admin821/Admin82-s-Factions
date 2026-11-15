package com.admin82.factions.block;

import com.admin82.factions.blockentity.OutpostStorageBlockEntity;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.registry.ModBlockEntities;
import com.admin82.factions.storage.ItemVaultService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * The storage box placed with an outpost: a table with a computer on it whose screen shows what's stored. It holds a
 * limited number of different items (more with outpost levels) and goes to whoever captures the outpost. It can't be
 * broken; it disappears with the outpost.
 */
public class OutpostStorageBlock extends Block implements EntityBlock {

    /** The way the computer's screen faces. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** The table top, its legs and the shelf (the computer on top pokes into the block above, so it isn't solid). */
    private static final VoxelShape SHAPE = Shapes.or(
            box(0, 15, 0, 16, 16, 16),
            box(1, 0, 1, 3, 15, 3), box(13, 0, 1, 15, 15, 3),
            box(1, 0, 13, 3, 15, 15), box(13, 0, 13, 15, 15, 15),
            box(1.5, 3, 1.5, 14.5, 4, 14.5));

    public OutpostStorageBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new OutpostStorageBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.OUTPOST_STORAGE.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<OutpostStorageBlockEntity>) OutpostStorageBlockEntity::serverTick;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
        OutpostEntry entry = OutpostData.get(serverLevel.getServer())
                .getOutpostByStorage(pos, serverLevel.dimension().location().toString());
        if (entry == null) {
            sp.displayClientMessage(Component.literal("§7This storage box doesn't belong to an outpost any more."), true);
            return InteractionResult.FAIL;
        }
        ItemVaultService.open(sp, ItemVaultService.Kind.OUTPOST, entry.id);
        return InteractionResult.CONSUME;
    }

    /** Pick block gives an Outpost. */
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level,
                                                                net.minecraft.core.BlockPos pos, BlockState state) {
        return new net.minecraft.world.item.ItemStack(com.admin82.factions.registry.ModItems.OUTPOST.get());
    }
}
