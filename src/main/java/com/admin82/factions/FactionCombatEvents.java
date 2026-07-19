package com.admin82.factions;

import com.admin82.factions.faction.FactionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = AdminsFactions.MODID)
public class FactionCombatEvents {

    private static final Map<UUID, Long> returnCombatLocks = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerDamagedByPlayer(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;
        Entity sourceEntity = event.getSource().getEntity();
        if (!(sourceEntity instanceof ServerPlayer attacker) || attacker.getUUID().equals(victim.getUUID())) return;
        if (event.getAmount() <= 0.0F) return;

        int combatSeconds = FactionManager.get(victim.server).getFactionReturnCombatSeconds();
        if (combatSeconds <= 0) return;

        long lockedUntil = System.currentTimeMillis() + (long) combatSeconds * 1000L;
        returnCombatLocks.put(victim.getUUID(), lockedUntil);
        returnCombatLocks.put(attacker.getUUID(), lockedUntil);
    }

    public static long getReturnCombatRemainingSeconds(ServerPlayer player) {
        Long lockedUntil = returnCombatLocks.get(player.getUUID());
        if (lockedUntil == null) return 0L;

        long now = System.currentTimeMillis();
        if (now >= lockedUntil) {
            returnCombatLocks.remove(player.getUUID());
            return 0L;
        }
        return Math.max(1L, (lockedUntil - now + 999L) / 1000L);
    }

    public static void clearReturnCombatLocks() {
        returnCombatLocks.clear();
    }
}