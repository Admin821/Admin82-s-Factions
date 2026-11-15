package com.admin82.factions.registry;

import com.admin82.factions.block.BarracksBlock;
import com.admin82.factions.block.CarePackageBlock;
import com.admin82.factions.block.CarePackageFillerBlock;
import com.admin82.factions.block.CurrencyExchangeBlock;
import com.admin82.factions.block.CurrencyExchangeFillerBlock;
import com.admin82.factions.block.FactionTableBlock;
import com.admin82.factions.block.FactionTableFillerBlock;
import com.admin82.factions.block.MarketBlock;
import com.admin82.factions.block.MarketFillerBlock;
import com.admin82.factions.block.MonumentControllerBlock;
import com.admin82.factions.block.MonumentCrateBlock;
import com.admin82.factions.block.OreGeneratorBlock;
import com.admin82.factions.block.OutpostManagerBlock;
import com.admin82.factions.block.OutpostManagerFillerBlock;
import com.admin82.factions.block.OutpostStorageBlock;
import com.admin82.factions.meter.MeterBlock;
import com.admin82.factions.meter.MeterKind;
import com.admin82.factions.meter.MeterTopBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.admin82.factions.AdminsFactions.MODID;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);

    public static final DeferredBlock<Block> CARE_PACKAGE_PARACHUTE = BLOCKS.register(
            "carepackage_parachute",
            () -> new Block(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(3.0f, 3_600_000.0f)
                            .sound(SoundType.WOOL)
                            .noLootTable()
            )
    );

    public static final DeferredBlock<CarePackageBlock> CARE_PACKAGE = BLOCKS.register(
            "carepackage",
            () -> new CarePackageBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(3.0f, 3_600_000.0f)
                            .sound(SoundType.WOOD)
                            .requiresCorrectToolForDrops()
            )
    );

    public static final DeferredBlock<CarePackageFillerBlock> CARE_PACKAGE_FILLER = BLOCKS.register(
            "carepackage_filler",
            () -> new CarePackageFillerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(3.0f, 3_600_000.0f)
                            .sound(SoundType.WOOD)
                            .requiresCorrectToolForDrops()
                            .noLootTable()
            )
    );

    public static final DeferredBlock<BarracksBlock> BARRACKS = BLOCKS.register(
            "barracks",
            () -> new BarracksBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(1.5f, 6.0f)
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
                            .mapColor(MapColor.WOOD)
                            .strength(2.0f, 3.0f)
                            .sound(SoundType.WOOD)
            )
    );

    /** Invisible filler block that completes the Market's 1×2 physical footprint. */
    public static final DeferredBlock<MarketFillerBlock> MARKET_FILLER = BLOCKS.register(
            "market_filler",
            () -> new MarketFillerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0f, 3.0f)
                            .sound(SoundType.WOOD)
                            .noLootTable()
            )
    );

    public static final DeferredBlock<CurrencyExchangeBlock> CURRENCY_EXCHANGE = BLOCKS.register(
            "currency_exchange_block",
            () -> new CurrencyExchangeBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0f, 3.0f)
                            .sound(SoundType.WOOD)
            )
    );

    /** Invisible filler block that completes the Currency Exchange's 1×2 physical footprint. */
    public static final DeferredBlock<CurrencyExchangeFillerBlock> CURRENCY_EXCHANGE_FILLER = BLOCKS.register(
            "currency_exchange_filler",
            () -> new CurrencyExchangeFillerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(2.0f, 3.0f)
                            .sound(SoundType.WOOD)
                            .noLootTable()
            )
    );

    public static final DeferredBlock<OutpostManagerBlock> OUTPOST_MANAGER = BLOCKS.register(
            "outpost_manager",
            () -> new OutpostManagerBlock(
                    // The flag: each break by an attacker knocks off 1 HP, so it breaks quickly by hand.
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(1.5f, 3_600_000.0f)
                            .sound(SoundType.WOOD)
            )
    );

    public static final DeferredBlock<OutpostStorageBlock> OUTPOST_STORAGE = BLOCKS.register(
            "outpost_storage",
            () -> new OutpostStorageBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(-1.0f, 3_600_000.0f) // unbreakable: it goes with the outpost
                            .sound(SoundType.WOOD)
                            .noOcclusion()
            )
    );

    /** The space the storage box's computer takes up in the block above it. */
    public static final DeferredBlock<com.admin82.factions.block.OutpostStorageTopBlock> OUTPOST_STORAGE_TOP = BLOCKS.register(
            "outpost_storage_top",
            () -> new com.admin82.factions.block.OutpostStorageTopBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(-1.0f, 3_600_000.0f)
                            .sound(SoundType.WOOD)
                            .noOcclusion()
                            .noLootTable()
            )
    );

    public static final DeferredBlock<MonumentControllerBlock> MONUMENT_CONTROLLER = BLOCKS.register(
            "monument_controller", () -> new MonumentControllerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(5.0f, 3_600_000.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()));

    public static final DeferredBlock<MonumentCrateBlock> MONUMENT_CRATE = BLOCKS.register(
            "monument_crate", () -> new MonumentCrateBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD).strength(3.0f, 3_600_000.0f).sound(SoundType.WOOD).requiresCorrectToolForDrops()
                    .noOcclusion()));

    public static final DeferredBlock<OreGeneratorBlock> ORE_GENERATOR = BLOCKS.register(
            "ore_generator", () -> new OreGeneratorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE).strength(-1.0f, 3_600_000.0f).sound(SoundType.STONE).noLootTable()));

    /** Invisible filler block that completes the Outpost Manager's 1×2 physical footprint. */
    public static final DeferredBlock<OutpostManagerFillerBlock> OUTPOST_MANAGER_FILLER = BLOCKS.register(
            "outpost_manager_filler",
            () -> new OutpostManagerFillerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .strength(1.5f, 3_600_000.0f)
                            .sound(SoundType.WOOD)
                            .noLootTable()
            )
    );

    // Power and fluid meters: a 2-tall block. The bottom stores, the top is the dial you open.
    public static final DeferredBlock<MeterTopBlock> POWER_METER_TOP = BLOCKS.register(
            "power_meter_top", () -> new MeterTopBlock(meterProperties().noLootTable()));
    public static final DeferredBlock<MeterTopBlock> FLUID_METER_TOP = BLOCKS.register(
            "fluid_meter_top", () -> new MeterTopBlock(meterProperties().noLootTable()));
    public static final DeferredBlock<MeterBlock> POWER_METER = BLOCKS.register(
            "power_meter", () -> new MeterBlock(meterProperties(), MeterKind.POWER, POWER_METER_TOP::get));
    public static final DeferredBlock<MeterBlock> FLUID_METER = BLOCKS.register(
            "fluid_meter", () -> new MeterBlock(meterProperties(), MeterKind.FLUID, FLUID_METER_TOP::get));

    /** Mines the fake ore seam of the chunk it stands in. */
    public static final DeferredBlock<com.admin82.factions.seam.SeamExtractorBlock> SEAM_EXTRACTOR = BLOCKS.register(
            "seam_extractor", () -> new com.admin82.factions.seam.SeamExtractorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(4.0f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()
                    .noOcclusion().pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)
                    .lightLevel(s -> s.getValue(com.admin82.factions.seam.SeamExtractorBlock.ACTIVE) ? 9 : 0)));
    /** The rest of the 2x2x3 Seam Extractor. */
    public static final DeferredBlock<com.admin82.factions.seam.SeamExtractorPartBlock> SEAM_EXTRACTOR_PART = BLOCKS.register(
            "seam_extractor_part", () -> new com.admin82.factions.seam.SeamExtractorPartBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL).strength(4.0f, 6.0f).sound(SoundType.METAL).requiresCorrectToolForDrops()
                    .noOcclusion().noLootTable().pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK)));

    private static BlockBehaviour.Properties meterProperties() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f, 6.0f)
                .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion()
                .pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK); // pistons can't split the halves
    }
}
