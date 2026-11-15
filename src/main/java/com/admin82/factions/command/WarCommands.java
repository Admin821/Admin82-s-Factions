package com.admin82.factions.command;

import com.admin82.factions.Config;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.faction.*;
import com.admin82.factions.war.VassalManager;
import com.admin82.factions.war.WarManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static com.admin82.factions.util.BypassManager.toggleBypass;

/** War settings and vassal management. */
final class WarCommands {

    private WarCommands() {}

    static int cmdWarSetTpDistance(CommandSourceStack src, int chunks) {
        WarManager.get(src.getServer()).setTpDistanceChunks(chunks);
        src.sendSuccess(() -> Component.literal("§aWar TP spawn distance set to §e" + chunks
                + " chunk" + (chunks == 1 ? "" : "s") + "§a from defender territory."), true);
        return chunks;
    }

    static int cmdWarDoTp(CommandSourceStack src, boolean enabled) {
        WarManager.get(src.getServer()).setWarTpEnabled(enabled);
        src.sendSuccess(() -> Component.literal("§aAttacker teleport on grace-end is now §e"
                + (enabled ? "enabled" : "disabled") + "§a."), true);
        return 1;
    }

    static int cmdWarDoBoundary(CommandSourceStack src, boolean enabled) {
        WarManager.get(src.getServer()).setWarBoundaryEnabled(enabled);
        src.sendSuccess(() -> Component.literal("§aWar boundary enforcement is now §e"
                + (enabled ? "enabled" : "disabled") + "§a."), true);
        return 1;
    }
    // /faction war

    static int cmdWarGraceperiodSet(CommandSourceStack src, int seconds) {
        if (!src.hasPermission(2)) {
            src.sendFailure(Component.literal("§cYou need operator permission to change the grace period."));
            return 0;
        }
        WarManager.get(src.getServer()).setGracePeriodSeconds(seconds);
        src.sendSuccess(() -> Component.literal("§aWar grace period set to §e"
                + Config.WAR_GRACE_PERIOD_SECONDS.get() + "s§a."), true);
        return seconds;
    }

    static int cmdWarAfterWarCooldown(CommandSourceStack src, int seconds) {
        WarManager.get(src.getServer()).setAfterWarCooldownSeconds(seconds);
        long hrs = seconds / 3600, mins = (seconds % 3600) / 60;
        String fmt = hrs > 0 ? hrs + "h " + mins + "m" : mins + "m " + (seconds % 60) + "s";
        src.sendSuccess(() -> Component.literal("§aPost-war re-declaration cooldown set to §e" + seconds
                + "s §8(" + fmt + ")§a."), true);
        return seconds;
    }

    static int cmdWarOnlinePercentage(CommandSourceStack src, int percent) {
        WarManager.get(src.getServer()).setMinOnlinePercentageForWar(percent);
        src.sendSuccess(() -> Component.literal("§aMinimum online % for war declaration set to §e"
                + percent + "%§a."), true);
        return percent;
    }

    static int cmdWarTableKothTime(CommandSourceStack src, int seconds) {
        WarManager warmgr = WarManager.get(src.getServer());
        warmgr.setTableKothTime(seconds);
        src.getServer().overworld().getDataStorage().save();
        src.sendSuccess(() -> Component.literal("§aFaction table KOTH capture time set to §e"
                + seconds + "s§a."), true);
        return seconds;
    }

    static int cmdWarOutpostKothTime(CommandSourceStack src, int seconds) {
        WarManager warmgr = WarManager.get(src.getServer());
        warmgr.setOutpostKothTime(seconds);
        src.getServer().overworld().getDataStorage().save();
        src.sendSuccess(() -> Component.literal("§aOutpost KOTH capture time set to §e"
                + seconds + "s§a."), true);
        return seconds;
    }

    // /faction war vassals edit

    static int cmdVassalFree(CommandSourceStack src, String factionName) {
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

    static int cmdVassalTax(CommandSourceStack src, String factionName, long amount) {
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

    static int cmdWarBlockBreakLimit(CommandSourceStack src, int limit) {
        WarManager.get(src.getServer()).setBlockBreakLimit(limit);
        src.sendSuccess(() -> Component.literal(
                "§aResource-War block-break limit set to §e" + limit + "§a blocks."), true);
        return limit;
    }
}
