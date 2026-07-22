package com.admin82.factions.supplydrop;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public class SupplyDropData extends SavedData {
    private static final String DATA_NAME = "adminsfactions_supply_drops";

    private final Map<String, SupplyDropPool> pools = new LinkedHashMap<>();

    public static SupplyDropData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SupplyDropData::new, SupplyDropData::load, null),
                DATA_NAME
        );
    }

    public Collection<SupplyDropPool> getPools() {
        return java.util.Collections.unmodifiableCollection(pools.values());
    }

    public java.util.List<String> getPoolNames() {
        return new ArrayList<>(pools.keySet());
    }

    @Nullable
    public SupplyDropPool getPool(String name) {
        if (name == null) return null;
        return pools.get(normalize(name));
    }

    public boolean createPool(String name) {
        if (!isValidName(name)) return false;
        String key = normalize(name);
        if (pools.containsKey(key)) return false;
        pools.put(key, new SupplyDropPool(name.trim()));
        setDirty();
        return true;
    }

    public boolean deletePool(String name) {
        SupplyDropPool removed = pools.remove(normalize(name));
        if (removed != null) setDirty();
        return removed != null;
    }

    public void savePoolSlot(String poolName, int slotIndex, ItemStack stack) {
        SupplyDropPool pool = getPool(poolName);
        if (pool == null) return;
        pool.setSlot(slotIndex, stack);
        setDirty();
    }

    public void savePoolSlotSettings(String poolName, int slotIndex, int minCount, int maxCount, int rarityLevel) {
        SupplyDropPool pool = getPool(poolName);
        if (pool == null) return;
        pool.setGenerationSettings(slotIndex, minCount, maxCount, rarityLevel);
        setDirty();
    }

    public static boolean isValidName(String name) {
        return name != null && !name.isBlank() && name.length() <= 32;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (SupplyDropPool pool : pools.values()) {
            list.add(pool.save(registries));
        }
        tag.put("Pools", list);
        return tag;
    }

    public static SupplyDropData load(CompoundTag tag, HolderLookup.Provider registries) {
        SupplyDropData data = new SupplyDropData();
        ListTag list = tag.getList("Pools", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            SupplyDropPool pool = SupplyDropPool.load(list.getCompound(i), registries);
            data.pools.put(normalize(pool.getName()), pool);
        }
        return data;
    }
}