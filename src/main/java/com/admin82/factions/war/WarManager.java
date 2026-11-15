package com.admin82.factions.war;

import com.admin82.factions.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Persistent store for all active wars.
 * Saved to world data under "adminsfactions_wars".
 */
public class WarManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_wars";

    private final Map<UUID, ActiveWar> activeWars       = new HashMap<>();
    /** Active Resource-War loot accesses keyed by loser faction ID. */
    private final Map<UUID, ResourceWarAccess> resourceWarAccesses = new HashMap<>();
    /**
     * Post-war cooldowns: "attackerId|defenderId" → epoch-ms when the attacker may
     * declare war on that same defender again.
     */
    private final Map<String, Long> warCooldowns = new HashMap<>();
    // Static access

    public static WarManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WarManager::new, WarManager::load, null),
                DATA_NAME
        );
    }

    // Settings. These live in the config file; see Config.

    public int getGracePeriodSeconds() { return Config.WAR_GRACE_PERIOD_SECONDS.get(); }
    public void setGracePeriodSeconds(int seconds) { Config.set(Config.WAR_GRACE_PERIOD_SECONDS, clamp(seconds, 0, 86400)); }

    public int getTpDistanceChunks() { return Config.WAR_TELEPORT_DISTANCE_CHUNKS.get(); }
    public void setTpDistanceChunks(int chunks) { Config.set(Config.WAR_TELEPORT_DISTANCE_CHUNKS, clamp(chunks, 1, 50)); }

    public boolean isWarTpEnabled() { return Config.WAR_TELEPORT_ATTACKERS.get(); }
    public void setWarTpEnabled(boolean enabled) { Config.set(Config.WAR_TELEPORT_ATTACKERS, enabled); }

    public boolean isWarBoundaryEnabled() { return Config.WAR_BOUNDARY_ENABLED.get(); }
    public void setWarBoundaryEnabled(boolean enabled) { Config.set(Config.WAR_BOUNDARY_ENABLED, enabled); }

    public int getBlockBreakLimit() { return Config.WAR_RESOURCE_BLOCK_LIMIT.get(); }
    public void setBlockBreakLimit(int limit) { Config.set(Config.WAR_RESOURCE_BLOCK_LIMIT, clamp(limit, 1, 1000)); }

    public int getTableKothTime() { return Config.WAR_CAPTURE_TIME_SECONDS.get(); }
    public void setTableKothTime(int seconds) { Config.set(Config.WAR_CAPTURE_TIME_SECONDS, clamp(seconds, 1, 86400)); }

    public int getOutpostKothTime() { return Config.WAR_OUTPOST_CAPTURE_TIME_SECONDS.get(); }
    public void setOutpostKothTime(int seconds) { Config.set(Config.WAR_OUTPOST_CAPTURE_TIME_SECONDS, clamp(seconds, 1, 86400)); }

    public int getAfterWarCooldownSeconds() { return Config.WAR_COOLDOWN_SECONDS.get(); }
    public void setAfterWarCooldownSeconds(int seconds) { Config.set(Config.WAR_COOLDOWN_SECONDS, clamp(seconds, 0, 604800)); }

    public int getMinOnlinePercentageForWar() { return Config.WAR_MIN_DEFENDERS_ONLINE_PERCENT.get(); }
    public void setMinOnlinePercentageForWar(int percent) { Config.set(Config.WAR_MIN_DEFENDERS_ONLINE_PERCENT, clamp(percent, 0, 100)); }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    /**
     * Records that the attacker just finished a war against the defender.
     * They will be blocked from declaring war on the same faction again until the cooldown expires.
     */
    public void recordWarCooldown(UUID attackerId, UUID defenderId) {
        int cooldown = getAfterWarCooldownSeconds();
        if (cooldown <= 0) return;
        warCooldowns.put(attackerId + "|" + defenderId,
                System.currentTimeMillis() + (long) cooldown * 1000L);
        setDirty();
    }

    /**
     * Returns true if the attacker is still on post-war cooldown against this specific defender.
     * Automatically cleans up expired entries.
     */
    public boolean isOnWarCooldown(UUID attackerId, UUID defenderId) {
        String key = attackerId + "|" + defenderId;
        Long expires = warCooldowns.get(key);
        if (expires == null) return false;
        if (System.currentTimeMillis() > expires) { warCooldowns.remove(key); setDirty(); return false; }
        return true;
    }

    /** Returns seconds remaining on the post-war cooldown (0 if not on cooldown). */
    public long getWarCooldownRemainingSeconds(UUID attackerId, UUID defenderId) {
        String key = attackerId + "|" + defenderId;
        Long expires = warCooldowns.get(key);
        if (expires == null) return 0;
        return Math.max(0, (expires - System.currentTimeMillis()) / 1000L);
    }

    // Active-war check

    /** Returns true if the faction is currently committed to any active or grace-period war. */
    public boolean isFactionInActiveWar(UUID factionId) {
        for (ActiveWar w : activeWars.values()) {
            if (w.phase == WarPhase.ENDED) continue;
            if (w.attackerFactionId.equals(factionId) || w.defenderFactionId.equals(factionId)) return true;
        }
        return false;
    }

    // Resource-War access

    public void addResourceWarAccess(ResourceWarAccess access) {
        resourceWarAccesses.put(access.loserFactionId, access);
        setDirty();
    }

    @Nullable
    public ResourceWarAccess getResourceWarAccess(UUID loserFactionId) {
        return resourceWarAccesses.get(loserFactionId);
    }

    public Collection<ResourceWarAccess> getAllResourceWarAccesses() {
        return Collections.unmodifiableCollection(resourceWarAccesses.values());
    }

    public void removeResourceWarAccess(UUID loserFactionId) {
        if (resourceWarAccesses.remove(loserFactionId) != null) setDirty();
    }

    /** Removes all expired Resource-War access entries. Called from the server tick. */
    public void removeExpiredResourceAccesses() {
        boolean changed = resourceWarAccesses.entrySet().removeIf(e -> e.getValue().isExpired());
        if (changed) setDirty();
    }

    // Queries

    public Collection<ActiveWar> getActiveWars() { return Collections.unmodifiableCollection(activeWars.values()); }

    @Nullable
    public ActiveWar getWar(UUID warId) { return activeWars.get(warId); }

    @Nullable
    public ActiveWar getWarBetween(UUID factionA, UUID factionB) {
        for (ActiveWar w : activeWars.values()) {
            if ((w.attackerFactionId.equals(factionA) && w.defenderFactionId.equals(factionB))
             || (w.attackerFactionId.equals(factionB) && w.defenderFactionId.equals(factionA))) {
                return w;
            }
        }
        return null;
    }

    /** Returns the war a given player is committed to, or null if none. */
    @Nullable
    public ActiveWar getWarForPlayer(UUID playerUUID) {
        for (ActiveWar w : activeWars.values()) {
            if (w.isParticipant(playerUUID)) return w;
        }
        return null;
    }

    // Lifecycle

    /**
     * Creates and registers a new war.
     *
     * @param attackerUUIDs      committed attacking players
     * @param defenderUUIDs      committed defending players (typically all faction members)
     * @param attackerLivesEach  lives per attacker
     * @param defenderLivesEach  lives per defender
     * @param defenderTablePos   centre of the capture point
     * @param defenderDim        dimension ID of the capture point
     */
    public ActiveWar startWar(UUID attackerFactionId, UUID defenderFactionId,
                              WarType warType,
                              List<UUID> attackerUUIDs, List<UUID> defenderUUIDs,
                              int attackerLivesEach, int defenderLivesEach,
                              BlockPos defenderTablePos, String defenderDim,
                              List<String> targetChunkKeys) {
        UUID   warId    = UUID.randomUUID();
        long   graceEnd = System.currentTimeMillis() + (long) getGracePeriodSeconds() * 1000L;
        ActiveWar war = new ActiveWar(warId, attackerFactionId, defenderFactionId,
                WarPhase.GRACE, warType, graceEnd, defenderTablePos, defenderDim);
        attackerUUIDs.forEach(id -> war.attackerLives.put(id, attackerLivesEach));
        defenderUUIDs.forEach(id -> war.defenderLives.put(id, defenderLivesEach));
        if (targetChunkKeys != null) war.targetChunkKeys.addAll(targetChunkKeys);
        activeWars.put(warId, war);
        setDirty();
        return war;
    }

    /** Removes the war from the active list. */
    public void endWar(UUID warId) {
        activeWars.remove(warId);
        setDirty();
    }

    // SavedData

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag warList = new ListTag();
        activeWars.values().forEach(w -> warList.add(w.save()));
        tag.put("wars", warList);
        ListTag rwList = new ListTag();
        resourceWarAccesses.values().forEach(a -> rwList.add(a.save()));
        tag.put("resourceWarAccesses", rwList);
        ListTag cdList = new ListTag();
        warCooldowns.forEach((k, v) -> { var e = new CompoundTag(); e.putString("k", k); e.putLong("v", v); cdList.add(e); });
        tag.put("warCooldowns", cdList);
        return tag;
    }

    public static WarManager load(CompoundTag tag, HolderLookup.Provider registries) {
        WarManager mgr = new WarManager();
        // Settings used to be saved here; move them to the config.
        if (tag.getInt("gracePeriodOverride") > 0) Config.migrate(Config.WAR_GRACE_PERIOD_SECONDS, clamp(tag.getInt("gracePeriodOverride"), 0, 86400));
        if (tag.getInt("tpDistanceChunksOverride") > 0) Config.migrate(Config.WAR_TELEPORT_DISTANCE_CHUNKS, clamp(tag.getInt("tpDistanceChunksOverride"), 1, 50));
        if (tag.contains("warTpEnabled")) Config.migrate(Config.WAR_TELEPORT_ATTACKERS, tag.getBoolean("warTpEnabled"));
        if (tag.contains("warBoundaryEnabled")) Config.migrate(Config.WAR_BOUNDARY_ENABLED, tag.getBoolean("warBoundaryEnabled"));
        if (tag.getInt("blockBreakLimitOverride") > 0) Config.migrate(Config.WAR_RESOURCE_BLOCK_LIMIT, clamp(tag.getInt("blockBreakLimitOverride"), 1, 1000));
        if (tag.contains("afterWarCooldownSeconds")) Config.migrate(Config.WAR_COOLDOWN_SECONDS, clamp(tag.getInt("afterWarCooldownSeconds"), 0, 604800));
        if (tag.contains("minOnlinePercentageForWar")) Config.migrate(Config.WAR_MIN_DEFENDERS_ONLINE_PERCENT, clamp(tag.getInt("minOnlinePercentageForWar"), 0, 100));
        if (tag.getInt("tableKothTime") > 0) Config.migrate(Config.WAR_CAPTURE_TIME_SECONDS, clamp(tag.getInt("tableKothTime"), 1, 86400));
        if (tag.getInt("outpostKothTime") > 0) Config.migrate(Config.WAR_OUTPOST_CAPTURE_TIME_SECONDS, clamp(tag.getInt("outpostKothTime"), 1, 86400));
        ListTag warList = tag.getList("wars", Tag.TAG_COMPOUND);
        for (int i = 0; i < warList.size(); i++) {
            ActiveWar w = ActiveWar.load(warList.getCompound(i));
            mgr.activeWars.put(w.warId, w);
        }
        ListTag rwList = tag.getList("resourceWarAccesses", Tag.TAG_COMPOUND);
        for (int i = 0; i < rwList.size(); i++) {
            ResourceWarAccess a = ResourceWarAccess.load(rwList.getCompound(i));
            mgr.resourceWarAccesses.put(a.loserFactionId, a);
        }
        ListTag cdList = tag.getList("warCooldowns", Tag.TAG_COMPOUND);
        for (int i = 0; i < cdList.size(); i++) {
            var e = cdList.getCompound(i);
            mgr.warCooldowns.put(e.getString("k"), e.getLong("v"));
        }
        // Old settings were copied to the config above; save once more so they leave the world file.
        if (Stream.of("gracePeriodOverride", "tpDistanceChunksOverride", "warTpEnabled", "warBoundaryEnabled", "blockBreakLimitOverride", "afterWarCooldownSeconds", "minOnlinePercentageForWar", "tableKothTime", "outpostKothTime").anyMatch(tag::contains)) mgr.setDirty();
        return mgr;
    }
}
