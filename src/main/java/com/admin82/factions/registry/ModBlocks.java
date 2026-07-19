package com.admin82.factions.registry;

import com.admin82.factions.block.BarracksBlock;
import com.admin82.factions.block.CurrencyExchangeBlock;
import com.admin82.factions.block.CurrencyExchangeFillerBlock;
import com.admin82.factions.block.FactionTableBlock;
import com.admin82.factions.block.FactionTableFillerBlock;
import com.admin82.factions.block.MarketBlock;
import com.admin82.factions.block.MarketFillerBlock;
import com.admin82.factions.block.OutpostManagerBlock;
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
                            .strength(3.5f, 3_600_000.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()
            )
    );

    public static final DeferredBlock<FactionTableBlock> FACTION_TABLE = BLOCKS.register(
            "faction_table",
            () -> new FactionTableBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.5f, 3_600_000.0f)
                            .sound(SoundType.WOOD)
                            .requiresCorrectToolForDrops()
            )
    );

    /** Invisible filler blocks that complete the Faction Table's 2×2 physical footprint. */
    public static final DeferredBlock<FactionTableFillerBlock> FACTION_TABLE_FILLER = BLOCKS.register(
            "faction_table_filler",
            () -> new FactionTableFillerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.5f, 3_600_000.0f)
                            .sound(SoundType.WOOD)
                            .requiresCorrectToolForDrops()
                            .noLootTable()
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

    /** Invisible filler block that completes the Market's 1×2 physical footprint. */
    public static final DeferredBlock<MarketFillerBlock> MARKET_FILLER = BLOCKS.register(
            "market_filler",
            () -> new MarketFillerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(3.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()
                            .noLootTable()
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

    /** Invisible filler block that completes the Currency Exchange's 1×2 physical footprint. */
    public static final DeferredBlock<CurrencyExchangeFillerBlock> CURRENCY_EXCHANGE_FILLER = BLOCKS.register(
            "currency_exchange_filler",
            () -> new CurrencyExchangeFillerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.GOLD)
                            .strength(3.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noLootTable()
            )
    );

    public static final DeferredBlock<OutpostManagerBlock> OUTPOST_MANAGER = BLOCKS.register(
            "outpost_manager",
            () -> new OutpostManagerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(5.0f, 1200.0f)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()
            )
    );
}
