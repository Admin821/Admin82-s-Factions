package com.admin82.factions.block;

import com.admin82.factions.blockentity.MonumentControllerBlockEntity;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.monument.MonumentView;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.faction.FactionManager;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.Comparator;

public class MonumentControllerBlock extends BaseEntityBlock {
    public static final MapCodec<MonumentControllerBlock> CODEC = simpleCodec(MonumentControllerBlock::new);

    public MonumentControllerBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MonumentControllerBlockEntity(pos, state); }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(placer instanceof ServerPlayer player)) return;
        if (!player.hasPermissions(2)) {
            level.removeBlock(pos, false);
            if (!player.getAbilities().instabuild) player.getInventory().add(stack.copyWithCount(1));
            player.displayClientMessage(Component.literal("§cOnly server operators can place monument controllers."), true);
            return;
        }
        MonumentData data = MonumentData.get(serverLevel.getServer());
        String name = uniqueName(data);
        data.add(new MonumentEntry(UUID.randomUUID(), name, 1, pos, serverLevel.dimension().location().toString()));
        player.displayClientMessage(Component.literal("§aCreated §e" + name + "§a. Use §f/factions monument edit " + name + "§a."), false);
    }

    private static String uniqueName(MonumentData data) {
        int number = data.getAll().size() + 1;
        while (data.getByName("Monument-" + number) != null) number++;
        return "Monument-" + number;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.hasPermissions(2)) {
            player.displayClientMessage(Component.literal("§cOnly server operators can edit monument loot pools."), true);
            return InteractionResult.FAIL;
        }
        MonumentData data = MonumentData.get(serverPlayer.server);
        MonumentEntry selected = data.getByController(pos, level.dimension().location().toString());
        if (selected == null) return InteractionResult.PASS;
        var factionManager = FactionManager.get(serverPlayer.server);
        var views = data.getAll().stream().map(entry -> MonumentView.from(entry, factionManager, entry.id.equals(selected.id)))
                .sorted(Comparator.comparing(MonumentView::name, String.CASE_INSENSITIVE_ORDER)).toList();
        serverPlayer.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new MonumentMenu(id, inventory, views, selected.id),
                Component.literal("Monument Controller")), buf -> {
            buf.writeVarInt(views.size());
            views.forEach(view -> view.write(buf));
            buf.writeBoolean(true);
            buf.writeUUID(selected.id);
        });
        return InteractionResult.CONSUME;
    }
}