package com.admin82.factions.block;

import com.admin82.factions.blockentity.MonumentCrateBlockEntity;
import com.admin82.factions.monument.MonumentCrateType;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public class MonumentCrateBlock extends BaseEntityBlock {
    public static final MapCodec<MonumentCrateBlock> CODEC = simpleCodec(MonumentCrateBlock::new);
    public static final EnumProperty<MonumentCrateType> TYPE = EnumProperty.create("type", MonumentCrateType.class);

    public MonumentCrateBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(TYPE, MonumentCrateType.SUPPLY));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MonumentCrateBlockEntity(pos, state); }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(TYPE);
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