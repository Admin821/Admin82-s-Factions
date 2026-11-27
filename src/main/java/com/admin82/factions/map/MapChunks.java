package com.admin82.factions.map;

import com.admin82.factions.AdminsFactions;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public final class MapChunks {
    private static final ConcurrentHashMap<Long, ChunkColorColumn> chunks = new ConcurrentHashMap<>(2048);

    public static long packChunkPos(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int[] unPackChunkPos(long packedChunk){
        int[] ret = new int[2];
        ret[0] = (int) (packedChunk >> 32);
        ret[1] = (int) packedChunk;
        return ret;
    }

    public static int unpackChunkX(long packed){
        return (int) (packed >> 32);
    }

    public static int unpackChunkZ(long packed){
        return (int) (packed);
    }

    public static int[][] getSlice(int chunkX, int chunkZ, int depthY) {
        ChunkColorColumn col = chunks.get(packChunkPos(chunkX, chunkZ));
        return (col != null) ? col.getSlice(depthY) : null;
    }

    public static boolean hasSlice(int chunkX, int chunkZ, int depthY){
        return chunks.containsKey(packChunkPos(chunkX, chunkZ));
    }

    public static void putSlice(int chunkX, int chunkZ, int depthY, int[][] colors16x16) {
        long key = packChunkPos(chunkX, chunkZ);
        ChunkColorColumn col = chunks.computeIfAbsent(key, k -> new ChunkColorColumn());
        col.putSlice(depthY, colors16x16);
    }

    public static void putHeightMap(int chunkX, int chunkZ, short[][] map){
        long key = packChunkPos(chunkX, chunkZ);
        ChunkColorColumn col = chunks.computeIfAbsent(key, k -> new ChunkColorColumn());
        col.putHeightMap(map);
    }

    public static void removeSlice(int chunkX, int chunkZ, int depthY) {
        ChunkColorColumn col = chunks.get(packChunkPos(chunkX, chunkZ));
        if (col != null) {
            col.removeSlice(depthY);
        }
    }

    public static void loadChunk(int chunkX, int chunkZ, RegionStorage regionStorage){
        try {
            Map<Long, RegionStorage.ChunkRecord> records = regionStorage.loadRegion(chunkX, chunkZ);
            putAllFromRecords(records);
        } catch (IOException e) {
            AdminsFactions.LOGGER.debug("Map Chunk data load failed, map data will not be loaded!\n", e);
        }
    }

    public static void unloadChunk(int chunkX, int chunkZ, RegionStorage regionStorage) {
        try {
            regionStorage.saveChunks(chunks);
        } catch (IOException e){
            AdminsFactions.LOGGER.debug("Map Chunk data save failed, map data will be lost!\n", e);
        }
        chunks.remove(packChunkPos(chunkX, chunkZ));
    }

    public static void putAllFromRecords(Map<Long, RegionStorage.ChunkRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, RegionStorage.ChunkRecord> entry : records.entrySet()) {
            long packedChunk = entry.getKey();
            RegionStorage.ChunkRecord record = entry.getValue();
            if (record == null) {
                continue;
            }
            ChunkColorColumn col = chunks.computeIfAbsent(packedChunk, k -> new ChunkColorColumn());
            if (record.heightMap() != null) {
                col.putHeightMap(record.heightMap());
            }
            if (record.slices() != null && !record.slices().isEmpty()) {
                for (Int2ObjectMap.Entry<int[][]> sliceEntry : record.slices().int2ObjectEntrySet()) {
                    col.putSlice(sliceEntry.getIntKey(), sliceEntry.getValue());
                }
            }
        }
    }

    public static void clear() {
        chunks.clear();
    }

    public static Map<Long, ChunkColorColumn> snapshotAllColumns() {
        return Map.copyOf(chunks);
    }

    public static final class ChunkColorColumn {
        private static final AtomicReferenceFieldUpdater<ChunkColorColumn, Int2ObjectOpenHashMap> UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(ChunkColorColumn.class, Int2ObjectOpenHashMap.class, "slices");

        private volatile Int2ObjectOpenHashMap<int[][]> slices = new Int2ObjectOpenHashMap<>(8, 0.5f);

        private volatile short[][] heightMap;

        public int[][] getSlice(int depthY) {
            return slices.get(depthY);
        }

        @SuppressWarnings("unchecked")
        public void putSlice(int depthY, int[][] colors16x16) {
            while (true) {
                Int2ObjectOpenHashMap<int[][]> current = this.slices;
                Int2ObjectOpenHashMap<int[][]> next = current.clone();
                next.put(depthY, colors16x16);
                if (UPDATER.compareAndSet(this, current, next)) {
                    return;
                }
            }
        }

        public void putHeightMap(short[][] map){
            heightMap = map;
        }

        public short[][] getHeightMap(){
            return heightMap;
        }

        @SuppressWarnings("unchecked")
        public void removeSlice(int depthY) {
            while (true) {
                Int2ObjectOpenHashMap<int[][]> current = this.slices;
                if (!current.containsKey(depthY)) return;
                Int2ObjectOpenHashMap<int[][]> next = current.clone();
                next.remove(depthY);
                if (UPDATER.compareAndSet(this, current, next)) {
                    return;
                }
            }
        }

        @SuppressWarnings("unchecked")
        public Int2ObjectOpenHashMap<int[][]> snapshotSlices() {
            Int2ObjectOpenHashMap<int[][]> current = this.slices;
            return current.clone();
        }

    }


}

