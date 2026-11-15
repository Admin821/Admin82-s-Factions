package com.admin82.factions.seam;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What has been seen with Seam Viewers, like a map mod's explored area: owner → dimension → chunk → the seam as it was
 * when last scanned. The owner is the player's faction, so every member's scans add to one shared map (also shown on
 * the faction table's territory map); players without a faction keep their own. Only scans update it, so a seam mined
 * out far away still shows as it was until someone scans it again.
 */
public class SeamMemory extends SavedData {

    private static final String DATA_NAME = "adminsfactions_seam_memory";

    private final Map<UUID, Map<String, Map<Long, SeamCell>>> seen = new HashMap<>();

    public static SeamMemory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SeamMemory::new, SeamMemory::load, null), DATA_NAME);
    }

    /** Whose map a player's scans go on: their faction's, or their own without one. */
    private static UUID owner(ServerPlayer player) {
        var faction = com.admin82.factions.faction.FactionManager.get(player.server).getFactionForPlayer(player.getUUID());
        return faction != null ? faction.getId() : player.getUUID();
    }

    private Map<Long, SeamCell> of(UUID owner, ServerPlayer player) {
        return seen.computeIfAbsent(owner, u -> new HashMap<>())
                .computeIfAbsent(player.level().dimension().location().toString(), d -> new HashMap<>());
    }

    /** The player's map; scans they made before joining a faction are moved onto the faction's the first time. */
    private Map<Long, SeamCell> of(ServerPlayer player) {
        UUID owner = owner(player);
        Map<Long, SeamCell> map = of(owner, player);
        if (!owner.equals(player.getUUID()) && seen.containsKey(player.getUUID())) {
            for (var dims : seen.remove(player.getUUID()).entrySet()) {
                Map<Long, SeamCell> into = seen.get(owner).computeIfAbsent(dims.getKey(), d -> new HashMap<>());
                dims.getValue().forEach((k, c) -> into.merge(k, c, (a, b) -> a.scannedAt() >= b.scannedAt() ? a : b));
            }
            setDirty();
        }
        return map;
    }

    /** Remembers a scan (stamped with the time). */
    public void remember(ServerPlayer player, List<SeamCell> cells) {
        Map<Long, SeamCell> mine = of(player);
        long now = System.currentTimeMillis();
        for (SeamCell c : cells) mine.put(c.key(), c.scanned(now));
        setDirty();
    }

    /** The chunks this player (or their faction) has scanned in a square of {@code size} around a chunk, in their dimension. */
    public List<SeamCell> around(ServerPlayer player, ChunkPos center, int size) {
        Map<Long, SeamCell> mine = of(player);
        List<SeamCell> out = new ArrayList<>();
        int half = size / 2;
        for (int dz = -half; dz <= half; dz++) {
            for (int dx = -half; dx <= half; dx++) {
                SeamCell c = mine.get(ChunkPos.asLong(center.x + dx, center.z + dz));
                if (c != null) out.add(c);
            }
        }
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag players = new ListTag();
        seen.forEach((player, dims) -> dims.forEach((dim, cells) -> {
            CompoundTag p = new CompoundTag();
            p.putUUID("player", player);
            p.putString("dim", dim);
            ListTag list = new ListTag();
            cells.values().forEach(c -> list.add(c.save()));
            p.put("cells", list);
            players.add(p);
        }));
        tag.put("players", players);
        return tag;
    }

    public static SeamMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        SeamMemory data = new SeamMemory();
        for (Tag t : tag.getList("players", Tag.TAG_COMPOUND)) {
            CompoundTag p = (CompoundTag) t;
            Map<Long, SeamCell> cells = data.seen.computeIfAbsent(p.getUUID("player"), u -> new HashMap<>())
                    .computeIfAbsent(p.getString("dim"), d -> new HashMap<>());
            for (Tag c : p.getList("cells", Tag.TAG_COMPOUND)) {
                SeamCell cell = SeamCell.load((CompoundTag) c);
                cells.put(cell.key(), cell);
            }
        }
        return data;
    }
}
