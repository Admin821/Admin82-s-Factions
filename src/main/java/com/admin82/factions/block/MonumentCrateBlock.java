package com.admin82.factions.block;

import com.admin82.factions.blockentity.MonumentCrateBlockEntity;
import com.admin82.factions.monument.MonumentCrateType;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

public class MonumentCrateBlock extends BaseEntityBlock {
    public static final MapCodec<MonumentCrateBlock> CODEC = simpleCodec(MonumentCrateBlock::new);
    public static final EnumProperty<MonumentCrateType> TYPE = EnumProperty.create("type", MonumentCrateType.class);
    /** The side the lid opens towards (faces whoever placed it). */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** Lid up while someone has the crate open. */
    public static final BooleanProperty OPEN = BlockStateProperties.OPEN;

    // The ammo can model's footprint (x 2-14, z 1-16 facing north), turned for each facing
    private static final VoxelShape NORTH = box(2, 0, 1, 14, 9, 16);
    private static final VoxelShape SOUTH = box(2, 0, 0, 14, 9, 15);
    private static final VoxelShape EAST = box(0, 0, 2, 15, 9, 14);
    private static final VoxelShape WEST = box(1, 0, 2, 16, 9, 14);

    public MonumentCrateBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TYPE, MonumentCrateType.SUPPLY)
                .setValue(FACING, Direction.NORTH).setValue(OPEN, false));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MonumentCrateBlockEntity(pos, state); }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TYPE, FACING, OPEN);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            default -> NORTH;
        };
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** Re-checks whether anyone still has the crate open (scheduled by the block entity), like a barrel. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (level.getBlockEntity(pos) instanceof MonumentCrateBlockEntity crate) crate.recheckOpen();
    }

    /** Checked before placing, so nothing has to be removed and handed back afterwards (which could duplicate it). */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (context.getLevel() instanceof ServerLevel serverLevel && context.getPlayer() instanceof ServerPlayer player) {
            boolean allowed = player.hasPermissions(2) && MonumentData.get(serverLevel.getServer())
                    .getAt(context.getClickedPos(), serverLevel.dimension().location().toString()) != null;
            if (!allowed) {
                player.displayClientMessage(Component.literal("§cMonument crates can only be placed by operators inside a monument."), true);
                return null;
            }
        }
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(placer instanceof ServerPlayer player)) return;
        MonumentData data = MonumentData.get(serverLevel.getServer());
        MonumentEntry monument = data.getAt(pos, serverLevel.dimension().location().toString());
        if (!player.hasPermissions(2) || monument == null) {
            level.removeBlock(pos, false);
            if (!player.getAbilities().instabuild) player.getInventory().add(stack.copyWithCount(1));
            player.displayClientMessage(Component.literal("§cMonument crates can only be placed by operators inside a monument."), true);
            return;
        }
        if (level.getBlockEntity(pos) instanceof MonumentCrateBlockEntity crate) {
            String poolName = monument.getLootPoolNames().getFirst();
            crate.link(monument.id, poolName);
            monument.linkCrate(pos, poolName);
            data.changed();
            player.displayClientMessage(Component.literal("§aLinked §e" + poolName + " §acrate to §e"
                    + monument.getName() + "§a. Sneak-use to change pool."), true);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof MonumentCrateBlockEntity crate)) return InteractionResult.PASS;
        if (player.isShiftKeyDown() && player instanceof ServerPlayer serverPlayer && serverPlayer.hasPermissions(2)) {
            if (crate.getMonumentId() != null) {
                MonumentData data = MonumentData.get(serverPlayer.server);
                MonumentEntry monument = data.get(crate.getMonumentId());
                if (monument != null) {
                    String poolName = monument.nextLootPool(crate.getPoolName());
                    crate.link(monument.id, poolName);
                    level.setBlock(pos, state.setValue(TYPE, crate.getCrateType()), 3);
                    monument.linkCrate(pos, poolName);
                    data.changed();
                    player.displayClientMessage(Component.literal("§aCrate loot pool: §e" + poolName), true);
                }
            }
            return InteractionResult.CONSUME;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(crate);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }
}