package com.admin82.factions.command;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.ExchangeManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.menu.AdminPanelMenu;
import com.admin82.factions.menu.AiCombatProfileMenu;
import com.admin82.factions.menu.CurrencyExchangeMenu;
import com.admin82.factions.menu.SupplyDropMenu;
import com.admin82.factions.menu.monument.MonumentMenu;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.monument.MonumentMoves;
import com.admin82.factions.monument.MonumentView;
import com.admin82.factions.network.packet.AdminPanelPacket;
import com.admin82.factions.network.packet.ExchangeActionPacket;
import com.admin82.factions.supplydrop.SupplyDropData;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.Comparator;
import java.util.Map;
import javax.annotation.Nullable;

import static com.admin82.factions.util.BypassManager.toggleBypass;

/** Operator commands: monuments, the admin panel, the AI editor, bypass, the exchange and supply drops. */
final class AdminCommands {

    private AdminCommands() {}

    static LiteralArgumentBuilder<CommandSourceStack> monumentCommand() {
        return Commands.literal("monument")
                .executes(ctx -> cmdMonumentList(ctx.getSource()))
                .then(Commands.literal("edit")
                        .requires(src -> src.hasPermission(2))
                    .executes(ctx -> cmdMonumentEdit(ctx.getSource())))
                .then(Commands.literal("move")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> cmdMonumentMove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))));
    }

    private static int cmdMonumentMove(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cOnly players can move monuments."));
            return 0;
        }
        MonumentEntry monument = MonumentData.get(source.getServer()).getByName(name);
        if (monument == null) {
            source.sendFailure(Component.literal("§cNo monument called " + name + "."));
            return 0;
        }
        MonumentMoves.start(player, monument);
        return 1;
    }

    static int cmdAdminPanel(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cOnly players can open the admin panel."));
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, menuPlayer) -> new AdminPanelMenu(id, inventory, menuPlayer),
                Component.literal("Admin Panel")),
                buffer -> buffer.writeVarInt(0)); // LDLib2 UISyncManager initial pack
        AdminPanelPacket.sendSync(player);
        return 1;
    }

    static int cmdAiEdit(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cOnly players can open the AI editor."));
            return 0;
        }
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, menuPlayer) -> new AiCombatProfileMenu(id, inventory, menuPlayer),
                Component.literal("AI Combat Profiles")),
                buffer -> {
                    AiCombatProfileMenu.writeOpenData(buffer, source.getServer());
                    buffer.writeVarInt(0);
                });
        return 1;
    }

    static int cmdToggleBypass(CommandSourceStack source) throws CommandSyntaxException {
        boolean enabled = toggleBypass(source.getPlayer().getUUID()) == 1;
        source.sendSuccess(() -> Component.literal(enabled
                ? "§aFaction protection bypass enabled."
                : "§eFaction protection bypass disabled."), false);
        return 1;
    }

            private static int cmdMonumentEdit(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("§cOnly players can open the monument editor."));
            return 0;
        }
        MonumentData data = MonumentData.get(src.getServer());
        var factionManager = FactionManager.get(src.getServer());
        var views = data.getAll().stream().map(entry ->
                MonumentView.from(entry, factionManager, false))
                .sorted(Comparator.comparing(MonumentView::name,
                        String.CASE_INSENSITIVE_ORDER)).toList();
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new MonumentMenu(id, inventory, views, null),
                Component.literal("Monument Administration")), buf -> {
            buf.writeVarInt(views.size());
            views.forEach(view -> view.write(buf));
            buf.writeBoolean(false);
            buf.writeVarInt(0); // LDLib2 UISyncManager initial pack: 0 sync values
        });
        return 1;
    }

    private static int cmdMonumentList(CommandSourceStack src) {
        var monuments = MonumentData.get(src.getServer()).getAll();
        if (monuments.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7No monuments have been created."), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§6§l── Monuments (" + monuments.size() + ") ──"), false);
        for (MonumentEntry monument : monuments) {
            long seconds = Math.max(0L, Math.round(monument.getRemainingRespawnTicks() / 20.0));
            boolean occupied = src.getServer().getPlayerList().getPlayers().stream().anyMatch(player ->
                    monument.contains(player.blockPosition(), player.level().dimension().location().toString()));
            String timer = occupied ? "§cPaused (occupied)" : "§a" + formatDuration(seconds);
            src.sendSuccess(() -> Component.literal("§e" + monument.getName() + " §7[Tier " + monument.getTier()
                    + "] §f" + monument.controllerPos.getX() + ", " + monument.controllerPos.getY() + ", "
                    + monument.controllerPos.getZ() + " §8(" + monument.dimension + ") §7Loot: " + timer), false);
        }
        return monuments.size();
    }

    private static String formatDuration(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long seconds = totalSeconds % 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    // /faction exchange

    static int cmdExchangeOpenGui(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer sp)) {
            src.sendFailure(Component.literal("§cOnly players can open the exchange GUI. Use: /faction exchange set <item> <rate>"));
            return 0;
        }
        if (!sp.hasPermissions(2)) {
            src.sendFailure(Component.literal("§cOperator permission required."));
            return 0;
        }
        var server = sp.getServer(); if (server == null) return 0;
        var exchange = ExchangeManager.get(server);
        var rates = exchange.getRates();
        sp.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new CurrencyExchangeMenu(id, inv, BlockPos.ZERO),
                Component.literal("Manage Exchange Rates")),
                buf -> {
                    buf.writeBlockPos(BlockPos.ZERO);
                    buf.writeBoolean(true);
                buf.writeBoolean(exchange.isBothWaysExchange());
                    buf.writeVarInt(rates.size());
                    for (var e : rates.entrySet()) { buf.writeUtf(e.getKey()); buf.writeLong(e.getValue()); }
                    buf.writeVarInt(0); // LDLib2 UISyncManager initial pack: 0 sync values
                });
        ExchangeActionPacket.sendSync(sp);
        return 1;
    }

    static int cmdExchangeSet(CommandSourceStack src, String itemId, String rateStr) {
        long rate = Currency.parse(rateStr);
        if (rate <= 0) {
            src.sendFailure(Component.literal("§cInvalid rate '§e" + rateStr + "§c'. Use e.g. §f100§c or §f1s§c."));
            return 0;
        }
        ExchangeManager.get(src.getServer()).setRate(itemId, rate);
        src.sendSuccess(() -> Component.literal(
                "§aSet exchange rate: §e" + itemId + " §a→ §e" + Currency.format(rate) + " §aper item."), true);
        return 1;
    }

    static int cmdExchangeRemove(CommandSourceStack src, String itemId) {
        ExchangeManager mgr = ExchangeManager.get(src.getServer());
        if (!mgr.hasRate(itemId)) {
            src.sendFailure(Component.literal("§cNo rate found for '§e" + itemId + "§c'."));
            return 0;
        }
        mgr.removeRate(itemId);
        src.sendSuccess(() -> Component.literal("§aRemoved exchange rate for §e" + itemId + "§a."), true);
        return 1;
    }

    static int cmdExchangeList(CommandSourceStack src) {
        var rates = ExchangeManager.get(src.getServer()).getRates();
        if (rates.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7No exchange rates configured."), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§6§l── Exchange Rates ──"), false);
        rates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> src.sendSuccess(() -> Component.literal(
                        "§e" + e.getKey() + " §7→ §a" + Currency.format(e.getValue()) + " §7each"), false));
        return rates.size();
    }

    static int cmdDoBothWaysExchange(CommandSourceStack src, @Nullable Boolean enabledArg) {
        ExchangeManager mgr = ExchangeManager.get(src.getServer());
        boolean enabled = enabledArg != null ? enabledArg : !mgr.isBothWaysExchange();
        mgr.setBothWaysExchange(enabled);
        src.sendSuccess(() -> Component.literal("§aTwo-way currency exchange is now §e"
                + (enabled ? "enabled" : "disabled") + "§a."), true);
        return enabled ? 1 : 0;
    }

    static int cmdSupplyDropOpen(CommandSourceStack src, SupplyDropMenu.Mode mode) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("§cOnly players can open the supply drop GUI."));
            return 0;
        }
        if (!player.hasPermissions(2)) {
            src.sendFailure(Component.literal("§cOperator permission required."));
            return 0;
        }
        var data = SupplyDropData.get(src.getServer());
        var names = data.getPoolNames();
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new SupplyDropMenu(id, inv, mode, names),
                Component.literal(mode == SupplyDropMenu.Mode.EDITOR ? "Supply Drop Loot Pools" : "Call Supply Drop")),
                buf -> {
                    buf.writeVarInt(mode.ordinal());
                    buf.writeVarInt(names.size());
                    for (String name : names) buf.writeUtf(name, 64);
                    buf.writeVarInt(0); // LDLib2 UISyncManager initial pack: 0 sync values
                });
        return 1;
    }
}
