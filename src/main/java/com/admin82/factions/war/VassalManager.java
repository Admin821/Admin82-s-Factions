package com.admin82.factions.war;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Persistent store for vassal relationships, accumulated taxes, and pending conquest decisions.
 */
public class VassalManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_vassals";

    /** vassalFactionId → suzerainFactionId */
    private final Map<UUID, UUID> vassalToSuzerain  = new HashMap<>();
    /** vassalFactionId → copper accumulated but not yet collected by suzerain */
    private final Map<UUID, Long> accumulatedTax     = new HashMap<>();
    /** attackerFactionId → defeatedFactionId — waiting for the conqueror to choose */
    private final Map<UUID, UUID> pendingConquests   = new HashMap<>();

    // ── Static access ─────────────────────────────────────────────────────────

    public static VassalManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(VassalManager::new, VassalManager::load, null),
                DATA_NAME
        );
    }

    // ── Vassal relationship ───────────────────────────────────────────────────

    public boolean isVassal(UUID factionId) { return vassalToSuzerain.containsKey(factionId); }

    @Nullable
    public UUID getSuzerain(UUID vassalId) { return vassalToSuzerain.get(vassalId); }

    /** Returns all vassal faction IDs for the given suzerain. */
    public Set<UUID> getVassals(UUID suzerainId) {
        Set<UUID> result = new HashSet<>();
        vassalToSuzerain.forEach((v, s) -> { if (s.equals(suzerainId)) result.add(v); });
        return result;
    }

    public boolean isSuzerain(UUID factionId) {
        return vassalToSuzerain.values().stream().anyMatch(s -> s.equals(factionId));
    }

    public void makeVassal(UUID vassalId, UUID suzerainId) {
        vassalToSuzerain.put(vassalId, suzerainId);
        setDirty();
    }

    public void freeVassal(UUID vassalId) {
        vassalToSuzerain.remove(vassalId);
        accumulatedTax.remove(vassalId);
        setDirty();
    }

    // ── Tax ───────────────────────────────────────────────────────────────────

    /** Add copper to the pending tax for a vassal faction. */
    public void accumulateTax(UUID vassalId, long copper) {
        if (copper <= 0 || !isVassal(vassalId)) return;
        accumulatedTax.merge(vassalId, copper, Long::sum);
        setDirty();
    }

    public long getPendingTax(UUID vassalId) {
        return accumulatedTax.getOrDefault(vassalId, 0L);
    }

    /** Resets and returns all accumulated tax for a vassal. */
    public long collectTax(UUID vassalId) {
        long amount = accumulatedTax.getOrDefault(vassalId, 0L);
        if (amount > 0) { accumulatedTax.remove(vassalId); setDirty(); }
        return amount;
    }

    // ── Pending conquests ─────────────────────────────────────────────────────

    public void addPendingConquest(UUID attackerFactionId, UUID defeatedFactionId) {
        pendingConquests.put(attackerFactionId, defeatedFactionId);
        setDirty();
    }

    @Nullable
    public UUID getConqueredFaction(UUID attackerFactionId) {
        return pendingConquests.get(attackerFactionId);
    }

    public void clearPendingConquest(UUID attackerFactionId) {
        if (pendingConquests.remove(attackerFactionId) != null) setDirty();
    }

    public Map<UUID, UUID> getAllPendingConquests() {
        return Collections.unmodifiableMap(pendingConquests);
    }

    // ── SavedData ─────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag vts = new CompoundTag();
        vassalToSuzerain.forEach((v, s) -> vts.putUUID(v.toString(), s));
        tag.put("vassalToSuzerain", vts);

        CompoundTag tax = new CompoundTag();
        accumulatedTax.forEach((v, a) -> tax.putLong(v.toString(), a));
        tag.put("accumulatedTax", tax);

        CompoundTag pc = new CompoundTag();
        pendingConquests.forEach((a, d) -> pc.putUUID(a.toString(), d));
        tag.put("pendingConquests", pc);

        return tag;
    }

    public static VassalManager load(CompoundTag tag, HolderLookup.Provider registries) {
        VassalManager mgr = new VassalManager();
        if (tag.contains("vassalToSuzerain")) {
            CompoundTag vts = tag.getCompound("vassalToSuzerain");
            vts.getAllKeys().forEach(k -> mgr.vassalToSuzerain.put(UUID.fromString(k), vts.getUUID(k)));
        }
        if (tag.contains("accumulatedTax")) {
            CompoundTag t = tag.getCompound("accumulatedTax");
            t.getAllKeys().forEach(k -> mgr.accumulatedTax.put(UUID.fromString(k), t.getLong(k)));
        }
        if (tag.contains("pendingConquests")) {
            CompoundTag pc = tag.getCompound("pendingConquests");
            pc.getAllKeys().forEach(k -> mgr.pendingConquests.put(UUID.fromString(k), pc.getUUID(k)));
        }
        return mgr;
    }
}
