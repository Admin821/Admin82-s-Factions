package com.admin82.factions.map;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public class MapChunks {
    private final ConcurrentHashMap<Long, ChunkColorColumn> chunks = new ConcurrentHashMap<>(2048);

    public static long packChunkPos(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public int[][] getSlice(int chunkX, int chunkZ, int depthY) {
        ChunkColorColumn col = chunks.get(packChunkPos(chunkX, chunkZ));
        return (col != null) ? col.getSlice(depthY) : null;
    }

    public void putSlice(int chunkX, int chunkZ, int depthY, int[][] colors16x16) {
        long key = packChunkPos(chunkX, chunkZ);
        ChunkColorColumn col = chunks.computeIfAbsent(key, k -> new ChunkColorColumn());
        col.putSlice(depthY, colors16x16);
    }

    public void removeSlice(int chunkX, int chunkZ, int depthY) {
        ChunkColorColumn col = chunks.get(packChunkPos(chunkX, chunkZ));
        if (col != null) {
            col.removeSlice(depthY);
        }
    }

    public void unloadChunk(int chunkX, int chunkZ) {
        chunks.remove(packChunkPos(chunkX, chunkZ));
    }


    public static final class ChunkColorColumn {
        private static final AtomicReferenceFieldUpdater<ChunkColorColumn, Int2ObjectOpenHashMap> UPDATER =
                AtomicReferenceFieldUpdater.newUpdater(ChunkColorColumn.class, Int2ObjectOpenHashMap.class, "slices");

        private volatile Int2ObjectOpenHashMap<int[][]> slices = new Int2ObjectOpenHashMap<>(8, 0.5f);

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

