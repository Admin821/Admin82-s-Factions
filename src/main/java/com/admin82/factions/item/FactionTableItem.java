package com.admin82.factions.item;

import com.admin82.factions.block.FactionTableBlock;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.menu.FactionTableMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class FactionTableItem extends BlockItem {

    public FactionTableItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.faction_table.line1");
        tooltip.add(Component.empty());
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.faction_table.line2");
        TooltipHelper.addOptional(tooltip, "tooltip.adminsfactions.faction_table.line3");
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS; // Server authoritative

        ServerPlayer player = (ServerPlayer) context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        FactionManager manager = FactionManager.get((ServerLevel) level);
        Faction playerFaction = manager.getFactionForPlayer(player.getUUID());

        if (playerFaction == null) {
            // ── No faction: open Create Faction screen WITHOUT placing the block ──
            BlockPos targetPos = context.getClickedPos().relative(context.getClickedFace());

            // Position must be free to place into
            if (!level.getBlockState(targetPos).canBeReplaced()) {
                return InteractionResult.FAIL;
            }

            // ── Must have a free 2×2 footprint (east, south, south-east) ────────────
            if (!FactionTableBlock.has2x2Space(level, targetPos)) {
                player.displayClientMessage(
                        Component.literal("§cNot enough space! The Faction Table needs a clear 2×2 area."), true);
                return InteractionResult.FAIL;
            }

            // ── Must be placed outdoors (at or near the surface) and not covered ──
            int surfaceY = ((ServerLevel) level).getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    targetPos.getX(), targetPos.getZ());
            if (targetPos.getY() < surfaceY - 1) {
                player.displayClientMessage(
                        Component.literal("§c[Faction Table] Must be placed at or above the surface — cannot be buried underground."), true);
                return InteractionResult.FAIL;
            }

            // Store where the table will go once the faction is confirmed
            manager.setPendingCreation(player.getUUID(),
                    new FactionManager.TableLocation(targetPos, level.dimension().location().toString()));

            // Open the menu in "create faction" mode (faction = null)
            player.openMenu(
                    new SimpleMenuProvider(
                            (id, inv, p) -> new FactionTableMenu(id, inv, targetPos, null),
                            Component.translatable("gui.adminsfactions.faction_table")),
                    buf -> {
                        buf.writeBlockPos(targetPos);
                        buf.writeBoolean(false); // No faction
                        buf.writeDouble(EconomyManager.get(player.server).getClaimRateMultiplier());
                        // Vassal data — must always be written to match FactionTableBlock's protocol
                        buf.writeBoolean(false); // isVassal
                        buf.writeBoolean(false); // isSuzerain
                        buf.writeUtf("");        // vassalSuzerainName
                        buf.writeLong(0L);       // vassalPendingTax
                        buf.writeVarInt(0);      // vassalSubjects count
                        buf.writeVarInt(0);      // LDLib2 UISyncManager initial pack: 0 sync values
                    });

            // Resync the target block to the client so no ghost block appears
            player.connection.send(new ClientboundBlockUpdatePacket(level, targetPos));
            return InteractionResult.CONSUME;
        }

        // ── Has a faction: let BlockItem handle placement normally.
        // setPlacedBy in FactionTableBlock will catch duplicates.
        return super.useOn(context);
    }
}
