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
    private int gracePeriodOverride = -1;

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
                              List<UUID> attackerUUIDs, List<UUID> defenderUUIDs,
                              int attackerLivesEach, int defenderLivesEach,
                              BlockPos defenderTablePos, String defenderDim) {
        UUID   warId      = UUID.randomUUID();
        long   graceEnd   = System.currentTimeMillis() + (long) getGracePeriodSeconds() * 1000L;
        ActiveWar war = new ActiveWar(warId, attackerFactionId, defenderFactionId,
                WarPhase.GRACE, graceEnd, defenderTablePos, defenderDim);
        attackerUUIDs.forEach(id -> war.attackerLives.put(id, attackerLivesEach));
        defenderUUIDs.forEach(id -> war.defenderLives.put(id, defenderLivesEach));
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
        ListTag warList = new ListTag();
        activeWars.values().forEach(w -> warList.add(w.save()));
        tag.put("wars", warList);
        return tag;
    }

    public static WarManager load(CompoundTag tag, HolderLookup.Provider registries) {
        WarManager mgr = new WarManager();
        mgr.gracePeriodOverride = tag.getInt("gracePeriodOverride");
        if (mgr.gracePeriodOverride == 0) mgr.gracePeriodOverride = -1; // 0 wasn't set
        ListTag warList = tag.getList("wars", Tag.TAG_COMPOUND);
        for (int i = 0; i < warList.size(); i++) {
            ActiveWar w = ActiveWar.load(warList.getCompound(i));
            mgr.activeWars.put(w.warId, w);
        }
        return mgr;
    }
}
