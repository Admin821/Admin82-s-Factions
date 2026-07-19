package com.admin82.factions.block;

import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.menu.MarketMenu;
import com.admin82.factions.network.packet.SyncMarketPacket;
import com.admin82.factions.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.network.PacketDistributor;
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

/**
 * The Faction Market block — opens the market auction-house UI.
 */
public class MarketBlock extends Block {

    public static final MapCodec<MarketBlock> CODEC = simpleCodec(MarketBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public MarketBlock(Properties properties) {
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
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    static Direction getFillerDirection(BlockState state) {
        return state.getValue(FACING).getCounterClockWise();
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
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;

        BlockPos fillerPos = pos.relative(getFillerDirection(state));
        if (!level.getBlockState(fillerPos).canBeReplaced()) {
            level.removeBlock(pos, false);
            if (placer instanceof Player player && !player.getAbilities().instabuild) {
                player.getInventory().add(new ItemStack(state.getBlock()));
                player.displayClientMessage(
                        Component.literal("§cNot enough space! Market needs a clear 1×2 area."), true);
            }
            return;
        }

        level.setBlock(fillerPos, ModBlocks.MARKET_FILLER.get().defaultBlockState()
                .setValue(MarketFillerBlock.FACING, state.getValue(FACING)), 3);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        var server = sp.getServer(); if (server == null) return InteractionResult.PASS;
        var eco     = EconomyManager.get(server);
        var market  = MarketManager.get(server);
        var faction = FactionManager.get(server.overworld()).getFactionForPlayer(sp.getUUID());

        MenuProvider provider = new SimpleMenuProvider(
                (id, inv, p) -> new MarketMenu(id, inv, pos),
                Component.literal("Faction Market"));
        sp.openMenu(provider, buf -> { buf.writeBlockPos(pos); buf.writeVarInt(0); }); // 0 = LDLib2 UISyncManager initial pack

        // Immediately send current market data
        int myListings = market.countPlayerListings(sp.getUUID());
        int maxSlots   = faction != null ? faction.getMembers().size() : 1;
        PacketDistributor.sendToPlayer(sp, new SyncMarketPacket(
                market.getListings().stream().toList(),
                eco.getWallet(sp.getUUID()),
                myListings, maxSlots));

        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockPos fillerPos = pos.relative(getFillerDirection(state));
            if (level.getBlockState(fillerPos).is(ModBlocks.MARKET_FILLER.get())) {
                level.removeBlock(fillerPos, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
