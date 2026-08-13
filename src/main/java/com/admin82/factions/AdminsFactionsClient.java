package com.admin82.factions;

import com.admin82.factions.client.model.CarePackageParachuteModel;
import com.admin82.factions.client.model.CarePackageModel;
import com.admin82.factions.client.renderer.CarePackageBlockEntityRenderer;
import com.admin82.factions.client.renderer.CarePackageItemRenderer;
import com.admin82.factions.client.renderer.SupplyDropVisualRenderer;
import com.admin82.factions.registry.ModBlockEntities;
import com.admin82.factions.registry.ModEntities;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.screen.BarracksScreen;
import com.admin82.factions.screen.ChunkBorderRenderer;
import com.admin82.factions.screen.ContainerHighlightRenderer;
import com.admin82.factions.screen.CurrencyExchangeScreen;
import com.admin82.factions.screen.FactionTableScreen;
import com.admin82.factions.screen.MarketScreen;
import com.admin82.factions.screen.MonumentScreen;
import com.admin82.factions.screen.SupplyDropScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = AdminsFactions.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AdminsFactions.MODID, value = Dist.CLIENT)
public class AdminsFactionsClient {

    public AdminsFactionsClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        // Register world-render event on the NeoForge (game) event bus for through-wall highlights
        NeoForge.EVENT_BUS.addListener(ContainerHighlightRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(ChunkBorderRenderer::onRenderLevel);
        // ResourceWarHudOverlay is registered automatically via @EventBusSubscriber
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        AdminsFactions.LOGGER.info("Admin's Factions — client setup complete.");
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.BARRACKS.get(), BarracksScreen::new);
        event.register(ModMenuTypes.FACTION_TABLE.get(), FactionTableScreen::new);
        event.register(ModMenuTypes.MARKET.get(), MarketScreen::new);
        event.register(ModMenuTypes.CURRENCY_EXCHANGE.get(), CurrencyExchangeScreen::new);
        event.register(ModMenuTypes.SUPPLY_DROP.get(), SupplyDropScreen::new);
        event.register(ModMenuTypes.MONUMENT.get(), MonumentScreen::new);
    }

    @SubscribeEvent
    static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SUPPLY_DROP_VISUAL.get(), SupplyDropVisualRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.CARE_PACKAGE.get(), CarePackageBlockEntityRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        CarePackageItemRenderer renderer = new CarePackageItemRenderer();
        event.registerItem(new IClientItemExtensions() {
            @Override
            public CarePackageItemRenderer getCustomRenderer() {
                return renderer;
            }
        }, ModItems.CARE_PACKAGE);
    }

    @SubscribeEvent
    static void onRegisterModelLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(CarePackageParachuteModel.LAYER, CarePackageParachuteModel::createLayer);
        event.registerLayerDefinition(CarePackageModel.CLOSED_LAYER, CarePackageModel::createClosedLayer);
        event.registerLayerDefinition(CarePackageModel.OPEN_LAYER, CarePackageModel::createOpenLayer);
    }
}


