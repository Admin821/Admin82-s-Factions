package com.admin82.factions.block;

import com.admin82.factions.economy.ExchangeManager;
import com.admin82.factions.menu.CurrencyExchangeMenu;
import com.admin82.factions.network.packet.ExchangeActionPacket;
import com.admin82.factions.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/**
 * The Currency Exchange block, lets players swap items for coins at admin-set rates. A field ATM two blocks tall:
 * this is the bottom half, with an invisible {@link CurrencyExchangeFillerBlock} above it.
 */
public class CurrencyExchangeBlock extends Block implements MultiblockPreview {

    public static final MapCodec<CurrencyExchangeBlock> CODEC = simpleCodec(CurrencyExchangeBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public CurrencyExchangeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos above = context.getClickedPos().above();
        if (above.getY() >= context.getLevel().getMaxBuildHeight() || !context.getLevel().getBlockState(above).canBeReplaced(context)) {
            if (context.getPlayer() != null && !context.getLevel().isClientSide) {
                context.getPlayer().displayClientMessage(
                        Component.literal("§cNot enough space! The Currency Exchange is 2 blocks tall."), true);
            }
            return null;
        }
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** The ATM's footprint; the same for both halves. */
    static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 16, 15);

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return SHAPE;
    }

    // Prevent the block from occluding adjacent block faces.
    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;

        BlockPos fillerPos = pos.above();
        if (!level.getBlockState(fillerPos).canBeReplaced()) {
            level.removeBlock(pos, false);
            if (placer instanceof Player player && !player.getAbilities().instabuild) {
                player.getInventory().add(new ItemStack(state.getBlock()));
                player.displayClientMessage(
                        Component.literal("§cNot enough space! The Currency Exchange is 2 blocks tall."), true);
            }
            return;
        }

        level.setBlock(fillerPos, ModBlocks.CURRENCY_EXCHANGE_FILLER.get().defaultBlockState()
            .setValue(CurrencyExchangeFillerBlock.FACING, state.getValue(FACING)), 3);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
        var server = sp.getServer(); if (server == null) return InteractionResult.PASS;

        // Collect rates to send to client
        ExchangeManager exchange = ExchangeManager.get(server);
        Map<String, Long> rates = exchange.getRates();
        boolean isOp = sp.hasPermissions(2);

        MenuProvider provider = new SimpleMenuProvider(
                (id, inv, p) -> new CurrencyExchangeMenu(id, inv, pos),
                Component.literal("Currency Exchange"));
        sp.openMenu(provider, buf -> {
            buf.writeBlockPos(pos);
            buf.writeBoolean(isOp);
            buf.writeBoolean(exchange.isBothWaysExchange());
            buf.writeVarInt(rates.size());
            for (var e : rates.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeLong(e.getValue());
            }
            buf.writeVarInt(0); // LDLib2 UISyncManager initial pack: 0 sync values
        });
        ExchangeActionPacket.sendSync(sp);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockPos fillerPos = pos.above();
            if (level.getBlockState(fillerPos).is(ModBlocks.CURRENCY_EXCHANGE_FILLER.get())) {
                level.removeBlock(fillerPos, false);
            }
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
}
