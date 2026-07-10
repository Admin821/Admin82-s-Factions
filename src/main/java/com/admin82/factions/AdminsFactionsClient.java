package com.admin82.factions;

import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.screen.BarracksScreen;
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

@Mod(value = AdminsFactions.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = AdminsFactions.MODID, value = Dist.CLIENT)
public class AdminsFactionsClient {

    public AdminsFactionsClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
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


