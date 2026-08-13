package com.admin82.factions.monument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class MonumentData extends SavedData {
    private static final String DATA_NAME = "adminsfactions_monuments";
    private final Map<UUID, MonumentEntry> monuments = new LinkedHashMap<>();

    public static MonumentData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MonumentData::new, MonumentData::load, null), DATA_NAME);
    }

    public Collection<MonumentEntry> getAll() {
        return Collections.unmodifiableCollection(monuments.values());
    }

    public void add(MonumentEntry entry) {
        monuments.put(entry.id, entry);
        setDirty();
    }

    public void remove(UUID id) {
        monuments.remove(id);
        setDirty();
    }

    @Nullable
    public MonumentEntry get(UUID id) { return monuments.get(id); }

    @Nullable
    public MonumentEntry getByName(String name) {
        return monuments.values().stream().filter(entry -> entry.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    @Nullable
    public MonumentEntry getAt(BlockPos pos, String dimension) {
        return monuments.values().stream()
                .filter(entry -> entry.contains(pos, dimension))
                .min((first, second) -> Integer.compare(first.getRadius(), second.getRadius()))
                .orElse(null);
    }

    @Nullable
    public MonumentEntry getByController(BlockPos pos, String dimension) {
        return monuments.values().stream()
                .filter(entry -> entry.dimension.equals(dimension) && entry.controllerPos.equals(pos))
                .findFirst().orElse(null);
    }

    @Nullable
    public MonumentEntry getByChunk(int chunkX, int chunkZ, String dimension) {
        return monuments.values().stream()
                .filter(entry -> entry.dimension.equals(dimension) && entry.hasChunk(chunkX, chunkZ))
                .findFirst().orElse(null);
    }

    public void changed() { setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        monuments.values().forEach(entry -> list.add(entry.save(registries)));
        tag.put("monuments", list);
        return tag;
    }

    public static MonumentData load(CompoundTag tag, HolderLookup.Provider registries) {
        MonumentData data = new MonumentData();
        ListTag list = tag.getList("monuments", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            MonumentEntry entry = MonumentEntry.load(list.getCompound(i), registries);
            data.monuments.put(entry.id, entry);
        }
        return data;
    }
}