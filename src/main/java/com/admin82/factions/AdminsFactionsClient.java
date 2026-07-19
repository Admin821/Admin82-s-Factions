package com.admin82.factions;

import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.screen.BarracksScreen;
import com.admin82.factions.screen.ChunkBorderRenderer;
import com.admin82.factions.screen.ContainerHighlightRenderer;
import com.admin82.factions.screen.CurrencyExchangeScreen;
import com.admin82.factions.screen.FactionTableScreen;
import com.admin82.factions.screen.MarketScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
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
    }
}


