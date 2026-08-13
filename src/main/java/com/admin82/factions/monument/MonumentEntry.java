package com.admin82.factions.monument;

import com.admin82.factions.supplydrop.SupplyDropPool;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.LongTag;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MonumentEntry {
    public static final long DEFAULT_RESPAWN_TICKS = 20L * 60L * 30L;

    public final UUID id;
    public final BlockPos controllerPos;
    public final String dimension;
    public static final List<String> DEFAULT_LOOT_POOLS = List.of("Supply", "Ammo", "Gun");

    private final Map<BlockPos, String> crates = new HashMap<>();
    private final Map<String, SupplyDropPool> lootPools = new LinkedHashMap<>();
    private final Map<BlockPos, String> oreGenerators = new HashMap<>();
    private final Set<Long> designatedChunks = new HashSet<>();
    private String name;
    private int tier;
    private int radius;
    private long baseRespawnTicks;
    private double remainingRespawnTicks;
    private boolean legacyLootMigrated = true;

    public MonumentEntry(UUID id, String name, int tier, BlockPos controllerPos, String dimension) {
        this(id, name, tier, controllerPos, dimension, 24, DEFAULT_RESPAWN_TICKS, DEFAULT_RESPAWN_TICKS);
    }

    private MonumentEntry(UUID id, String name, int tier, BlockPos controllerPos, String dimension,
                          int radius, long baseRespawnTicks, double remainingRespawnTicks) {
        this.id = id;
        this.name = name;
        this.tier = tier;
        this.controllerPos = controllerPos.immutable();
        this.dimension = dimension;
        this.radius = radius;
        this.baseRespawnTicks = baseRespawnTicks;
        this.remainingRespawnTicks = remainingRespawnTicks;
        designatedChunks.add(controllerChunkKey());
        DEFAULT_LOOT_POOLS.forEach(this::createLootPool);
    }

    public String getName() { return name; }
    public int getTier() { return tier; }
    public int getRadius() { return radius; }
    public long getBaseRespawnTicks() { return baseRespawnTicks; }
    public double getRemainingRespawnTicks() { return remainingRespawnTicks; }
    public Map<BlockPos, String> getCrates() { return Collections.unmodifiableMap(crates); }
    public List<String> getLootPoolNames() { return lootPools.values().stream().map(SupplyDropPool::getName).toList(); }
    public SupplyDropPool getLootPool(String name) { return lootPools.get(normalizePoolName(name)); }
    public Map<BlockPos, String> getOreGenerators() { return Collections.unmodifiableMap(oreGenerators); }
    public Set<Long> getDesignatedChunks() { return Collections.unmodifiableSet(designatedChunks); }

    public void setName(String name) { this.name = name; }
    public void setTier(int tier) { this.tier = Math.clamp(tier, 1, 5); }
    public void setRadius(int radius) { this.radius = Math.clamp(radius, 4, 256); }
    public void setBaseRespawnTicks(long ticks) {
        baseRespawnTicks = Math.max(20L, ticks);
        remainingRespawnTicks = Math.min(remainingRespawnTicks, baseRespawnTicks);
    }
    public void setRemainingRespawnTicks(double ticks) { remainingRespawnTicks = Math.max(0.0, ticks); }
    public void resetRespawnTimer() { remainingRespawnTicks = baseRespawnTicks; }
    public void linkCrate(BlockPos pos, String poolName) {
        SupplyDropPool pool = getLootPool(poolName);
        if (pool != null) crates.put(pos.immutable(), pool.getName());
    }
    public void unlinkCrate(BlockPos pos) { crates.remove(pos); }
    public boolean createLootPool(String name) {
        if (name == null || name.isBlank() || name.length() > 32) return false;
        if (lootPools.size() >= 8) return false;
        String key = normalizePoolName(name);
        if (lootPools.containsKey(key)) return false;
        lootPools.put(key, new SupplyDropPool(name.trim()));
        return true;
    }
    public boolean deleteLootPool(String name) {
        if (lootPools.size() <= 1) return false;
        SupplyDropPool removed = lootPools.remove(normalizePoolName(name));
        if (removed == null) return false;
        String fallback = getLootPoolNames().getFirst();
        crates.replaceAll((pos, assigned) -> assigned.equalsIgnoreCase(removed.getName()) ? fallback : assigned);
        return true;
    }
    public String nextLootPool(String current) {
        List<String> names = getLootPoolNames();
        if (names.isEmpty()) return "Supply";
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(current)) return names.get((i + 1) % names.size());
        }
        return names.getFirst();
    }
    public boolean needsLegacyLootMigration() { return !legacyLootMigrated; }
    public void markLegacyLootMigrated() { legacyLootMigrated = true; }
    public void linkOreGenerator(BlockPos pos) { oreGenerators.put(pos.immutable(), "minecraft:cobblestone"); }
    public void setGeneratorOre(BlockPos pos, String blockId) {
        if (oreGenerators.containsKey(pos)) oreGenerators.put(pos.immutable(), blockId);
    }
    public void unlinkOreGenerator(BlockPos pos) { oreGenerators.remove(pos); }
    public boolean hasOreGenerator(BlockPos pos) { return oreGenerators.containsKey(pos); }
    public boolean hasChunk(int chunkX, int chunkZ) { return designatedChunks.contains(ChunkPos.asLong(chunkX, chunkZ)); }
    public boolean toggleChunk(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        if (key == controllerChunkKey()) return false;
        if (!designatedChunks.remove(key)) designatedChunks.add(key);
        return true;
    }

    private long controllerChunkKey() {
        return ChunkPos.asLong(SectionPos.blockToSectionCoord(controllerPos.getX()),
                SectionPos.blockToSectionCoord(controllerPos.getZ()));
    }

    public boolean contains(BlockPos pos, String dimension) {
        return this.dimension.equals(dimension)
                && hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putInt("tier", tier);
        tag.putLong("controllerPos", controllerPos.asLong());
        tag.putString("dimension", dimension);
        tag.putInt("radius", radius);
        tag.putLong("baseRespawnTicks", baseRespawnTicks);
        tag.putDouble("remainingRespawnTicks", remainingRespawnTicks);
        ListTag crateList = new ListTag();
        crates.forEach((pos, poolName) -> {
            CompoundTag crateTag = new CompoundTag();
            crateTag.putLong("pos", pos.asLong());
            crateTag.putString("pool", poolName);
            crateList.add(crateTag);
        });
        tag.put("crates", crateList);
        ListTag poolList = new ListTag();
        lootPools.values().forEach(pool -> poolList.add(pool.save(registries)));
        tag.put("lootPools", poolList);
        tag.putBoolean("legacyLootMigrated", legacyLootMigrated);
        ListTag generatorList = new ListTag();
        oreGenerators.forEach((pos, blockId) -> {
            CompoundTag generatorTag = new CompoundTag();
            generatorTag.putLong("pos", pos.asLong());
            generatorTag.putString("block", blockId);
            generatorList.add(generatorTag);
        });
        tag.put("oreGenerators", generatorList);
        ListTag chunkList = new ListTag();
        designatedChunks.forEach(key -> chunkList.add(LongTag.valueOf(key)));
        tag.put("designatedChunks", chunkList);
        return tag;
    }

    public static MonumentEntry load(CompoundTag tag, HolderLookup.Provider registries) {
        MonumentEntry entry = new MonumentEntry(
                tag.getUUID("id"), tag.getString("name"), tag.getInt("tier"),
                BlockPos.of(tag.getLong("controllerPos")), tag.getString("dimension"),
                tag.getInt("radius"), tag.getLong("baseRespawnTicks"), tag.getDouble("remainingRespawnTicks"));
        ListTag crateList = tag.getList("crates", Tag.TAG_COMPOUND);
        for (int i = 0; i < crateList.size(); i++) {
            CompoundTag crateTag = crateList.getCompound(i);
            String poolName = crateTag.contains("pool", Tag.TAG_STRING)
                    ? crateTag.getString("pool") : legacyPoolName(crateTag.getString("type"));
            entry.crates.put(BlockPos.of(crateTag.getLong("pos")), poolName);
        }
        if (tag.contains("lootPools", Tag.TAG_LIST)) {
            entry.lootPools.clear();
            ListTag poolList = tag.getList("lootPools", Tag.TAG_COMPOUND);
            for (int i = 0; i < poolList.size(); i++) {
                SupplyDropPool pool = SupplyDropPool.load(poolList.getCompound(i), registries);
                entry.lootPools.put(normalizePoolName(pool.getName()), pool);
            }
            if (entry.lootPools.isEmpty()) DEFAULT_LOOT_POOLS.forEach(entry::createLootPool);
        }
        entry.legacyLootMigrated = tag.contains("legacyLootMigrated", Tag.TAG_BYTE)
            ? tag.getBoolean("legacyLootMigrated") : tag.contains("lootPools", Tag.TAG_LIST);
        ListTag generatorList = tag.getList("oreGenerators", Tag.TAG_COMPOUND);
        for (int i = 0; i < generatorList.size(); i++) {
            CompoundTag generatorTag = generatorList.getCompound(i);
            entry.oreGenerators.put(BlockPos.of(generatorTag.getLong("pos")), generatorTag.getString("block"));
        }
        if (tag.contains("designatedChunks", Tag.TAG_LIST)) {
            entry.designatedChunks.clear();
            ListTag chunkList = tag.getList("designatedChunks", Tag.TAG_LONG);
            for (int i = 0; i < chunkList.size(); i++) {
                entry.designatedChunks.add(((LongTag) chunkList.get(i)).getAsLong());
            }
            entry.designatedChunks.add(entry.controllerChunkKey());
        }
        return entry;
    }

    private static String normalizePoolName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String legacyPoolName(String type) {
        if (type == null || type.isBlank()) return "Supply";
        String lower = type.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}