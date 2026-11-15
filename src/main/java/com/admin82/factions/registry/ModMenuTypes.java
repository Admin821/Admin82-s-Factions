package com.admin82.factions.registry;

import com.admin82.factions.menu.AdminPanelMenu;
import com.admin82.factions.menu.AiCombatProfileMenu;
import com.admin82.factions.menu.BarracksMenu;
import com.admin82.factions.menu.CurrencyExchangeMenu;
import com.admin82.factions.menu.FactionVaultMenu;
import com.admin82.factions.menu.ItemVaultMenu;
import com.admin82.factions.menu.MeterMenu;
import com.admin82.factions.menu.SupplyDropMenu;
import com.admin82.factions.menu.factiontable.FactionTableMenu;
import com.admin82.factions.menu.market.MarketMenu;
import com.admin82.factions.menu.monument.MonumentMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import static com.admin82.factions.AdminsFactions.MODID;

public class ModMenuTypes {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<BarracksMenu>> BARRACKS =
            MENUS.register("barracks", () ->
                    IMenuTypeExtension.create((id, inv, buf) -> new BarracksMenu(id, inv, buf))
            );

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

    public static final DeferredHolder<MenuType<?>, MenuType<SupplyDropMenu>> SUPPLY_DROP =
            MENUS.register("supply_drop", () ->
                    IMenuTypeExtension.create((id, inv, buf) -> new SupplyDropMenu(id, inv, buf))
            );

    public static final DeferredHolder<MenuType<?>, MenuType<AiCombatProfileMenu>> AI_COMBAT_PROFILES =
            MENUS.register("ai_combat_profiles", () ->
                    IMenuTypeExtension.create(AiCombatProfileMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ItemVaultMenu>> ITEM_VAULT =
            MENUS.register("item_vault", () ->
                    IMenuTypeExtension.create((id, inv, buf) -> new ItemVaultMenu(id, inv, buf)));

    public static final DeferredHolder<MenuType<?>, MenuType<FactionVaultMenu>> FACTION_VAULT =
            MENUS.register("faction_vault", () ->
                    IMenuTypeExtension.create((id, inv, buf) -> new FactionVaultMenu(id, inv, buf)));

    public static final DeferredHolder<MenuType<?>, MenuType<AdminPanelMenu>> ADMIN_PANEL =
            MENUS.register("admin_panel", () ->
                    IMenuTypeExtension.create((id, inv, buf) -> new AdminPanelMenu(id, inv, buf)));

        public static final DeferredHolder<MenuType<?>, MenuType<MonumentMenu>> MONUMENT =
                        MENUS.register("monument", () -> IMenuTypeExtension.create(MonumentMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.admin82.factions.menu.CoinStorageMenu>> COIN_STORAGE =
            MENUS.register("coin_storage", () -> IMenuTypeExtension.create(com.admin82.factions.menu.CoinStorageMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.admin82.factions.menu.SeamExtractorMenu>> SEAM_EXTRACTOR =
            MENUS.register("seam_extractor", () -> IMenuTypeExtension.create(com.admin82.factions.menu.SeamExtractorMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<MeterMenu>> METER =
            MENUS.register("meter", () -> IMenuTypeExtension.create((id, inv, buf) -> new MeterMenu(id, inv, buf)));
}
