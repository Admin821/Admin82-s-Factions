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
        tooltip.add(Component.literal("§7The faction's spawn point during wars."));
        tooltip.add(Component.literal("§7Stores kits for each life in war."));
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("§8» Required before creating a faction."));
        tooltip.add(Component.literal("§8» Right-click to manage kits and life assignments."));
    }
}
