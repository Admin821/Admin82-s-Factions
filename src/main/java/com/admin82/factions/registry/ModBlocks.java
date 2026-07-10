package com.admin82.factions.registry;

import com.admin82.factions.block.BarracksBlock;
import com.admin82.factions.block.CurrencyExchangeBlock;
import com.admin82.factions.block.FactionTableBlock;
import com.admin82.factions.block.MarketBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.admin82.factions.AdminsFactions.MODID;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    public static final DeferredBlock<BarracksBlock> BARRACKS = BLOCKS.register(
            "barracks",
            () -> new BarracksBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(3.5f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()
            )
    );

    public static final DeferredBlock<FactionTableBlock> FACTION_TABLE = BLOCKS.register(
            "faction_table",
            () -> new FactionTableBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.5f)
                            .sound(SoundType.WOOD)
                            .requiresCorrectToolForDrops()
            )
    );

    public static final DeferredBlock<MarketBlock> MARKET = BLOCKS.register(
            "market_block",
            () -> new MarketBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(3.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()
            )
    );

    public static final DeferredBlock<CurrencyExchangeBlock> CURRENCY_EXCHANGE = BLOCKS.register(
            "currency_exchange_block",
            () -> new CurrencyExchangeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.GOLD)
                            .strength(3.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
            )
    );
}
