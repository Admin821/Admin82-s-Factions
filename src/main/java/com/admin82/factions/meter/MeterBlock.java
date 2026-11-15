package com.admin82.factions.meter;

import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Bottom half of a power or fluid meter (the battery or tank). Placing it also places the meter on top.
 */
public class MeterBlock extends BaseEntityBlock implements com.admin82.factions.block.MultiblockPreview {

    /** Where the screen faces. The side settings turn with it. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public final MeterKind kind;
    private final Supplier<Block> top;

    public MeterBlock(Properties properties, MeterKind kind, Supplier<Block> top) {
        super(properties);
        this.kind = kind;
        this.top = top;
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
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
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(p -> new MeterBlock(p, kind, top));
    }

    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MeterBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.METER.get(), MeterBlockEntity::serverTick);
    }

    /** Needs room for the meter on top; refused up front so nothing has to be handed back. */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos above = context.getClickedPos().above();
        if (above.getY() >= context.getLevel().getMaxBuildHeight() || !context.getLevel().getBlockState(above).canBeReplaced()) return null;
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        level.setBlock(pos.above(), top.get().defaultBlockState(), 3);
        if (placer instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof MeterBlockEntity be) {
            be.owner = sp.getUUID();
            be.ownerName = sp.getGameProfile().getName();
            be.setChanged();
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) MeterService.open(sp, pos, true);
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            if (level.getBlockEntity(pos) instanceof MeterBlockEntity be) {
                // Coins go back to whoever placed it; a supplier leaves its network.
                be.payOwner(serverLevel.getServer());
                long coins = be.takeAllCoins();
                if (coins > 0 && be.owner != null) {
                    EconomyManager.get(serverLevel.getServer()).addWallet(be.owner, coins, "Coins from a removed meter");
                }
                MeterNetworks.Network n = MeterNetworks.get(serverLevel.getServer()).get(be.networkId);
                if (n != null && n.suppliers.remove(GlobalPos.of(level.dimension(), pos))) MeterNetworks.get(serverLevel.getServer()).changed();
            }
            if (level.getBlockState(pos.above()).is(top.get())) level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    // Placement preview

    @Override
    public BlockState previewState(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public java.util.List<BlockPos> previewCells(BlockPos pos, BlockState state) {
        return java.util.List.of(pos, pos.above());
    }

    @Override
    public java.util.Map<BlockPos, BlockState> previewExtras(BlockPos pos, BlockState state) {
        return java.util.Map.of(pos.above(), top.get().defaultBlockState());
    }
}
