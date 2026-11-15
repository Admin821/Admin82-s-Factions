package com.admin82.factions.client;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.client.model.CarePackageModel;
import com.admin82.factions.client.model.CarePackageParachuteModel;
import com.admin82.factions.client.renderer.CarePackageBlockEntityRenderer;
import com.admin82.factions.client.renderer.CarePackageItemRenderer;
import com.admin82.factions.client.renderer.RoguePmcRenderer;
import com.admin82.factions.client.renderer.SupplyDropVisualRenderer;
import com.admin82.factions.registry.ModBlockEntities;
import com.admin82.factions.registry.ModEntities;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.screen.AdminPanelScreen;
import com.admin82.factions.screen.AiCombatProfileScreen;
import com.admin82.factions.screen.BarracksScreen;
import com.admin82.factions.screen.ChunkBorderRenderer;
import com.admin82.factions.screen.ContainerHighlightRenderer;
import com.admin82.factions.screen.CurrencyExchangeScreen;
import com.admin82.factions.screen.FactionTableScreen;
import com.admin82.factions.screen.FactionVaultScreen;
import com.admin82.factions.screen.MeterScreen;
import com.admin82.factions.screen.ItemVaultScreen;
import com.admin82.factions.screen.MarketScreen;
import com.admin82.factions.screen.MonumentScreen;
import com.admin82.factions.screen.SupplyDropScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
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
        NeoForge.EVENT_BUS.addListener(com.admin82.factions.client.PlacementPreview::onRenderLevel);
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
        event.register(ModMenuTypes.ADMIN_PANEL.get(), AdminPanelScreen::new);
        event.register(ModMenuTypes.ITEM_VAULT.get(), ItemVaultScreen::new);
        event.register(ModMenuTypes.FACTION_VAULT.get(), FactionVaultScreen::new);
        event.register(ModMenuTypes.METER.get(), MeterScreen::new);
        event.register(ModMenuTypes.SEAM_EXTRACTOR.get(), com.admin82.factions.screen.SeamExtractorScreen::new);
        event.register(ModMenuTypes.COIN_STORAGE.get(), com.admin82.factions.screen.CoinStorageScreen::new);
        event.register(ModMenuTypes.SUPPLY_DROP.get(), SupplyDropScreen::new);
        event.register(ModMenuTypes.MONUMENT.get(), MonumentScreen::new);
        event.register(ModMenuTypes.AI_COMBAT_PROFILES.get(), AiCombatProfileScreen::new);
    }

    @SubscribeEvent
    static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SUPPLY_DROP_VISUAL.get(), SupplyDropVisualRenderer::new);
        event.registerEntityRenderer(ModEntities.ROGUE_SNIPER.get(), RoguePmcRenderer::new);
        event.registerEntityRenderer(ModEntities.ROGUE_ASSAULT.get(), RoguePmcRenderer::new);
        event.registerEntityRenderer(ModEntities.ROGUE_HEAVY_GUNNER.get(), RoguePmcRenderer::new);
        event.registerEntityRenderer(ModEntities.ROGUE_LIGHT_GUNNER.get(), RoguePmcRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.CARE_PACKAGE.get(), CarePackageBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.METER.get(), com.admin82.factions.client.renderer.MeterFluidRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.OUTPOST_STORAGE.get(), com.admin82.factions.client.renderer.OutpostStorageRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SEAM_EXTRACTOR.get(), com.admin82.factions.client.renderer.SeamExtractorRenderer::new);
    }

    /** Models drawn by block entity renderers rather than by a block. */
    @SubscribeEvent
    static void onRegisterAdditionalModels(net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional event) {
        event.register(com.admin82.factions.client.renderer.SeamExtractorRenderer.RING);
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


