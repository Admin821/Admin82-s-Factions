package com.admin82.factions.command;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.faction.*;
import com.admin82.factions.faction.FactionBlockProtection;
import com.admin82.factions.menu.SupplyDropMenu;
import com.admin82.factions.network.packet.SyncFactionDataPacket;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.outpost.OutpostService;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.storage.FactionItemVaults;
import com.admin82.factions.war.WarManager;
import com.admin82.factions.war.WarPhase;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;

import static com.admin82.factions.util.BypassManager.toggleBypass;

@EventBusSubscriber(modid = AdminsFactions.MODID)
public class FactionCommands {

    static final Map<UUID, Long> factionReturnCooldowns = new HashMap<>();

    // Suggestion providers

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

    // Command registration

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("faction")
                // /faction list
                .then(Commands.literal("list")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> MembershipCommands.cmdList(ctx.getSource())))

                // /faction info <name>
                .then(Commands.literal("info")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(FACTION_NAMES)
                        .executes(ctx -> MembershipCommands.cmdInfo(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))))

                // /faction join <name>
                .then(Commands.literal("join")
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(FACTION_NAMES)
                        .executes(ctx -> MembershipCommands.cmdJoin(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))))

                // /faction delete <name>
                .then(Commands.literal("delete")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("name", StringArgumentType.greedyString())
                        .suggests(FACTION_NAMES)
                        .executes(ctx -> MembershipCommands.cmdDelete(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "name")))))

                // /faction set <player> <faction|none>
                .then(Commands.literal("set")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYER_NAMES)
                        .then(Commands.argument("faction", StringArgumentType.greedyString())
                            .suggests(FACTION_NAMES_OR_NONE)
                            .executes(ctx -> MembershipCommands.cmdSet(
                                    ctx.getSource(),
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "faction"))))))

                // /faction add <player> <faction>
                .then(Commands.literal("add")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(ONLINE_PLAYER_NAMES)
                        .then(Commands.argument("faction", StringArgumentType.greedyString())
                            .suggests(FACTION_NAMES)
                            .executes(ctx -> MembershipCommands.cmdAdd(
                                    ctx.getSource(),
                                    StringArgumentType.getString(ctx, "player"),
                                    StringArgumentType.getString(ctx, "faction"))))))

                // /faction exchange set             ← no-arg: opens GUI
                // /faction exchange set <item> <rate>  ← console/command-block
                .then(Commands.literal("exchange")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("set")
                        .executes(ctx -> AdminCommands.cmdExchangeOpenGui(ctx.getSource()))
                        .then(Commands.argument("item", StringArgumentType.word())
                            .then(Commands.argument("rate", StringArgumentType.word())
                                .executes(ctx -> AdminCommands.cmdExchangeSet(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "item"),
                                        StringArgumentType.getString(ctx, "rate"))))))
                    .then(Commands.literal("remove")
                        .then(Commands.argument("item", StringArgumentType.word())
                            .executes(ctx -> AdminCommands.cmdExchangeRemove(
                                    ctx.getSource(),
                                    StringArgumentType.getString(ctx, "item")))))
                    .then(Commands.literal("list")
                        .executes(ctx -> AdminCommands.cmdExchangeList(ctx.getSource()))))

                // /faction dobothwaysexchange [true|false]
                .then(Commands.literal("dobothwaysexchange")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> AdminCommands.cmdDoBothWaysExchange(ctx.getSource(), null))
                    .then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(ctx -> AdminCommands.cmdDoBothWaysExchange(ctx.getSource(),
                                BoolArgumentType.getBool(ctx, "enabled")))))

                // /faction supplydrop loot|spawn
                .then(Commands.literal("supplydrop")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("loot")
                        .executes(ctx -> AdminCommands.cmdSupplyDropOpen(ctx.getSource(), SupplyDropMenu.Mode.EDITOR)))
                    .then(Commands.literal("spawn")
                        .executes(ctx -> AdminCommands.cmdSupplyDropOpen(ctx.getSource(), SupplyDropMenu.Mode.SPAWNER))))

                    // /faction war dotp <true|false>
                    .then(Commands.literal("dotp")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> WarCommands.cmdWarDoTp(ctx.getSource(),
                                    BoolArgumentType.getBool(ctx, "enabled")))))
                    // /faction war doboundary <true|false>
                    .then(Commands.literal("doboundary")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(ctx -> WarCommands.cmdWarDoBoundary(ctx.getSource(),
                                    BoolArgumentType.getBool(ctx, "enabled")))))
                    // /faction war graceperiod set <seconds>
                .then(Commands.literal("war")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("graceperiod")
                        .then(Commands.literal("set")
                            .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                                .executes(ctx -> WarCommands.cmdWarGraceperiodSet(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "seconds"))))))
                    // /faction war settpdistance <chunks>
                    .then(Commands.literal("settpdistance")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 50))
                            .executes(ctx -> WarCommands.cmdWarSetTpDistance(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "chunks")))))
                    // /faction war blockbreaklimit <count>
                    .then(Commands.literal("blockbreaklimit")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 1000))
                            .executes(ctx -> WarCommands.cmdWarBlockBreakLimit(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "limit")))))
                    // /faction war AfterWarCooldownTime <seconds>
                    .then(Commands.literal("AfterWarCooldownTime")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 604800))
                            .executes(ctx -> WarCommands.cmdWarAfterWarCooldown(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "seconds")))))
                    // /faction war PercentageOfOnlinePlayersForWar <0-100>
                    .then(Commands.literal("PercentageOfOnlinePlayersForWar")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                            .executes(ctx -> WarCommands.cmdWarOnlinePercentage(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "percent")))))
                    // /faction war FactionTableCaptureKothTime <seconds>
                    .then(Commands.literal("FactionTableCaptureKothTime")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                            .executes(ctx -> WarCommands.cmdWarTableKothTime(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "seconds")))))
                    // /faction war OutpostKothCaptureTime <seconds>
                    .then(Commands.literal("OutpostKothCaptureTime")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 86400))
                            .executes(ctx -> WarCommands.cmdWarOutpostKothTime(ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "seconds")))))
                    // /faction war vassals edit <faction> free
                    // /faction war vassals edit <faction> tax <amount>
                    .then(Commands.literal("vassals")
                        .then(Commands.literal("edit")
                            .requires(src -> src.hasPermission(2))
                            .then(Commands.argument("faction", StringArgumentType.string())
                                .then(Commands.literal("free")
                                    .executes(ctx -> WarCommands.cmdVassalFree(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "faction"))))
                                .then(Commands.literal("tax")
                                    .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(ctx -> WarCommands.cmdVassalTax(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "faction"),
                                                LongArgumentType.getLong(ctx, "amount")))))))))

                // /faction economy claimrates <multiplier>
                .then(Commands.literal("economy")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("claimrates")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(1.0, 10.0))
                            .executes(ctx -> EconomyCommands.cmdEconomyClaimRates(
                                    ctx.getSource(),
                                    DoubleArgumentType.getDouble(ctx, "value")))))
                    .then(Commands.literal("outpostramp")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0, 5.0))
                            .executes(ctx -> EconomyCommands.cmdEconomyOutpostRamp(
                                    ctx.getSource(),
                                    DoubleArgumentType.getDouble(ctx, "value")))))
                    .then(Commands.literal("TpCostToOutpost")
                        .then(Commands.argument("silver", IntegerArgumentType.integer(0, 10000))
                            .executes(ctx -> EconomyCommands.cmdEconomyTpCostToOutpost(
                                    ctx.getSource(),
                                    IntegerArgumentType.getInteger(ctx, "silver"))))))

                // /faction ReturnCooldownTime <seconds>
                .then(Commands.literal("ReturnCooldownTime")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                        .executes(ctx -> ReturnCommand.cmdReturnCooldownTime(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "seconds")))))

                // /faction ReturnCombatTime <seconds>
                .then(Commands.literal("ReturnCombatTime")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                        .executes(ctx -> ReturnCommand.cmdReturnCombatTime(
                                ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "seconds")))))

                .then(AdminCommands.monumentCommand())

                .then(Commands.literal("ai")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("edit")
                        .executes(ctx -> AdminCommands.cmdAiEdit(ctx.getSource()))))

                // /faction admin, the admin panel (settings, factions, editors, import / export)
                .then(Commands.literal("admin")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> AdminCommands.cmdAdminPanel(ctx.getSource())))

                // /faction bypass
                .then(Commands.literal("bypass")
                    .requires(src -> src.hasPermission(2))
                    .executes(ctx -> AdminCommands.cmdToggleBypass(ctx.getSource())))

        );
        // /factionreturn, teleports any faction member to their barracks (no op needed)
        event.getDispatcher().register(
                Commands.literal("factionreturn")
                        .executes(ctx -> ReturnCommand.cmdReturnToBase(ctx.getSource())));
    }

    // Shared utilities

    /**
     * Disbands a faction: unlinks its table block entity, notifies all online members,
     * then removes the faction from persistent storage.
     * Called by the /faction delete command and by the block-break event handler.
     */
    public static void performDisband(MinecraftServer server, UUID factionId, @Nullable Component reason) {
        performDisband(server, factionId, reason, null);
    }

    public static void performDisband(MinecraftServer server, UUID factionId, @Nullable Component reason,
                                      @Nullable ServerPlayer refundPlayer) {
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
                        SectionPos.blockToSectionCoord(table.pos().getX()),
                        SectionPos.blockToSectionCoord(table.pos().getZ()), true);
                FactionBlockProtection.allowProtectedRemoval(() -> tableLevel.removeBlock(table.pos(), false));
            }
            giveOrDrop(refundPlayer, new ItemStack(ModItems.FACTION_TABLE.get()));
        }

        // Remove the faction barracks block from the world
        FactionManager.TableLocation barracks = manager.getFactionBarracks(factionId);
        if (barracks != null) {
            ResourceKey<Level> dimKey = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(barracks.dimension()));
            ServerLevel barrLevel = server.getLevel(dimKey);
            if (barrLevel != null) {
                barrLevel.getChunkSource().getChunk(
                        SectionPos.blockToSectionCoord(barracks.pos().getX()),
                        SectionPos.blockToSectionCoord(barracks.pos().getZ()), true);
                FactionBlockProtection.allowProtectedRemoval(() -> barrLevel.removeBlock(barracks.pos(), false));
            }
            giveOrDrop(refundPlayer, new ItemStack(ModItems.BARRACKS.get()));
            manager.removeFactionBarracks(factionId);
        }

        // Remove all outposts belonging to this faction
        OutpostData outpostData = OutpostData.get(server);
        for (OutpostEntry outpost : new ArrayList<>(outpostData.getOutpostsForFaction(factionId))) {
            // Flag, storage box and land go; its items move to the item vault (handed on below) and coins to the vault.
            OutpostService.dissolve(server, outpost, "faction disbanded");
            giveOrDrop(refundPlayer, new ItemStack(ModItems.OUTPOST.get()));
        }

        // Notify and desync all online members
        for (FactionMember member : faction.getMembers()) {
            ServerPlayer mp = server.getPlayerList().getPlayer(member.getUuid());
            if (mp != null) {
                PacketDistributor.sendToPlayer(mp, new SyncFactionDataPacket(null));
                mp.displayClientMessage(msg, false);
            }
        }

        FactionUpgrades.get(server).remove(factionId);
        // The item vault's contents are delivered to the owner (claim them in My Market).
        var itemVault = FactionItemVaults.get(server).remove(factionId);
        if (faction.getOwnerId() != null) {
            var market = MarketManager.get(server);
            for (ItemStack stack : itemVault) {
                market.queueDelivery(faction.getOwnerId(), stack, "Item vault of disbanded faction " + faction.getName());
            }
        }

        // Wars this faction is in end now; the war tick cleans them up (spectators, records, demands).
        var wars = WarManager.get(server);
        for (var war : wars.getActiveWars()) {
            if (factionId.equals(war.attackerFactionId) || factionId.equals(war.defenderFactionId)) {
                war.phase = WarPhase.ENDED;
                wars.setDirty();
            }
        }
        // Whatever is left in the vault goes to the owner instead of vanishing, then the faction's records are cleared.
        var eco = EconomyManager.get(server);
        long leftover = eco.getVault(factionId);
        if (leftover > 0 && faction.getOwnerId() != null) {
            eco.addWallet(faction.getOwnerId(), leftover, "Vault of disbanded faction " + faction.getName());
        }
        eco.removeFactionData(factionId);
        // Former members keep the leave cooldown toward this faction's allies.
        for (FactionMember member : faction.getMembers()) FactionDepartures.record(server, member.getUuid(), faction);
        manager.disbandFaction(factionId);
    }

    private static void giveOrDrop(@Nullable ServerPlayer player, ItemStack stack) {
        if (player == null || stack.isEmpty()) return;
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    /** Sends an updated faction sync packet (and optional message) to an online player. */
    static void syncPlayer(MinecraftServer server, UUID uuid, @Nullable Component message) {
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
    static Optional<UUID> findPlayerUUID(MinecraftServer server, String name) {
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
    static String resolvePlayerName(MinecraftServer server, String fallback, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) return online.getGameProfile().getName();
        return server.getProfileCache().get(uuid)
                .map(GameProfile::getName).orElse(fallback);
    }

}
