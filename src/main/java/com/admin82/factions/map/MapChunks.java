package com.admin82.factions.map;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

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

    public static void putSlice(int chunkX, int chunkZ, int depthY, int[][] colors16x16) {
        long key = packChunkPos(chunkX, chunkZ);
        ChunkColorColumn col = chunks.computeIfAbsent(key, k -> new ChunkColorColumn());
        col.putSlice(depthY, colors16x16);
    }

    public static void putHeightMap(int chunkX, int chunkZ, short[][] map){
        long key = packChunkPos(chunkX, chunkZ);
        ChunkColorColumn col = chunks.computeIfAbsent(key, k -> new ChunkColorColumn());
        col.putHightMap(map);
    }

    public static void removeSlice(int chunkX, int chunkZ, int depthY) {
        ChunkColorColumn col = chunks.get(packChunkPos(chunkX, chunkZ));
        if (col != null) {
            col.removeSlice(depthY);
        }
    }

    public static void unloadChunk(int chunkX, int chunkZ) {
        chunks.remove(packChunkPos(chunkX, chunkZ));
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

        public void putHightMap(short[][] map){
            heightMap = map;
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
    }


}

