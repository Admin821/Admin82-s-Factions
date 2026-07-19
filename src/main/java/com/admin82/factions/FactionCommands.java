package com.admin82.factions;

import com.admin82.factions.blockentity.FactionTableBlockEntity;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.ExchangeManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.menu.CurrencyExchangeMenu;
import com.admin82.factions.network.packet.SyncFactionDataPacket;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.VassalManager;
import com.mojang.brigadier.arguments.LongArgumentType;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@EventBusSubscriber(modid = AdminsFactions.MODID)
public class FactionCommands {

    private static final Map<UUID, Long> factionReturnCooldowns = new HashMap<>();

    // ── Suggestion providers ──────────────────────────────────────────────────

    /** Suggests all existing faction names. */
    private static final SuggestionProvider<CommandSourceStack> FACTION_NAMES =
            (ctx, builder) -> {
                FactionManager.get(ctx.getSource().getServer())
                        .getAllFactions().values()
                        .forEach(f -> builder.suggest(f.getName()));
                return builder.buildFuture();
            };

    /** Suggests "none" plus all existing faction names. */
    private static final SuggestionProvider<CommandSourceStack> FACTION_NAMES_OR_NONE =
            (ctx, builder) -> {
                builder.suggest("none");
                FactionManager.get(ctx.getSource().getServer())
                        .getAllFactions().values()
                        .forEach(f -> builder.suggest(f.getName()));
                return builder.buildFuture();
            };

    /** Suggests names of currently online players. */
    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYER_NAMES =
            (ctx, builder) -> {
                ctx.getSource().getServer().getPlayerList().getPlayers()
                        .forEach(p -> builder.suggest(p.getGameProfile().getName()));
                return builder.buildFuture();
            };

    // ── Command registration ──────────────────────────────────────────────────

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("faction")
                // /faction list
                .then(Commands.literal("list")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> cmdList(ctx.getSource())))

                // /faction info <name>
                .then(Commands.literal("info")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(FACTION_NAMES)
                        .executes(ctx -> cmdInfo(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))))

                // /faction join <name>
                .then(Commands.literal("join")
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(FACTION_NAMES)
                        .executes(ctx -> cmdJoin(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))))

                // /faction delete <name>
                .then(Commands.literal("delete")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(FACTION_NAMES)
                        .executes(ctx -> cmdDelete(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))))

                // /faction set <player> <faction|none>
                .then(Commands.literal("set")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYER_NAMES)
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_NAMES_OR_NONE)
                            .executes(ctx -> cmdSet(
                                    ctx.getSource(),
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "faction"))))))

                // /faction add <player> <faction>
                .then(Commands.literal("add")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYER_NAMES)
                        .then(Commands.argument("faction", StringArgumentType.word())
                            .suggests(FACTION_NAMES)
                            .executes(ctx -> cmdAdd(
                                    ctx.getSource(),
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "faction"))))))

                // /faction exchange set             ← no-arg: opens GUI
                // /faction exchange set <item> <rate>  ← console/command-block
                .then(Commands.literal("exchange")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("set")
                        .executes(ctx -> cmdExchangeOpenGui(ctx.getSource()))
                        .then(Commands.argument("item", StringArgumentType.word())
                            .then(Commands.argument("rate", StringArgumentType.word())
                                .executes(ctx -> cmdExchangeSet(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"),
                                        StringArgumentType.getString(ctx, "rate"))))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("item", StringArgumentType.word())
                            .executes(ctx -> cmdExchangeRemove(
                                    ctx.getSource(),
                                    StringArgumentType.getString(ctx, "item")))))
                    .then(Commands.literal("list")
                        .executes(ctx -> cmdExchangeList(ctx.getSource()))))

                    // /faction war dotp <true|false>
                    .then(Commands.literal("dotp")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> cmdWarDoTp(ctx.getSource(),
                                    BoolArgumentType.getBool(ctx, "enabled")))))
                    // /faction war doboundary <true|false>
                    .then(Commands.literal("doboundary")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> cmdWarDoBoundary(ctx.getSource(),
                                    BoolArgumentType.getBool(ctx, "enabled")))))
                    // /faction war graceperiod set <seconds>
                .then(Commands.literal("war")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("graceperiod")
                        .then(Commands.literal("set")
                            .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                                .executes(ctx -> cmdWarGraceperiodSet(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "seconds"))))))
                    // /faction war settpdistance <chunks>
                    .then(Commands.literal("settpdistance")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> cmdWarSetTpDistance(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "chunks")))))
                    // /faction war blockbreaklimit <count>
                    .then(Commands.literal("blockbreaklimit")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 1000))
                            .executes(ctx -> cmdWarBlockBreakLimit(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "limit")))))
                    // /faction war AfterWarCooldownTime <seconds>
                    .then(Commands.literal("AfterWarCooldownTime")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 604800))
                            .executes(ctx -> cmdWarAfterWarCooldown(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "seconds")))))
                    // /faction war PercentageOfOnlinePlayersForWar <0-100>
                    .then(Commands.literal("PercentageOfOnlinePlayersForWar")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                            .executes(ctx -> cmdWarOnlinePercentage(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "percent")))))
                    // /faction war FactionTableCaptureKothTime <seconds>
                    .then(Commands.literal("FactionTableCaptureKothTime")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                            .executes(ctx -> cmdWarTableKothTime(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "seconds")))))
                    // /faction war OutpostKothCaptureTime <seconds>
                    .then(Commands.literal("OutpostKothCaptureTime")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                            .executes(ctx -> cmdWarOutpostKothTime(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "seconds")))))
                    // /faction war vassals edit <faction> free
                    // /faction war vassals edit <faction> tax <amount>
                    .then(Commands.literal("vassals")
                        .then(Commands.literal("edit")
                            .requires(src -> src.hasPermission(2))
                            .then(Commands.argument("faction", StringArgumentType.string())
                                .then(Commands.literal("free")
                                    .executes(ctx -> cmdVassalFree(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "faction"))))
                                .then(Commands.literal("tax")
                                    .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> cmdVassalTax(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "faction"),
                                                LongArgumentType.getLong(ctx, "amount")))))))))

                // /faction economy claimrates <multiplier>
                .then(Commands.literal("economy")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("claimrates")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(1.0, 10.0))
                            .executes(ctx -> cmdEconomyClaimRates(
                                    ctx.getSource(),
                                    DoubleArgumentType.getDouble(ctx, "value")))))
                    .then(Commands.literal("outpostramp")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 5.0))
                            .executes(ctx -> cmdEconomyOutpostRamp(
                                    ctx.getSource(),
                                    DoubleArgumentType.getDouble(ctx, "value")))))
                    .then(Commands.literal("TpCostToOutpost")
                        .then(Commands.argument("silver", IntegerArgumentType.integer(0, 10000))
                            .executes(ctx -> cmdEconomyTpCostToOutpost(
                                    ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "silver"))))))

                // /faction ReturnCooldownTime <seconds>
                .then(Commands.literal("ReturnCooldownTime")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                        .executes(ctx -> cmdReturnCooldownTime(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "seconds")))))

                // /faction ReturnCombatTime <seconds>
                .then(Commands.literal("ReturnCombatTime")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                        .executes(ctx -> cmdReturnCombatTime(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "seconds")))))
        );
        // /factionreturn — teleports any faction member to their barracks (no op needed)
        event.getDispatcher().register(
                Commands.literal("factionreturn")
                        .executes(ctx -> cmdReturnToBase(ctx.getSource())));
    }

    // ── /faction list ─────────────────────────────────────────────────────────

    private static int cmdList(CommandSourceStack src) {
        FactionManager manager = FactionManager.get(src.getServer());
        var factions = manager.getAllFactions();
        if (factions.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7No factions exist yet."), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§6§l── Factions (" + factions.size() + ") ──"), false);
        for (Faction f : factions.values()) {
            src.sendSuccess(() -> Component.literal(
                    "§e" + f.getName()
                    + " §7[" + f.getMembers().size() + " members"
                    + ", " + f.getLandClaims().size() + " chunks]"), false);
        }
        return factions.size();
    }

    // ── /faction info <name> ──────────────────────────────────────────────────

    private static int cmdInfo(CommandSourceStack src, String name) {
        FactionManager manager = FactionManager.get(src.getServer());
        Faction faction = manager.getFactionByName(name);
        if (faction == null) {
            src.sendFailure(Component.literal("§cFaction '§e" + name + "§c' not found."));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§6§l" + faction.getName()), false);
        if (!faction.getDescription().isBlank())
            src.sendSuccess(() -> Component.literal("§7" + faction.getDescription()), false);
        src.sendSuccess(() -> Component.literal(
                "§fMembers: §e" + faction.getMembers().size()
                + "  §fChunks: §e" + faction.getLandClaims().size()), false);
        for (FactionMember m : faction.getMembers()) {
            src.sendSuccess(() -> Component.literal(
                    "§8  [§7" + m.getRole().getId() + "§8] §f" + m.getPlayerName()), false);
        }
        return 1;
    }

    // ── /faction join <name> ─────────────────────────────────────────────────

    private static int cmdJoin(CommandSourceStack src, String factionName) {
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("§cOnly players can join factions."));
            return 0;
        }

        FactionManager manager = FactionManager.get(src.getServer());
        if (manager.getPlayerFactionId(player.getUUID()) != null) {
            src.sendFailure(Component.literal("§cYou are already in a faction."));
            return 0;
        }

        Faction faction = manager.getFactionByName(factionName);
        if (faction == null) {
            src.sendFailure(Component.literal("§cFaction '§e" + factionName + "§c' not found."));
            return 0;
        }

        if (!manager.consumeInvite(player.getUUID(), faction.getId())) {
            src.sendFailure(Component.literal("§cYou do not have an invite to join '§e" + faction.getName() + "§c'."));
            return 0;
        }

        if (!manager.addPlayerToFaction(faction.getId(), player.getUUID(), player.getGameProfile().getName())) {
            src.sendFailure(Component.literal("§cCould not join '§e" + faction.getName() + "§c'."));
            return 0;
        }

        PacketDistributor.sendToPlayer(player, new SyncFactionDataPacket(manager.getFactionForPlayer(player.getUUID())));
        src.sendSuccess(() -> Component.literal("§aJoined faction '§e" + faction.getName() + "§a'."), false);
        for (FactionMember member : faction.getMembers()) {
            ServerPlayer online = src.getServer().getPlayerList().getPlayer(member.getUuid());
            if (online != null) {
                PacketDistributor.sendToPlayer(online, new SyncFactionDataPacket(faction));
                if (!online.getUUID().equals(player.getUUID())) {
                    online.displayClientMessage(Component.literal(
                            "§e" + player.getGameProfile().getName() + " §ajoined your faction."), false);
                }
            }
        }
        return 1;
    }

    // ── /faction delete <name> ────────────────────────────────────────────────

    private static int cmdDelete(CommandSourceStack src, String name) {
        FactionManager manager = FactionManager.get(src.getServer());
        Faction faction = manager.getFactionByName(name);
        if (faction == null) {
            src.sendFailure(Component.literal("§cFaction '§e" + name + "§c' not found."));
            return 0;
        }
        String factionName = faction.getName();
        performDisband(src.getServer(), faction.getId(),
                Component.literal("§cFaction '§e" + factionName + "§c' was deleted by an admin."));
        src.sendSuccess(() -> Component.literal("§aFaction '§e" + factionName + "§a' has been deleted."), true);
        return 1;
    }

    // ── /faction set <player> <faction|none> ──────────────────────────────────

    private static int cmdSet(CommandSourceStack src, String playerName, String factionName) {
        MinecraftServer server = src.getServer();
        FactionManager manager = FactionManager.get(server);

        Optional<UUID> uuidOpt = findPlayerUUID(server, playerName);
        if (uuidOpt.isEmpty()) {
            src.sendFailure(Component.literal("§cPlayer '§e" + playerName + "§c' not found (must have joined at least once)."));
            return 0;
        }
        UUID playerUUID = uuidOpt.get();

        // If the player is already in a faction, remove them first (unless they're the owner)
        UUID currentFactionId = manager.getPlayerFactionId(playerUUID);
        if (currentFactionId != null) {
            Faction current = manager.getFaction(currentFactionId);
            if (current != null && current.getOwnerId().equals(playerUUID)) {
                src.sendFailure(Component.literal(
                        "§c§e" + playerName + " §cis the owner of '§e" + current.getName()
                        + "§c'. Use §f/faction delete " + current.getName() + " §cfirst."));
                return 0;
            }
            manager.removePlayerFromFaction(playerUUID);
            syncPlayer(server, playerUUID, null);
        }

        if (factionName.equalsIgnoreCase("none")) {
            src.sendSuccess(() -> Component.literal("§aRemoved §e" + playerName + " §afrom their faction."), true);
            return 1;
        }

        Faction target = manager.getFactionByName(factionName);
        if (target == null) {
            src.sendFailure(Component.literal("§cFaction '§e" + factionName + "§c' not found."));
            return 0;
        }

        String resolvedName = resolvePlayerName(server, playerName, playerUUID);
        manager.addPlayerToFaction(target.getId(), playerUUID, resolvedName);
        syncPlayer(server, playerUUID,
                Component.literal("§aYou were placed in faction '§e" + target.getName() + "§a' by an admin."));
        src.sendSuccess(() -> Component.literal(
                "§aSet §e" + playerName + " §ato faction '§e" + target.getName() + "§a'."), true);
        return 1;
    }

    // ── /faction add <player> <faction> ──────────────────────────────────────

    private static int cmdAdd(CommandSourceStack src, String playerName, String factionName) {
        MinecraftServer server = src.getServer();
        FactionManager manager = FactionManager.get(server);

        Optional<UUID> uuidOpt = findPlayerUUID(server, playerName);
        if (uuidOpt.isEmpty()) {
            src.sendFailure(Component.literal("§cPlayer '§e" + playerName + "§c' not found (must have joined at least once)."));
            return 0;
        }
        UUID playerUUID = uuidOpt.get();

        if (manager.getPlayerFactionId(playerUUID) != null) {
            src.sendFailure(Component.literal(
                    "§c§e" + playerName + " §cis already in a faction. Use §f/faction set §cto move them."));
            return 0;
        }

        Faction target = manager.getFactionByName(factionName);
        if (target == null) {
            src.sendFailure(Component.literal("§cFaction '§e" + factionName + "§c' not found."));
            return 0;
        }

        String resolvedName = resolvePlayerName(server, playerName, playerUUID);
        manager.addPlayerToFaction(target.getId(), playerUUID, resolvedName);
        syncPlayer(server, playerUUID,
                Component.literal("§aYou were added to faction '§e" + target.getName() + "§a' by an admin."));
        src.sendSuccess(() -> Component.literal(
                "§aAdded §e" + playerName + " §ato faction '§e" + target.getName() + "§a'."), true);
        return 1;
    }

    // ── /faction exchange ─────────────────────────────────────────────────────

    private static int cmdExchangeOpenGui(CommandSourceStack src) {
        if (!(src.getEntity() instanceof ServerPlayer sp)) {
            src.sendFailure(Component.literal("§cOnly players can open the exchange GUI. Use: /faction exchange set <item> <rate>"));
            return 0;
        }
        if (!sp.hasPermissions(2)) {
            src.sendFailure(Component.literal("§cOperator permission required."));
            return 0;
        }
        var server = sp.getServer(); if (server == null) return 0;
        var rates = ExchangeManager.get(server).getRates();
        sp.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new CurrencyExchangeMenu(id, inv, BlockPos.ZERO),
                Component.literal("Manage Exchange Rates")),
                buf -> {
                    buf.writeBlockPos(BlockPos.ZERO);
                    buf.writeBoolean(true);
                    buf.writeVarInt(rates.size());
                    for (var e : rates.entrySet()) { buf.writeUtf(e.getKey()); buf.writeLong(e.getValue()); }
                    buf.writeVarInt(0); // LDLib2 UISyncManager initial pack: 0 sync values
                });
        return 1;
    }

    private static int cmdExchangeSet(CommandSourceStack src, String itemId, String rateStr) {
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

    private static int cmdExchangeRemove(CommandSourceStack src, String itemId) {
        ExchangeManager mgr = ExchangeManager.get(src.getServer());
        if (!mgr.hasRate(itemId)) {
            src.sendFailure(Component.literal("§cNo rate found for '§e" + itemId + "§c'."));
            return 0;
        }
        mgr.removeRate(itemId);
        src.sendSuccess(() -> Component.literal("§aRemoved exchange rate for §e" + itemId + "§a."), true);
        return 1;
    }

    private static int cmdExchangeList(CommandSourceStack src) {
        var rates = ExchangeManager.get(src.getServer()).getRates();
        if (rates.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7No exchange rates configured."), false);
            return 0;
        }
        src.sendSuccess(() -> Component.literal("§6§l── Exchange Rates ──"), false);
        rates.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> src.sendSuccess(() -> Component.literal(
                        "§e" + e.getKey() + " §7→ §a" + Currency.format(e.getValue()) + " §7each"), false));
        return rates.size();
    }
    private static int cmdWarSetTpDistance(CommandSourceStack src, int chunks) {
        WarManager.get(src.getServer()).setTpDistanceChunks(chunks);
        src.sendSuccess(() -> Component.literal("§aWar TP spawn distance set to §e" + chunks
                + " chunk" + (chunks == 1 ? "" : "s") + "§a from defender territory."), true);
        return chunks;
    }

    private static int cmdWarDoTp(CommandSourceStack src, boolean enabled) {
        WarManager.get(src.getServer()).setWarTpEnabled(enabled);
        src.sendSuccess(() -> Component.literal("§aAttacker teleport on grace-end is now §e"
                + (enabled ? "enabled" : "disabled") + "§a."), true);
        return 1;
    }

    private static int cmdWarDoBoundary(CommandSourceStack src, boolean enabled) {
        WarManager.get(src.getServer()).setWarBoundaryEnabled(enabled);
        src.sendSuccess(() -> Component.literal("§aWar boundary enforcement is now §e"
                + (enabled ? "enabled" : "disabled") + "§a."), true);
        return 1;
    }
    // ── /faction war ─────────────────────────────────────────────────────────

    private static int cmdWarGraceperiodSet(CommandSourceStack src, int seconds) {
        if (!src.hasPermission(2)) {
            src.sendFailure(Component.literal("§cYou need operator permission to change the grace period."));
            return 0;
        }
        WarManager.get(src.getServer()).setGracePeriodSeconds(seconds);
        src.sendSuccess(() -> Component.literal("§aWar grace period set to §e" + seconds + "s§a. "
                + "(Config default: §7" + Config.WAR_GRACE_PERIOD_SECONDS.get() + "s§a.)"), true);
        return seconds;
    }

    private static int cmdWarAfterWarCooldown(CommandSourceStack src, int seconds) {
        WarManager.get(src.getServer()).setAfterWarCooldownSeconds(seconds);
        long hrs = seconds / 3600, mins = (seconds % 3600) / 60;
        String fmt = hrs > 0 ? hrs + "h " + mins + "m" : mins + "m " + (seconds % 60) + "s";
        src.sendSuccess(() -> Component.literal("§aPost-war re-declaration cooldown set to §e" + seconds
                + "s §8(" + fmt + ")§a."), true);
        return seconds;
    }

    private static int cmdWarOnlinePercentage(CommandSourceStack src, int percent) {
        WarManager.get(src.getServer()).setMinOnlinePercentageForWar(percent);
        src.sendSuccess(() -> Component.literal("§aMinimum online % for war declaration set to §e"
                + percent + "%§a."), true);
        return percent;
    }

    private static int cmdWarTableKothTime(CommandSourceStack src, int seconds) {
        WarManager warmgr = WarManager.get(src.getServer());
        warmgr.setTableKothTime(seconds);
        src.getServer().overworld().getDataStorage().save();
        src.sendSuccess(() -> Component.literal("§aFaction table KOTH capture time set to §e"
                + seconds + "s§a."), true);
        return seconds;
    }

    private static int cmdWarOutpostKothTime(CommandSourceStack src, int seconds) {
        WarManager warmgr = WarManager.get(src.getServer());
        warmgr.setOutpostKothTime(seconds);
        src.getServer().overworld().getDataStorage().save();
        src.sendSuccess(() -> Component.literal("§aOutpost KOTH capture time set to §e"
                + seconds + "s§a."), true);
        return seconds;
    }

    // ── /faction war vassals edit ─────────────────────────────────────────────

    private static int cmdVassalFree(CommandSourceStack src, String factionName) {
        FactionManager fmgr = FactionManager.get(src.getServer());
        Faction faction = fmgr.getFactionByName(factionName);
        if (faction == null) {
            src.sendFailure(Component.literal("§cFaction not found: §e" + factionName));
            return 0;
        }
        VassalManager vmgr = VassalManager.get(src.getServer());
        if (!vmgr.isVassal(faction.getId())) {
            src.sendFailure(Component.literal("§c" + factionName + " §cis not a vassal."));
            return 0;
        }
        vmgr.freeVassal(faction.getId());
        src.sendSuccess(() -> Component.literal("§a" + factionName + " §ahas been freed from vassalage."), true);
        // Notify faction members
        for (FactionMember m : faction.getMembers()) {
            ServerPlayer sp = src.getServer().getPlayerList().getPlayer(m.getUuid());
            if (sp != null) sp.displayClientMessage(
                    Component.literal("§a[Admin] Your faction has been granted independence by an admin."), false);
        }
        return 1;
    }

    private static int cmdVassalTax(CommandSourceStack src, String factionName, long amount) {
        FactionManager fmgr = FactionManager.get(src.getServer());
        Faction faction = fmgr.getFactionByName(factionName);
        if (faction == null) {
            src.sendFailure(Component.literal("§cFaction not found: §e" + factionName));
            return 0;
        }
        VassalManager vmgr = VassalManager.get(src.getServer());
        if (!vmgr.isVassal(faction.getId())) {
            src.sendFailure(Component.literal("§c" + factionName + " §cis not a vassal."));
            return 0;
        }
        vmgr.accumulateTax(faction.getId(), amount);
        src.sendSuccess(() -> Component.literal("§aAdded §e" + Currency.format(amount)
                + " §ato pending tax for §e" + factionName + "§a."), true);
        return 1;
    }

    // ── Shared utilities ──────────────────────────────────────────────────────

    /**
     * Disbands a faction: unlinks its table block entity, notifies all online members,
     * then removes the faction from persistent storage.
     * Called by the /faction delete command and by the block-break event handler.
     */
    public static void performDisband(MinecraftServer server, UUID factionId, @Nullable Component reason) {
        FactionManager manager = FactionManager.get(server);
        Faction faction = manager.getFaction(factionId);
        if (faction == null) return;

        Component msg = reason != null ? reason
                : Component.literal("§cFaction '§e" + faction.getName() + "§c' has been disbanded.");

        // Remove the faction table block from the world
        FactionManager.TableLocation table = manager.getFactionTable(factionId);
        if (table != null) {
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(table.dimension()));
            ServerLevel tableLevel = server.getLevel(dimKey);
            if (tableLevel != null) {
                // Force-load the chunk so removeBlock actually reaches the block entity
                tableLevel.getChunkSource().getChunk(
                        net.minecraft.core.SectionPos.blockToSectionCoord(table.pos().getX()),
                        net.minecraft.core.SectionPos.blockToSectionCoord(table.pos().getZ()), true);
                tableLevel.removeBlock(table.pos(), false);
            }
        }

        // Remove the faction barracks block from the world
        FactionManager.TableLocation barracks = manager.getFactionBarracks(factionId);
        if (barracks != null) {
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(barracks.dimension()));
            ServerLevel barrLevel = server.getLevel(dimKey);
            if (barrLevel != null) {
                barrLevel.getChunkSource().getChunk(
                        net.minecraft.core.SectionPos.blockToSectionCoord(barracks.pos().getX()),
                        net.minecraft.core.SectionPos.blockToSectionCoord(barracks.pos().getZ()), true);
                barrLevel.removeBlock(barracks.pos(), false);
            }
            manager.removeFactionBarracks(factionId);
        }

        // Remove all outposts belonging to this faction
        OutpostData outpostData = OutpostData.get(server);
        for (OutpostEntry outpost : new java.util.ArrayList<>(outpostData.getOutpostsForFaction(factionId))) {
            // Find the level
            net.minecraft.server.level.ServerLevel outpostLevel = null;
            for (net.minecraft.server.level.ServerLevel lvl : server.getAllLevels()) {
                if (lvl.dimension().location().toString().equals(outpost.dimension)) {
                    outpostLevel = lvl; break;
                }
            }
            if (outpostLevel != null) {
                for (net.minecraft.core.BlockPos bp : new java.util.ArrayList<>(outpost.structureBlocks))
                    outpostLevel.removeBlock(bp, false);
                outpostLevel.removeBlock(outpost.managerPos, false);
            }
            // Unclaim the outpost's chunk
            manager.unclaimChunk(factionId,
                    net.minecraft.core.SectionPos.blockToSectionCoord(outpost.managerPos.getX()),
                    net.minecraft.core.SectionPos.blockToSectionCoord(outpost.managerPos.getZ()),
                    outpost.dimension);
            outpostData.removeOutpost(outpost.id);
        }

        // Notify and desync all online members
        for (FactionMember member : faction.getMembers()) {
            ServerPlayer mp = server.getPlayerList().getPlayer(member.getUuid());
            if (mp != null) {
                PacketDistributor.sendToPlayer(mp, new SyncFactionDataPacket(null));
                mp.displayClientMessage(msg, false);
            }
        }

        manager.disbandFaction(factionId);
    }

    /** Sends an updated faction sync packet (and optional message) to an online player. */
    private static void syncPlayer(MinecraftServer server, UUID uuid, @Nullable Component message) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online == null) return;
        FactionManager manager = FactionManager.get(server);
        PacketDistributor.sendToPlayer(online,
                new SyncFactionDataPacket(manager.getFactionForPlayer(uuid)));
        if (message != null) online.displayClientMessage(message, false);
    }

    /**
     * Resolves a player UUID from a name.
     * Checks online players first, then existing faction member records,
     * then the server profile cache (for offline players who have joined before).
     */
    private static Optional<UUID> findPlayerUUID(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return Optional.of(online.getUUID());

        FactionManager manager = FactionManager.get(server);
        for (Faction f : manager.getAllFactions().values()) {
            for (FactionMember m : f.getMembers()) {
                if (m.getPlayerName().equalsIgnoreCase(name)) return Optional.of(m.getUuid());
            }
        }

        return server.getProfileCache().get(name).map(GameProfile::getId);
    }

    /** Resolves the display name of a player by UUID, falling back to the provided string. */
    private static String resolvePlayerName(MinecraftServer server, String fallback, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) return online.getGameProfile().getName();
        return server.getProfileCache().get(uuid)
                .map(GameProfile::getName).orElse(fallback);
    }

    private static int cmdWarBlockBreakLimit(CommandSourceStack src, int limit) {
        com.admin82.factions.war.WarManager.get(src.getServer()).setBlockBreakLimit(limit);
        src.sendSuccess(() -> Component.literal(
                "§aResource-War block-break limit set to §e" + limit + "§a blocks."), true);
        return limit;
    }

    // ── /faction economy claimrates <value> ──────────────────────────────────

    private static int cmdEconomyClaimRates(CommandSourceStack src, double rate) {
        EconomyManager.get(src.getServer()).setClaimRateMultiplier(rate);
        src.sendSuccess(() -> Component.literal(
                "§aClaim deed cost rate set to §e" + rate
                + "§a. Each additional deed costs base × " + rate + "^n."), true);
        return 1;
    }

    // ── /faction economy outpostramp <value> ───────────────────────────────────

    private static int cmdEconomyOutpostRamp(CommandSourceStack src, double ramp) {
        EconomyManager.get(src.getServer()).setOutpostDistanceRamp(ramp);
        src.sendSuccess(() -> Component.literal(
                "§aOutpost distance ramp set to §e" + ramp
                + "§a per 5-chunk band (e.g. 10 chunks away = +" + (int)(2 * ramp * 100) + "%)." ), true);
        return 1;
    }

    private static int cmdEconomyTpCostToOutpost(CommandSourceStack src, int silver) {
        long copper = (long) silver * com.admin82.factions.economy.Currency.COPPER_PER_SILVER;
        EconomyManager.get(src.getServer()).setTpCostToOutpost(copper);
        src.sendSuccess(() -> Component.literal("§aOutpost teleport cost set to §e" + silver + " silver§a."), true);
        return silver;
    }

    private static int cmdReturnCooldownTime(CommandSourceStack src, int seconds) {
        FactionManager.get(src.getServer()).setFactionReturnCooldownSeconds(seconds);
        factionReturnCooldowns.clear();
        int cooldownSeconds = FactionManager.get(src.getServer()).getFactionReturnCooldownSeconds();
        src.sendSuccess(() -> Component.literal("§aFaction return cooldown set to §e"
            + cooldownSeconds + "s§a."), true);
        return cooldownSeconds;
    }

    private static int cmdReturnCombatTime(CommandSourceStack src, int seconds) {
        FactionManager.get(src.getServer()).setFactionReturnCombatSeconds(seconds);
        FactionCombatEvents.clearReturnCombatLocks();
        int combatSeconds = FactionManager.get(src.getServer()).getFactionReturnCombatSeconds();
        src.sendSuccess(() -> Component.literal("§aFaction return combat lock set to §e"
                + combatSeconds + "s§a."), true);
        return combatSeconds;
    }

    private static int cmdReturnToBase(CommandSourceStack src) {
        if (!(src.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) {
            src.sendFailure(Component.literal("§cThis command can only be used by players."));
            return 0;
        }
        FactionManager fmgr = FactionManager.get(src.getServer());
        Faction faction = fmgr.getFactionForPlayer(player.getUUID());
        if (faction == null) { src.sendFailure(Component.literal("§cYou are not in a faction.")); return 0; }
        FactionManager.TableLocation barracks = fmgr.getFactionBarracks(faction.getId());
        if (barracks == null) { src.sendFailure(Component.literal("§cYour faction has no barracks.")); return 0; }
        long combatSecondsLeft = FactionCombatEvents.getReturnCombatRemainingSeconds(player);
        if (combatSecondsLeft > 0) {
            src.sendFailure(Component.literal("§cYou cannot use §f/factionreturn §cwhile in player combat. Wait §e"
                + combatSecondsLeft + "s§c."));
            return 0;
        }
        long now = System.currentTimeMillis();
        int cooldownSeconds = fmgr.getFactionReturnCooldownSeconds();
        Long cooldownUntil = factionReturnCooldowns.get(player.getUUID());
        if (cooldownSeconds > 0 && cooldownUntil != null && now < cooldownUntil) {
            long secondsLeft = Math.max(1L, (cooldownUntil - now + 999L) / 1000L);
            src.sendFailure(Component.literal("§cYou can use §f/factionreturn §cagain in §e"
                    + secondsLeft + "s§c."));
            return 0;
        }
        net.minecraft.server.level.ServerLevel targetLevel = null;
        for (net.minecraft.server.level.ServerLevel lvl : src.getServer().getAllLevels()) {
            if (lvl.dimension().location().toString().equals(barracks.dimension())) { targetLevel = lvl; break; }
        }
        if (targetLevel == null) return 0;
        net.minecraft.core.BlockPos bp = barracks.pos();
        int spawnX = bp.getX(), spawnZ = bp.getZ() + 2;
        int spawnY = targetLevel.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawnX, spawnZ);
        final net.minecraft.server.level.ServerLevel fl = targetLevel;
        player.teleportTo(fl, spawnX + 0.5, spawnY, spawnZ + 0.5, player.getYRot(), player.getXRot());
        if (cooldownSeconds > 0) {
            factionReturnCooldowns.put(player.getUUID(), now + (long) cooldownSeconds * 1000L);
        } else {
            factionReturnCooldowns.remove(player.getUUID());
        }
        src.sendSuccess(() -> Component.literal("§a✔ Teleported to §e" + faction.getName() + " §aBarracks!"), false);
        return 1;
    }
}
