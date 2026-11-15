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
 * What each player has seen with their Seam Viewer, like a map mod's explored area: player → dimension → chunk →
 * the seam as it was when they last scanned it. Only their own scans update it, so a seam someone else mined out
 * far away still shows as it was until they go back and scan it.
 */
public class SeamMemory extends SavedData {

    private static final String DATA_NAME = "adminsfactions_seam_memory";

    private final Map<UUID, Map<String, Map<Long, SeamCell>>> seen = new HashMap<>();

    public static SeamMemory get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SeamMemory::new, SeamMemory::load, null), DATA_NAME);
    }

    private Map<Long, SeamCell> of(ServerPlayer player) {
        return seen.computeIfAbsent(player.getUUID(), u -> new HashMap<>())
                .computeIfAbsent(player.level().dimension().location().toString(), d -> new HashMap<>());
    }

    /** Remembers a scan (stamped with the time). */
    public void remember(ServerPlayer player, List<SeamCell> cells) {
        Map<Long, SeamCell> mine = of(player);
        long now = System.currentTimeMillis();
        for (SeamCell c : cells) mine.put(c.key(), c.scanned(now));
        setDirty();
    }

    /** The chunks this player has scanned in a square of {@code size} around a chunk, in their dimension. */
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
