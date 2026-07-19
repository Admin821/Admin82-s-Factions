package com.admin82.factions.war;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persistent store for all wartime negotiation demands.
 * Saved to world data as {@code "adminsfactions_negotiations"}.
 */
public class WarNegotiationsManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_negotiations";

    private final Map<UUID, WarDemand> demands = new HashMap<>();

    // ── Singleton access ──────────────────────────────────────────────────────

    public static WarNegotiationsManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(WarNegotiationsManager::new, WarNegotiationsManager::load, null),
                DATA_NAME
        );
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    public void addDemand(WarDemand demand) {
        demands.put(demand.demandId, demand);
        setDirty();
    }

    public void resolveDemand(UUID demandId, WarDemand.Status status) {
        WarDemand d = demands.get(demandId);
        if (d != null) { d.status = status; setDirty(); }
    }

    /** Marks any PENDING demands whose expiry has passed as EXPIRED. */
    public void tickExpiry() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (WarDemand d : demands.values()) {
            if (d.status == WarDemand.Status.PENDING && now > d.expiresAt) {
                d.status = WarDemand.Status.EXPIRED;
                changed = true;
            }
        }
        if (changed) setDirty();
    }

    /** Returns all demands for a given war (both directions), newest first. */
    public List<WarDemand> getDemandsForWar(UUID warId) {
        return demands.values().stream()
                .filter(d -> d.warId.equals(warId))
                .sorted(Comparator.comparingLong((WarDemand d) -> d.sentAt).reversed())
                .collect(Collectors.toList());
    }

    /** Returns all PENDING demands where the given faction is the receiver. */
    public List<WarDemand> getIncomingPending(UUID factionId) {
        return demands.values().stream()
                .filter(d -> d.status == WarDemand.Status.PENDING
                          && d.receiverFactionId.equals(factionId))
                .sorted(Comparator.comparingLong(d -> d.sentAt))
                .collect(Collectors.toList());
    }

    @Nullable
    public WarDemand getDemand(UUID demandId) {
        return demands.get(demandId);
    }

    /** Removes all demands associated with a war (called when war ends). */
    public void clearWarDemands(UUID warId) {
        demands.entrySet().removeIf(e -> e.getValue().warId.equals(warId));
        setDirty();
    }

    // ── SavedData ─────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        demands.values().forEach(d -> list.add(d.save()));
        tag.put("demands", list);
        return tag;
    }

    private static WarNegotiationsManager load(CompoundTag tag, HolderLookup.Provider reg) {
        WarNegotiationsManager mgr = new WarNegotiationsManager();
        ListTag list = tag.getList("demands", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            WarDemand d = WarDemand.load(list.getCompound(i));
            mgr.demands.put(d.demandId, d);
        }
        return mgr;
    }
}
