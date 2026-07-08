package com.admin82.factions.item;

import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
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
        tooltip.add(Component.literal("§7Used to create and manage a Faction."));
        tooltip.add(Component.literal("§7Right-click to open the faction menu."));
        tooltip.add(Component.empty());
        tooltip.add(Component.literal("§8» Without a faction: opens the creation screen."));
        tooltip.add(Component.literal("§8  The block is only placed after confirming."));
        tooltip.add(Component.literal("§8» Only faction members can open a linked table."));
        tooltip.add(Component.literal("§8» Ops (permission level 2+) can always break it."));
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
