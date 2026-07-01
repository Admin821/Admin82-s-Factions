package com.admin82.factions.registry;

import com.admin82.factions.menu.CurrencyExchangeMenu;
import com.admin82.factions.menu.FactionTableMenu;
import com.admin82.factions.menu.MarketMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.admin82.factions.AdminsFactions.MODID;

public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<FactionTableMenu>> FACTION_TABLE =
            MENUS.register("faction_table", () ->
                    IMenuTypeExtension.create((id, inv, buf) -> new FactionTableMenu(id, inv, buf))
            );

    public static final DeferredHolder<MenuType<?>, MenuType<MarketMenu>> MARKET =
            MENUS.register("market", () ->
                    IMenuTypeExtension.create((id, inv, buf) -> new MarketMenu(id, inv, buf))
            );

    public static final DeferredHolder<MenuType<?>, MenuType<CurrencyExchangeMenu>> CURRENCY_EXCHANGE =
            MENUS.register("currency_exchange", () ->
                    IMenuTypeExtension.create((id, inv, buf) -> new CurrencyExchangeMenu(id, inv, buf))
            );
}
