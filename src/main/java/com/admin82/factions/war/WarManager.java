package com.admin82.factions.war;

import com.admin82.factions.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Persistent store for all active wars.
 * Saved to world data under "adminsfactions_wars".
 */
public class WarManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_wars";

    private final Map<UUID, ActiveWar> activeWars       = new HashMap<>();
    /** -1 means use Config default; any positive value overrides it. */
    private int     gracePeriodOverride      = -1;
    /** -1 means use default (5); any positive value overrides the attacker TP spawn distance in chunks. */
    private int     tpDistanceChunksOverride = -1;
    /** Whether attackers are teleported to the front line when the grace period ends. Default OFF. */
    private boolean warTpEnabled             = false;
    /** Whether the war boundary is enforced for attackers. Default OFF. */
    private boolean warBoundaryEnabled       = false;
    /** Max blocks the Resource-War winner may break in the loser's territory. -1 = use default (50). */
    private int     blockBreakLimitOverride  = -1;
    /** Faction-table KOTH capture time in seconds. -1 = use Config default. */
    private int     tableKothTimeOverride    = -1;
    /** Outpost KOTH capture time in seconds. -1 = use OutpostEntry.CAPTURE_TIME_SECONDS (60). */
    private int     outpostKothTimeOverride  = -1;
    /** Active Resource-War loot accesses keyed by loser faction ID. */
    private final Map<UUID, ResourceWarAccess> resourceWarAccesses = new HashMap<>();
    /**
     * Post-war cooldowns: "attackerId|defenderId" → epoch-ms when the attacker may
     * declare war on that same defender again.
     */
    private final Map<String, Long> warCooldowns = new HashMap<>();
    /** Duration of the post-war cooldown in seconds (default 24 h). */
    private int afterWarCooldownSeconds    = 86_400;
    /**
     * Minimum percentage (0-100) of a defender's members that must be online before
     * a war can be declared on them.  0 = no minimum.
     */
    private int minOnlinePercentageForWar  = 50;
    // ── Static access ─────────────────────────────────────────────────────────

    public static WarManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WarManager::new, WarManager::load, null),
                DATA_NAME
        );
    }

    // ── Grace period ──────────────────────────────────────────────────────────

    /** Returns the effective grace period in seconds (command override or config default). */
    public int getGracePeriodSeconds() {
        return gracePeriodOverride > 0 ? gracePeriodOverride : Config.WAR_GRACE_PERIOD_SECONDS.get();
    }

    /** Overrides the grace period for this world. Set to -1 to revert to config. */
    public void setGracePeriodSeconds(int seconds) {
        this.gracePeriodOverride = seconds;
        setDirty();
    }

    // ── TP spawn distance ──────────────────────────────────────────────

    /** Returns the effective attacker TP distance in chunks (command override or default 5). */
    public int getTpDistanceChunks() {
        return tpDistanceChunksOverride > 0 ? tpDistanceChunksOverride : 5;
    }

    /** Overrides the TP spawn distance for this world (in chunks). Set to -1 to revert to default. */
    public void setTpDistanceChunks(int chunks) {
        this.tpDistanceChunksOverride = chunks;
        setDirty();
    }

    // ── War TP toggle ─────────────────────────────────────────────────────────

    /** Returns whether attackers are teleported to the front line when grace ends. */
    public boolean isWarTpEnabled() { return warTpEnabled; }

    /** Enables or disables the attacker teleport system for this world. */
    public void setWarTpEnabled(boolean enabled) { this.warTpEnabled = enabled; setDirty(); }

    // ── War boundary toggle ───────────────────────────────────────────────────

    /** Returns whether the war boundary is enforced for attackers. */
    public boolean isWarBoundaryEnabled() { return warBoundaryEnabled; }

    /** Enables or disables the war boundary for this world. */
    public void setWarBoundaryEnabled(boolean enabled) { this.warBoundaryEnabled = enabled; setDirty(); }

    // ── Block-break limit ────────────────────────────────────────────────

    /** Returns how many blocks the Resource-War winner may break. Default 50. */
    public int getBlockBreakLimit() {
        return blockBreakLimitOverride > 0 ? blockBreakLimitOverride : 50;
    }

    public void setBlockBreakLimit(int limit) {
        this.blockBreakLimitOverride = Math.max(1, limit);
        setDirty();
    }

    // ── KOTH capture times ─────────────────────────────────────────────────────

    /** Faction-table KOTH capture time. Default from Config. */
    public int getTableKothTime() {
        return tableKothTimeOverride > 0 ? tableKothTimeOverride : com.admin82.factions.Config.WAR_CAPTURE_TIME_SECONDS.get();
    }
    public void setTableKothTime(int seconds) { this.tableKothTimeOverride  = Math.max(1, seconds); setDirty(); }

    /** Outpost KOTH capture time. Default 60 s. */
    public int getOutpostKothTime() {
        return outpostKothTimeOverride > 0 ? outpostKothTimeOverride : (int) com.admin82.factions.outpost.OutpostEntry.CAPTURE_TIME_SECONDS;
    }
    public void setOutpostKothTime(int seconds) { this.outpostKothTimeOverride = Math.max(1, seconds); setDirty(); }

    public int getAfterWarCooldownSeconds() { return afterWarCooldownSeconds; }
    public void setAfterWarCooldownSeconds(int seconds) {
        this.afterWarCooldownSeconds = Math.max(0, seconds); setDirty();
    }

    /**
     * Records that the attacker just finished a war against the defender.
     * They will be blocked from declaring war on the same faction again until the cooldown expires.
     */
    public void recordWarCooldown(UUID attackerId, UUID defenderId) {
        if (afterWarCooldownSeconds <= 0) return;
        warCooldowns.put(attackerId + "|" + defenderId,
                System.currentTimeMillis() + (long) afterWarCooldownSeconds * 1000L);
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

    // ── Online-percentage requirement ───────────────────────────────────────────

    public int getMinOnlinePercentageForWar() { return minOnlinePercentageForWar; }
    public void setMinOnlinePercentageForWar(int percent) {
        this.minOnlinePercentageForWar = Math.max(0, Math.min(100, percent)); setDirty();
    }

    // ── Active-war check ────────────────────────────────────────────────────────

    /** Returns true if the faction is currently committed to any active or grace-period war. */
    public boolean isFactionInActiveWar(UUID factionId) {
        for (ActiveWar w : activeWars.values()) {
            if (w.phase == WarPhase.ENDED) continue;
            if (w.attackerFactionId.equals(factionId) || w.defenderFactionId.equals(factionId)) return true;
        }
        return false;
    }

    // ── Resource-War access ──────────────────────────────────────────

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

    // ── Queries ───────────────────────────────────────────────────────────────

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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

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

    // ── SavedData ─────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("gracePeriodOverride", gracePeriodOverride);
        tag.putInt("tpDistanceChunksOverride", tpDistanceChunksOverride);
        tag.putBoolean("warTpEnabled", warTpEnabled);
        tag.putBoolean("warBoundaryEnabled", warBoundaryEnabled);
        tag.putInt("blockBreakLimitOverride", blockBreakLimitOverride);
        tag.putInt("afterWarCooldownSeconds", afterWarCooldownSeconds);
        tag.putInt("minOnlinePercentageForWar", minOnlinePercentageForWar);
        if (tableKothTimeOverride   > 0) tag.putInt("tableKothTime",   tableKothTimeOverride);
        if (outpostKothTimeOverride > 0) tag.putInt("outpostKothTime", outpostKothTimeOverride);
        ListTag warList = new ListTag();
        activeWars.values().forEach(w -> warList.add(w.save()));
        tag.put("wars", warList);
        ListTag rwList = new ListTag();
        resourceWarAccesses.values().forEach(a -> rwList.add(a.save()));
        tag.put("resourceWarAccesses", rwList);
        ListTag cdList = new ListTag();
        warCooldowns.forEach((k, v) -> { var e = new net.minecraft.nbt.CompoundTag(); e.putString("k", k); e.putLong("v", v); cdList.add(e); });
        tag.put("warCooldowns", cdList);
        return tag;
    }

    public static WarManager load(CompoundTag tag, HolderLookup.Provider registries) {
        WarManager mgr = new WarManager();
        mgr.gracePeriodOverride = tag.getInt("gracePeriodOverride");
        if (mgr.gracePeriodOverride == 0) mgr.gracePeriodOverride = -1;
        mgr.tpDistanceChunksOverride = tag.getInt("tpDistanceChunksOverride");
        if (mgr.tpDistanceChunksOverride == 0) mgr.tpDistanceChunksOverride = -1;
        mgr.warTpEnabled       = tag.contains("warTpEnabled")       && tag.getBoolean("warTpEnabled");
        mgr.warBoundaryEnabled = tag.contains("warBoundaryEnabled") && tag.getBoolean("warBoundaryEnabled");
        if (tag.contains("blockBreakLimitOverride")) {
            int bbl = tag.getInt("blockBreakLimitOverride");
            mgr.blockBreakLimitOverride = bbl == 0 ? -1 : bbl;
        }
        if (tag.contains("afterWarCooldownSeconds"))   mgr.afterWarCooldownSeconds   = tag.getInt("afterWarCooldownSeconds");
        if (tag.contains("minOnlinePercentageForWar")) mgr.minOnlinePercentageForWar = tag.getInt("minOnlinePercentageForWar");
        if (tag.contains("tableKothTime"))   mgr.tableKothTimeOverride   = tag.getInt("tableKothTime");
        if (tag.contains("outpostKothTime")) mgr.outpostKothTimeOverride = tag.getInt("outpostKothTime");
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
        return mgr;
    }
}
