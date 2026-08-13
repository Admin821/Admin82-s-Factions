package com.admin82.factions.block;

import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.util.BypassManager;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public class OreGeneratorBlock extends Block {
    public static final MapCodec<OreGeneratorBlock> CODEC = simpleCodec(OreGeneratorBlock::new);
    private static final TagKey<Block> ORES = TagKey.create(
            Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "ores"));

    public OreGeneratorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel) || !(placer instanceof ServerPlayer player)) return;
        MonumentData data = MonumentData.get(serverLevel.getServer());
        MonumentEntry monument = data.getAt(pos, serverLevel.dimension().location().toString());
        if (!player.hasPermissions(2) || monument == null) {
            level.removeBlock(pos, false);
            if (!player.getAbilities().instabuild) player.getInventory().add(stack.copyWithCount(1));
            player.displayClientMessage(Component.literal(
                    "§cOre Generators can only be placed by operators inside a monument."), true);
            return;
        }
        monument.linkOreGenerator(pos);
        data.changed();
        player.displayClientMessage(Component.literal(
                "§aOre Generator linked to §e" + monument.getName()
                        + "§a. Enable bypass and use an ore block to configure it."), true);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, net.minecraft.world.InteractionHand hand,
                                              BlockHitResult hitResult) {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.hasPermissions(2)
                || !BypassManager.isBypassing(serverPlayer.getUUID())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(stack.getItem() instanceof BlockItem blockItem) || !isOre(blockItem.getBlock().defaultBlockState())) {
            serverPlayer.displayClientMessage(Component.literal(
                    "§cHold a block tagged as an ore to configure this generator."), true);
            return ItemInteractionResult.FAIL;
        }
        MonumentData data = MonumentData.get(serverPlayer.server);
        MonumentEntry monument = data.getAt(pos, level.dimension().location().toString());
        if (monument == null || !monument.hasOreGenerator(pos)) return ItemInteractionResult.FAIL;
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
        monument.setGeneratorOre(pos, blockId.toString());
        data.changed();
        level.setBlock(pos, blockItem.getBlock().defaultBlockState(), 3);
        serverPlayer.displayClientMessage(Component.literal(
                "§aOre Generator configured as §e" + stack.getHoverName().getString()
                + "§a and activated immediately."), true);
        return ItemInteractionResult.CONSUME;
    }

    private static boolean isOre(BlockState state) {
        if (state.is(ORES)) return true;
        return state.getTags().anyMatch(tag -> {
            ResourceLocation id = tag.location();
            return (id.getNamespace().equals("c") || id.getNamespace().equals("forge"))
                    && (id.getPath().equals("ores") || id.getPath().startsWith("ores/"));
        });
    }
}