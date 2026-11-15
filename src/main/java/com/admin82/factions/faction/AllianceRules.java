package com.admin82.factions.faction;

import com.admin82.factions.Config;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;
import javax.annotation.Nullable;

/** The rules an alliance enforces, in one place so every feature checks them the same way. */
public final class AllianceRules {

    private AllianceRules() {}

    /** Why {@code attacker} may not declare war on {@code defender}, or null if it may. */
    @Nullable
    public static String warBlockedReason(MinecraftServer server, UUID attacker, UUID defender) {
        AllianceManager am = AllianceManager.get(server);
        if (am.areAllied(attacker, defender)) {
            return "§c[Alliance] You are allied with that faction. Break the alliance first; its protections then last another "
                    + AllianceManager.formatDuration(Config.ALLIANCE_BREAK_COOLDOWN_HOURS.get() * 3_600_000L) + ".";
        }
        long left = am.cooldownRemainingMs(attacker, defender);
        if (left > 0) {
            return "§c[Alliance] Your alliance with that faction ended recently. You can't declare war on them for another §e"
                    + AllianceManager.formatDuration(left) + "§c.";
        }
        return null;
    }

    /**
     * Whether {@code actor} is barred from acting against {@code target} (bounties): their factions are
     * covered by an alliance (or its cooldown), or the actor recently left the target's faction or one of
     * its allies. The leave rule only binds the player who left.
     */
    public static boolean playersProtected(MinecraftServer server, UUID actor, UUID target) {
        FactionManager fmgr = FactionManager.get(server);
        UUID a = fmgr.getPlayerFactionId(actor);
        UUID b = fmgr.getPlayerFactionId(target);
        if (a != null && b != null && !a.equals(b) && AllianceManager.get(server).isProtected(a, b)) return true;
        return FactionDepartures.get(server).covering(actor, b) != null;
    }

    /** Why {@code player} can't act against {@code faction} because they recently left it or one of its allies, or null. */
    @Nullable
    public static String leftRecentlyReason(MinecraftServer server, UUID player, @Nullable UUID faction) {
        var d = FactionDepartures.get(server).covering(player, faction);
        if (d == null) return null;
        return "You left " + d.formerFaction() + " recently, so you can't act against it or its allies for another "
                + AllianceManager.formatDuration(d.until() - System.currentTimeMillis()) + ".";
    }
}
