package com.admin82.factions;

import com.admin82.factions.registry.*;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(AdminsFactions.MODID)
public class AdminsFactions {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "adminsfactions";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // Creative tab
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FACTIONS_TAB =
            CREATIVE_MODE_TABS.register("factions_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.adminsfactions"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> ModItems.FACTION_TABLE.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(ModItems.FACTION_TABLE.get());
                        output.accept(ModItems.MARKET_BLOCK.get());
                        output.accept(ModItems.CURRENCY_EXCHANGE_BLOCK.get());
                        output.accept(ModItems.COPPER_COIN.get());
                        output.accept(ModItems.SILVER_COIN.get());
                        output.accept(ModItems.GOLD_COIN.get());
                        output.accept(ModItems.PLATINUM_COIN.get());
                    })
                    .build());

    public AdminsFactions(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);

        // Register mod deferred registers
        ModBlocks.BLOCKS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModMenuTypes.MENUS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        // Register network packets
        modEventBus.addListener(ModPackets::register);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Admin's Factions — common setup complete.");
    }
}
