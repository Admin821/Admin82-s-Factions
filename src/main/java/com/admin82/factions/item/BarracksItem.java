package com.admin82.factions.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class BarracksItem extends BlockItem {

    public BarracksItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.barracks.line1");
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.barracks.line2");
        tooltip.add(Component.empty());
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.barracks.line3");
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.barracks.line4");
    }
}
