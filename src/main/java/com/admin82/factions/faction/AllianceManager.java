package com.admin82.factions.faction;

import com.admin82.factions.Config;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;
import javax.annotation.Nullable;

/**
 * Alliances are non-aggression pacts between two factions. While allied, and for a cooldown
 * after the alliance is broken, the two factions cannot declare war on each other, cannot
 * claim bounties on each other, and cannot fight on opposite sides of a war.
 */
public class AllianceManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_alliances";
    /** Unanswered alliance proposals expire after this long. */
    public static final long PROPOSAL_EXPIRY_MS = 24L * 3_600_000L;

    /** Pair key (lower UUID first) → time the alliance was formed. */
    private final Map<String, Long> alliances = new HashMap<>();
    /** Pair key → time the protection from a broken alliance ends. */
    private final Map<String, Long> brokenUntil = new HashMap<>();
    /** Proposer faction → (target faction → time proposed). */
    private final Map<UUID, Map<UUID, Long>> proposals = new HashMap<>();

    private long version;

    public static AllianceManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AllianceManager::new, AllianceManager::load, null), DATA_NAME);
    }

    public long getVersion() { return version; }

    @Override
    public void setDirty(boolean dirty) {
        if (dirty) version++;
        super.setDirty(dirty);
    }

    public static String key(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    @Nullable
    private static UUID other(String key, UUID self) {
        String[] parts = key.split("\\|");
        if (parts.length != 2) return null;
        UUID a = UUID.fromString(parts[0]), b = UUID.fromString(parts[1]);
        if (a.equals(self)) return b;
        if (b.equals(self)) return a;
        return null;
    }

    // Queries

    public boolean areAllied(UUID a, UUID b) {
        return a != null && b != null && !a.equals(b) && alliances.containsKey(key(a, b));
    }

    /** Remaining protection after a broken alliance, or 0 if none. */
    public long cooldownRemainingMs(UUID a, UUID b) {
        if (a == null || b == null) return 0;
        Long until = brokenUntil.get(key(a, b));
        return until == null ? 0 : Math.max(0, until - System.currentTimeMillis());
    }

    /** True while allied or during the cooldown after breaking: the pact's rules still apply. */
    public boolean isProtected(UUID a, UUID b) {
        return areAllied(a, b) || cooldownRemainingMs(a, b) > 0;
    }

    public boolean hasProposal(UUID from, UUID to) {
        Long at = proposals.getOrDefault(from, Map.of()).get(to);
        return at != null && System.currentTimeMillis() - at < PROPOSAL_EXPIRY_MS;
    }

    public Set<UUID> getAllies(UUID faction) {
        Set<UUID> result = new HashSet<>();
        for (String k : alliances.keySet()) {
            UUID o = other(k, faction);
            if (o != null) result.add(o);
        }
        return result;
    }

    public long allianceFormedAt(UUID a, UUID b) {
        return alliances.getOrDefault(key(a, b), 0L);
    }

    /** Factions whose broken-alliance cooldown with {@code faction} is still running → ms left. */
    public Map<UUID, Long> getCooldowns(UUID faction) {
        Map<UUID, Long> result = new HashMap<>();
        long now = System.currentTimeMillis();
        brokenUntil.forEach((k, until) -> {
            UUID o = other(k, faction);
            if (o != null && until > now) result.put(o, until - now);
        });
        return result;
    }

    /** Proposals sent by {@code faction} that are still open → time sent. */
    public Map<UUID, Long> getOutgoing(UUID faction) {
        Map<UUID, Long> result = new HashMap<>();
        proposals.getOrDefault(faction, Map.of()).forEach((to, at) -> {
            if (System.currentTimeMillis() - at < PROPOSAL_EXPIRY_MS) result.put(to, at);
        });
        return result;
    }

    /** Proposals sent to {@code faction} that are still open → time sent. */
    public Map<UUID, Long> getIncoming(UUID faction) {
        Map<UUID, Long> result = new HashMap<>();
        proposals.forEach((from, map) -> {
            Long at = map.get(faction);
            if (at != null && System.currentTimeMillis() - at < PROPOSAL_EXPIRY_MS) result.put(from, at);
        });
        return result;
    }

    // Changes

    public void propose(UUID from, UUID to) {
        proposals.computeIfAbsent(from, ignored -> new HashMap<>()).put(to, System.currentTimeMillis());
        setDirty();
    }

    public void removeProposal(UUID from, UUID to) {
        Map<UUID, Long> map = proposals.get(from);
        if (map != null && map.remove(to) != null) {
            if (map.isEmpty()) proposals.remove(from);
            setDirty();
        }
    }

    public void form(UUID a, UUID b) {
        alliances.put(key(a, b), System.currentTimeMillis());
        brokenUntil.remove(key(a, b));
        removeProposal(a, b);
        removeProposal(b, a);
        setDirty();
    }

    /** Ends the alliance; its protections keep applying for the configured cooldown. */
    public void breakAlliance(UUID a, UUID b) {
        if (alliances.remove(key(a, b)) == null) return;
        long cooldown = Config.ALLIANCE_BREAK_COOLDOWN_HOURS.get() * 3_600_000L;
        if (cooldown > 0) brokenUntil.put(key(a, b), System.currentTimeMillis() + cooldown);
        setDirty();
    }

    /** Drops everything involving a disbanded faction. */
    public void removeFaction(UUID faction) {
        String id = faction.toString();
        boolean changed = alliances.keySet().removeIf(k -> k.contains(id));
        changed |= brokenUntil.keySet().removeIf(k -> k.contains(id));
        changed |= proposals.remove(faction) != null;
        for (Map<UUID, Long> map : proposals.values()) changed |= map.remove(faction) != null;
        proposals.values().removeIf(Map::isEmpty);
        if (changed) setDirty();
    }

    /** Drops every entry that mentions a faction not in {@code known}. */
    public void retainFactions(Set<UUID> known) {
        Set<UUID> mentioned = new HashSet<>(proposals.keySet());
        proposals.values().forEach(map -> mentioned.addAll(map.keySet()));
        for (String k : alliances.keySet()) for (String part : k.split("\\|")) mentioned.add(UUID.fromString(part));
        for (String k : brokenUntil.keySet()) for (String part : k.split("\\|")) mentioned.add(UUID.fromString(part));
        for (UUID id : mentioned) if (!known.contains(id)) removeFaction(id);
    }

    /** Clears expired cooldowns and proposals; called now and then. */
    public void prune() {
        long now = System.currentTimeMillis();
        boolean changed = brokenUntil.values().removeIf(until -> until <= now);
        for (Map<UUID, Long> map : proposals.values()) changed |= map.values().removeIf(at -> now - at >= PROPOSAL_EXPIRY_MS);
        proposals.values().removeIf(Map::isEmpty);
        if (changed) setDirty();
    }

    /** Short "1d 4h" style duration. */
    public static String formatDuration(long ms) {
        long minutes = Math.max(1, ms / 60_000L);
        long days = minutes / 1440, hours = (minutes % 1440) / 60, mins = minutes % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }

    // Persistence

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        alliances.forEach((k, at) -> { CompoundTag e = new CompoundTag(); e.putString("k", k); e.putLong("at", at); list.add(e); });
        tag.put("alliances", list);
        ListTag broken = new ListTag();
        brokenUntil.forEach((k, until) -> { CompoundTag e = new CompoundTag(); e.putString("k", k); e.putLong("until", until); broken.add(e); });
        tag.put("broken", broken);
        ListTag props = new ListTag();
        proposals.forEach((from, map) -> map.forEach((to, at) -> {
            CompoundTag e = new CompoundTag();
            e.putUUID("from", from);
            e.putUUID("to", to);
            e.putLong("at", at);
            props.add(e);
        }));
        tag.put("proposals", props);
        return tag;
    }

    public static AllianceManager load(CompoundTag tag, HolderLookup.Provider registries) {
        AllianceManager m = new AllianceManager();
        for (Tag t : tag.getList("alliances", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            m.alliances.put(e.getString("k"), e.getLong("at"));
        }
        for (Tag t : tag.getList("broken", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            m.brokenUntil.put(e.getString("k"), e.getLong("until"));
        }
        for (Tag t : tag.getList("proposals", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            m.proposals.computeIfAbsent(e.getUUID("from"), ignored -> new HashMap<>()).put(e.getUUID("to"), e.getLong("at"));
        }
        return m;
    }
}
