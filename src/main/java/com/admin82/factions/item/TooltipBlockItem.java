package com.admin82.factions.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class TooltipBlockItem extends BlockItem {

    private final String tooltipPrefix;

    public TooltipBlockItem(Block block, Item.Properties properties, String tooltipPrefix) {
        super(block, properties);
        this.tooltipPrefix = tooltipPrefix;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        for (int line = 1; line <= 5; line++) {
            TooltipHelper.addOptional(tooltip, tooltipPrefix + ".line" + line);
        }
    }
}