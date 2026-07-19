package com.admin82.factions.outpost;

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
 * Persistent world data for all placed Outposts.
 * Saved under "adminsfactions_outposts".
 */
public class OutpostData extends SavedData {

    private static final String DATA_NAME = "adminsfactions_outposts";

    // ── Data ──────────────────────────────────────────────────────────────────
    private final Map<UUID, OutpostEntry>   outposts         = new HashMap<>();
    /** Pending placement positions waiting for player confirmation: playerId → position. */
    private final Map<UUID, BlockPos>       pendingPosMap    = new HashMap<>();
    private final Map<UUID, String>         pendingDimMap    = new HashMap<>();
    /** Per-player custom war spawn: playerId → spawn position. Cleared when war ends. */
    private final Map<UUID, BlockPos>       warSpawnPos      = new HashMap<>();
    private final Map<UUID, String>         warSpawnDim      = new HashMap<>();
    /** Per-faction outpost placement cooldown: factionId → epoch-ms when cooldown expires (1h after losing outpost in war). */
    private final Map<UUID, Long>           outpostCooldowns = new HashMap<>();

    // ── Static access ─────────────────────────────────────────────────────────

    public static OutpostData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(OutpostData::new, OutpostData::load, null),
                DATA_NAME);
    }

    // ── Outpost CRUD ──────────────────────────────────────────────────────────

    public void addOutpost(OutpostEntry entry) {
        outposts.put(entry.id, entry);
        setDirty();
    }

    public void removeOutpost(UUID id) {
        outposts.remove(id);
        setDirty();
    }

    public Collection<OutpostEntry> getAllOutposts() {
        return Collections.unmodifiableCollection(outposts.values());
    }

    @Nullable
    public OutpostEntry getOutpost(UUID id) { return outposts.get(id); }

    @Nullable
    public OutpostEntry getOutpostAtPos(BlockPos pos, String dim) {
        for (OutpostEntry e : outposts.values()) {
            if (e.managerPos.equals(pos) && e.dimension.equals(dim)) return e;
        }
        return null;
    }

    public List<OutpostEntry> getOutpostsForFaction(UUID factionId) {
        List<OutpostEntry> list = new ArrayList<>();
        for (OutpostEntry e : outposts.values()) {
            if (factionId.equals(e.ownerFactionId)) list.add(e);
        }
        return list;
    }

    // ── Pending placement ─────────────────────────────────────────────────────

    public void setPending(UUID playerId, BlockPos pos, String dim) {
        pendingPosMap.put(playerId, pos);
        pendingDimMap.put(playerId, dim);
    }

    @Nullable public BlockPos  getPendingPos(UUID playerId) { return pendingPosMap.get(playerId); }
    @Nullable public String    getPendingDim(UUID playerId) { return pendingDimMap.get(playerId); }

    public void clearPending(UUID playerId) {
        pendingPosMap.remove(playerId);
        pendingDimMap.remove(playerId);
    }

    // ── War spawn ─────────────────────────────────────────────────────────────

    public void setWarSpawn(UUID playerId, BlockPos pos, String dim) {
        warSpawnPos.put(playerId, pos);
        warSpawnDim.put(playerId, dim);
        setDirty();
    }

    @Nullable public BlockPos getWarSpawnPos(UUID playerId) { return warSpawnPos.get(playerId); }
    @Nullable public String   getWarSpawnDim(UUID playerId) { return warSpawnDim.get(playerId); }

    public void clearWarSpawn(UUID playerId) {
        warSpawnPos.remove(playerId);
        warSpawnDim.remove(playerId);
        setDirty();
    }

    /** Returns epoch-ms when the faction's outpost cooldown expires (0 = no cooldown). */
    public long getOutpostCooldown(UUID factionId) {
        return outpostCooldowns.getOrDefault(factionId, 0L);
    }

    public void setOutpostCooldown(UUID factionId, long expiresAt) {
        outpostCooldowns.put(factionId, expiresAt);
        setDirty();
    }

    // ── SavedData ─────────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        outposts.values().forEach(e -> list.add(e.save()));
        tag.put("outposts", list);

        // War spawns
        ListTag spawnList = new ListTag();
        for (Map.Entry<UUID, BlockPos> entry : warSpawnPos.entrySet()) {
            CompoundTag s = new CompoundTag();
            s.putUUID("playerId", entry.getKey());
            s.putInt("x", entry.getValue().getX());
            s.putInt("y", entry.getValue().getY());
            s.putInt("z", entry.getValue().getZ());
            s.putString("dim", warSpawnDim.getOrDefault(entry.getKey(), "minecraft:overworld"));
            spawnList.add(s);
        }
        tag.put("warSpawns", spawnList);

        // Placement cooldowns
        CompoundTag cooldowns = new CompoundTag();
        outpostCooldowns.forEach((id, exp) -> cooldowns.putLong(id.toString(), exp));
        tag.put("outpostCooldowns", cooldowns);

        return tag;
    }

    public static OutpostData load(CompoundTag tag, HolderLookup.Provider registries) {
        OutpostData data = new OutpostData();
        ListTag list = tag.getList("outposts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            OutpostEntry e = OutpostEntry.load(list.getCompound(i));
            data.outposts.put(e.id, e);
        }
        ListTag spawnList = tag.getList("warSpawns", Tag.TAG_COMPOUND);
        for (int i = 0; i < spawnList.size(); i++) {
            CompoundTag s = spawnList.getCompound(i);
            UUID pid = s.getUUID("playerId");
            data.warSpawnPos.put(pid, new BlockPos(s.getInt("x"), s.getInt("y"), s.getInt("z")));
            data.warSpawnDim.put(pid, s.getString("dim"));
        }
        if (tag.contains("outpostCooldowns")) {
            CompoundTag cooldowns = tag.getCompound("outpostCooldowns");
            long now = System.currentTimeMillis();
            for (String key : cooldowns.getAllKeys()) {
                long exp = cooldowns.getLong(key);
                if (exp > now) { // discard already-expired entries
                    try { data.outpostCooldowns.put(UUID.fromString(key), exp); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        }
        return data;
    }
}
