package com.admin82.factions.registry;

import com.admin82.factions.item.BarracksItem;
import com.admin82.factions.item.CoinItem;
import com.admin82.factions.item.FactionTableItem;
import com.admin82.factions.item.OutpostItem;
import com.admin82.factions.item.TooltipBlockItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

import static com.admin82.factions.AdminsFactions.MODID;

public class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredItem<BarracksItem> BARRACKS =
            ITEMS.register("barracks",
                    () -> new BarracksItem(ModBlocks.BARRACKS.get(), new Item.Properties()));

    public static final DeferredItem<FactionTableItem> FACTION_TABLE =
            ITEMS.register("faction_table",
                    () -> new FactionTableItem(ModBlocks.FACTION_TABLE.get(), new Item.Properties()));

    // Coins
    public static final DeferredItem<CoinItem> COPPER_COIN =
            ITEMS.register("copper_coin", () -> new CoinItem(new Item.Properties().stacksTo(99)));
    public static final DeferredItem<CoinItem> SILVER_COIN =
            ITEMS.register("silver_coin", () -> new CoinItem(new Item.Properties().stacksTo(99)));
    public static final DeferredItem<CoinItem> GOLD_COIN =
            ITEMS.register("gold_coin", () -> new CoinItem(new Item.Properties().stacksTo(99)));
    public static final DeferredItem<CoinItem> PLATINUM_COIN =
            ITEMS.register("platinum_coin", () -> new CoinItem(new Item.Properties().stacksTo(99)));

    // Block items
    public static final DeferredItem<BlockItem> CARE_PACKAGE =
            ITEMS.register("carepackage", () -> new BlockItem(ModBlocks.CARE_PACKAGE.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> MARKET_BLOCK =
            ITEMS.register("market_block", () -> new TooltipBlockItem(ModBlocks.MARKET.get(), new Item.Properties(),
                    "tooltip.adminsfactions.market_block"));
    public static final DeferredItem<BlockItem> CURRENCY_EXCHANGE_BLOCK =
            ITEMS.register("currency_exchange_block", () -> new TooltipBlockItem(ModBlocks.CURRENCY_EXCHANGE.get(), new Item.Properties(),
                    "tooltip.adminsfactions.currency_exchange_block"));
    public static final DeferredItem<BlockItem> MONUMENT_CONTROLLER =
            ITEMS.register("monument_controller", () -> new BlockItem(ModBlocks.MONUMENT_CONTROLLER.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> MONUMENT_CRATE =
            ITEMS.register("monument_crate", () -> new BlockItem(ModBlocks.MONUMENT_CRATE.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> ORE_GENERATOR =
            ITEMS.register("ore_generator", () -> new BlockItem(ModBlocks.ORE_GENERATOR.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> POWER_METER =
            ITEMS.register("power_meter", () -> new TooltipBlockItem(ModBlocks.POWER_METER.get(), new Item.Properties(),
                    "tooltip.adminsfactions.power_meter"));
    public static final DeferredItem<com.admin82.factions.item.SeamViewerItem> SEAM_VIEWER =
            ITEMS.register("seam_viewer", () -> new com.admin82.factions.item.SeamViewerItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<BlockItem> SEAM_EXTRACTOR =
            ITEMS.register("seam_extractor", () -> new TooltipBlockItem(ModBlocks.SEAM_EXTRACTOR.get(), new Item.Properties(),
                    "tooltip.adminsfactions.seam_extractor"));
    public static final DeferredItem<BlockItem> FLUID_METER =
            ITEMS.register("fluid_meter", () -> new TooltipBlockItem(ModBlocks.FLUID_METER.get(), new Item.Properties(),
                    "tooltip.adminsfactions.fluid_meter"));

    // Outpost
    public static final DeferredItem<OutpostItem> OUTPOST =
            ITEMS.register("outpost", () -> new OutpostItem(new Item.Properties().stacksTo(16)));

    // Warbonds: the currency for faction upgrades
    public static final DeferredItem<Item> WARBOND =
            ITEMS.register("warbond", () -> new Item(new Item.Properties().stacksTo(64)
                    .rarity(Rarity.UNCOMMON)
                    .component(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)) {
                @Override
                public void appendHoverText(ItemStack stack, TooltipContext context,
                                            List<Component> tooltip,
                                            TooltipFlag flag) {
                    tooltip.add(Component.literal("§7Pays for faction upgrades."));
                }
            });
}
