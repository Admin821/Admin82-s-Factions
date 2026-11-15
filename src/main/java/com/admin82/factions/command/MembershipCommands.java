package com.admin82.factions.command;

import com.admin82.factions.faction.*;
import com.admin82.factions.network.packet.SyncFactionDataPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;
import java.util.UUID;

import static com.admin82.factions.util.BypassManager.toggleBypass;

/** Listing factions and moving players in and out of them. */
final class MembershipCommands {

    private MembershipCommands() {}

    // /faction list

    static int cmdList(CommandSourceStack src) {
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

    // /faction info <name>

    static int cmdInfo(CommandSourceStack src, String name) {
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

    // /faction join <name>

    static int cmdJoin(CommandSourceStack src, String factionName) {
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

    // /faction delete <name>

    static int cmdDelete(CommandSourceStack src, String name) {
        FactionManager manager = FactionManager.get(src.getServer());
        Faction faction = manager.getFactionByName(name);
        if (faction == null) {
            src.sendFailure(Component.literal("§cFaction '§e" + name + "§c' not found."));
            return 0;
        }
        String factionName = faction.getName();
        FactionCommands.performDisband(src.getServer(), faction.getId(),
                Component.literal("§cFaction '§e" + factionName + "§c' was deleted by an admin."));
        src.sendSuccess(() -> Component.literal("§aFaction '§e" + factionName + "§a' has been deleted."), true);
        return 1;
    }

    // /faction set <player> <faction|none>

    static int cmdSet(CommandSourceStack src, String playerName, String factionName) {
        MinecraftServer server = src.getServer();
        FactionManager manager = FactionManager.get(server);

        Optional<UUID> uuidOpt = FactionCommands.findPlayerUUID(server, playerName);
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
            if (current != null) FactionDepartures.record(server, playerUUID, current);
            manager.removePlayerFromFaction(playerUUID);
            FactionCommands.syncPlayer(server, playerUUID, null);
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

        String resolvedName = FactionCommands.resolvePlayerName(server, playerName, playerUUID);
        manager.addPlayerToFaction(target.getId(), playerUUID, resolvedName);
        FactionCommands.syncPlayer(server, playerUUID,
                Component.literal("§aYou were placed in faction '§e" + target.getName() + "§a' by an admin."));
        src.sendSuccess(() -> Component.literal(
                "§aSet §e" + playerName + " §ato faction '§e" + target.getName() + "§a'."), true);
        return 1;
    }

    // /faction add <player> <faction>

    static int cmdAdd(CommandSourceStack src, String playerName, String factionName) {
        MinecraftServer server = src.getServer();
        FactionManager manager = FactionManager.get(server);

        Optional<UUID> uuidOpt = FactionCommands.findPlayerUUID(server, playerName);
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

        String resolvedName = FactionCommands.resolvePlayerName(server, playerName, playerUUID);
        manager.addPlayerToFaction(target.getId(), playerUUID, resolvedName);
        FactionCommands.syncPlayer(server, playerUUID,
                Component.literal("§aYou were added to faction '§e" + target.getName() + "§a' by an admin."));
        src.sendSuccess(() -> Component.literal(
                "§aAdded §e" + playerName + " §ato faction '§e" + target.getName() + "§a'."), true);
        return 1;
    }
}
