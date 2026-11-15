package com.admin82.factions.economy;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;
import javax.annotation.Nullable;

/**
 * Bounty board. A player pays a reward from their wallet to put a bounty on another player;
 * the money is held until someone eligible kills the target, the bounty expires (refund) or
 * the placer cancels it (refund).
 */
public class BountyManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_bounties";

    public static final class Bounty {
        public final UUID id;
        public final UUID targetId;
        public String targetName;
        public final UUID placerId;
        public String placerName;
        public final long reward;
        public final long createdAt;
        public final long expiresAt;

        public Bounty(UUID id, UUID targetId, String targetName, UUID placerId, String placerName,
                      long reward, long createdAt, long expiresAt) {
            this.id = id;
            this.targetId = targetId;
            this.targetName = targetName;
            this.placerId = placerId;
            this.placerName = placerName;
            this.reward = reward;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            t.putUUID("target", targetId);
            t.putString("targetName", targetName);
            t.putUUID("placer", placerId);
            t.putString("placerName", placerName);
            t.putLong("reward", reward);
            t.putLong("created", createdAt);
            t.putLong("expires", expiresAt);
            return t;
        }

        static Bounty load(CompoundTag t) {
            return new Bounty(t.getUUID("id"), t.getUUID("target"), t.getString("targetName"), t.getUUID("placer"),
                    t.getString("placerName"), t.getLong("reward"), t.getLong("created"), t.getLong("expires"));
        }
    }

    private final Map<UUID, Bounty> bounties = new LinkedHashMap<>();
    private long version;

    public static BountyManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BountyManager::new, BountyManager::load, null), DATA_NAME);
    }

    public long getVersion() { return version; }

    @Override
    public void setDirty(boolean dirty) {
        if (dirty) version++;
        super.setDirty(dirty);
    }

    public Collection<Bounty> getAll() { return Collections.unmodifiableCollection(bounties.values()); }

    @Nullable public Bounty get(UUID id) { return bounties.get(id); }

    public List<Bounty> getOnTarget(UUID target) {
        return bounties.values().stream().filter(b -> b.targetId.equals(target)).toList();
    }

    public int countByPlacer(UUID placer) {
        return (int) bounties.values().stream().filter(b -> b.placerId.equals(placer)).count();
    }

    public void add(Bounty bounty) {
        bounties.put(bounty.id, bounty);
        setDirty();
    }

    public void remove(UUID id) {
        if (bounties.remove(id) != null) setDirty();
    }

    /** Removes and returns bounties whose time ran out. */
    public List<Bounty> takeExpired() {
        long now = System.currentTimeMillis();
        List<Bounty> expired = bounties.values().stream().filter(b -> b.expiresAt <= now).toList();
        if (!expired.isEmpty()) {
            expired.forEach(b -> bounties.remove(b.id));
            setDirty();
        }
        return expired;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        bounties.values().forEach(b -> list.add(b.save()));
        tag.put("bounties", list);
        return tag;
    }

    public static BountyManager load(CompoundTag tag, HolderLookup.Provider registries) {
        BountyManager m = new BountyManager();
        for (Tag t : tag.getList("bounties", Tag.TAG_COMPOUND)) {
            Bounty b = Bounty.load((CompoundTag) t);
            m.bounties.put(b.id, b);
        }
        return m;
    }
}
