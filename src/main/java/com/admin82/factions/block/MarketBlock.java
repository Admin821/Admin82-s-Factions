package com.admin82.factions.block;

import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.menu.MarketMenu;
import com.admin82.factions.network.packet.SyncMarketPacket;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The Faction Market block — opens the market auction-house UI.
 */
public class MarketBlock extends Block {

    public static final MapCodec<MarketBlock> CODEC = simpleCodec(MarketBlock::new);

    public MarketBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

        var server = sp.getServer(); if (server == null) return InteractionResult.PASS;
        var eco     = EconomyManager.get(server);
        var market  = MarketManager.get(server);
        var faction = FactionManager.get(server.overworld()).getFactionForPlayer(sp.getUUID());

        MenuProvider provider = new SimpleMenuProvider(
                (id, inv, p) -> new MarketMenu(id, inv, pos),
                Component.literal("Faction Market"));
        sp.openMenu(provider, buf -> buf.writeBlockPos(pos));

        // Immediately send current market data
        int myListings = market.countPlayerListings(sp.getUUID());
        int maxSlots   = faction != null ? faction.getMembers().size() : 1;
        PacketDistributor.sendToPlayer(sp, new SyncMarketPacket(
                market.getListings().stream().toList(),
                eco.getWallet(sp.getUUID()),
                myListings, maxSlots));

        return InteractionResult.CONSUME;
    }
}
