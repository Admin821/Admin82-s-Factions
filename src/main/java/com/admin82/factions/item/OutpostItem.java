package com.admin82.factions.item;

import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.network.packet.OpenOutpostPlacementPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class OutpostItem extends Item {

    public OutpostItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.outpost.line1");
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.outpost.line2");
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.outpost.line3");
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;

        ServerPlayer player = (ServerPlayer) ctx.getPlayer();
        if (player == null) return InteractionResult.PASS;

        // Player must be in a faction
        FactionManager fmgr    = FactionManager.get((ServerLevel) level);
        Faction        faction = fmgr.getFactionForPlayer(player.getUUID());
        if (faction == null) {
            player.displayClientMessage(
                    Component.literal("§cYou must be in a faction to place an Outpost."), true);
            return InteractionResult.FAIL;
        }

        // Target position: face above the clicked block
        BlockPos targetPos = ctx.getClickedPos().relative(ctx.getClickedFace());
        if (!level.getBlockState(targetPos).canBeReplaced()) {
            player.displayClientMessage(
                    Component.literal("§cThat position is blocked."), true);
            return InteractionResult.FAIL;
        }

        String dim = level.dimension().location().toString();

        // Store pending placement and open confirmation GUI
        OutpostData.get(player.server).setPending(player.getUUID(), targetPos, dim);
        PacketDistributor.sendToPlayer(player, new OpenOutpostPlacementPacket(targetPos, dim));
        return InteractionResult.CONSUME;
    }
}
