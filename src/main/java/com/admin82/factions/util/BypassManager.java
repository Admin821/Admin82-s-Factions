package com.admin82.factions.util;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class BypassManager {
    private static final Set<UUID> bypassEnabled = new HashSet<>();

    public static boolean isBypassing(UUID playerUUID) {
        return bypassEnabled.contains(playerUUID);
    }

    public static int toggleBypass(UUID playerUUID) {
        if (bypassEnabled.contains(playerUUID)) {
            bypassEnabled.remove(playerUUID);
            return 0;
        } else {
            bypassEnabled.add(playerUUID);
            return 1;
        }
    }
}