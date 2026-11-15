package com.admin82.factions.item;

import com.admin82.factions.network.packet.OpenSeamViewerPacket;
import com.admin82.factions.seam.SeamCell;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/** Right-click to scan the seams of the 9×9 chunks around you and see them on a map. */
public class SeamViewerItem extends Item {

    /** Chunks across the scanned square (centred on the player). */
    public static final int SIZE = 9;

    public SeamViewerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server && player instanceof ServerPlayer sp) {
            var center = sp.chunkPosition();
            var memory = com.admin82.factions.seam.SeamMemory.get(server.getServer());
            memory.remember(sp, SeamCell.area(server, center, SIZE));
            if (sp.containerMenu != sp.inventoryMenu) sp.doCloseContainer();
            PacketDistributor.sendToPlayer(sp, new OpenSeamViewerPacket(center.x, center.z, SIZE,
                    memory.around(sp, center, com.admin82.factions.network.packet.SeamMemoryPacket.WINDOW)));
            level.playSound(null, sp.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 0.6f, 1.4f);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§7Right-click to scan the ore seams of the 9×9 chunks around you."));
        tooltip.add(Component.literal("§7Everything you scan is kept on its map: drag it to look around."));
        tooltip.add(Component.literal("§7Your faction shares one map: it also shows on the faction table's territory map."));
        tooltip.add(Component.literal("§8Seams can only be mined with a Seam Extractor."));
    }
}
