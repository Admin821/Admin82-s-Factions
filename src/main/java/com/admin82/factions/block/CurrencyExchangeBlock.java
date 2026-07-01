package com.admin82.factions.block;

import com.admin82.factions.economy.ExchangeManager;
import com.admin82.factions.menu.CurrencyExchangeMenu;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import java.util.Map;

/**
 * The Currency Exchange block — lets players swap items for coins at admin-set rates.
 */
public class CurrencyExchangeBlock extends Block {

    public static final MapCodec<CurrencyExchangeBlock> CODEC = simpleCodec(CurrencyExchangeBlock::new);

    public CurrencyExchangeBlock(Properties properties) {
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

        // Collect rates to send to client
        Map<String, Long> rates = ExchangeManager.get(server).getRates();
        boolean isOp = sp.hasPermissions(2);

        MenuProvider provider = new SimpleMenuProvider(
                (id, inv, p) -> new CurrencyExchangeMenu(id, inv, pos),
                Component.literal("Currency Exchange"));
        sp.openMenu(provider, buf -> {
            buf.writeBlockPos(pos);
            buf.writeBoolean(isOp);
            buf.writeVarInt(rates.size());
            for (var e : rates.entrySet()) {
                buf.writeUtf(e.getKey());
                buf.writeLong(e.getValue());
            }
        });
        return InteractionResult.CONSUME;
    }
}
