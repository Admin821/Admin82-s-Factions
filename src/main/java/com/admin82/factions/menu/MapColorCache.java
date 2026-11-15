package com.admin82.factions.menu;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the map colour of each block column, so dragging a terrain map around only works out the colours of
 * chunks it hasn't drawn recently instead of every pixel of the map each time it moves (which froze the game).
 * Colours are kept for {@link #TTL_MS} so the map still catches up with changes to the world.
 */
public final class MapColorCache {

    /** Works out one block column's colour (the expensive part). */
    public interface Sampler {
        int color(Level level, int worldX, int worldZ);
    }

    private static final long TTL_MS = 30_000;
    private static final int MAX_CHUNKS = 4096;
    private static final int UNKNOWN = -1;

    private record Entry(int[] colors, long made) {}

    private final Sampler sampler;
    private final Map<Long, Entry> chunks = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Entry> eldest) {
            return size() > MAX_CHUNKS;
        }
    };
    private Level level;

    public MapColorCache(Sampler sampler) {
        this.sampler = sampler;
    }

    /** The colour of the block column at x, z (only call it for loaded chunks). */
    public int color(Level level, int worldX, int worldZ) {
        if (level != this.level) {
            chunks.clear();
            this.level = level;
        }
        long key = ChunkPos.asLong(worldX >> 4, worldZ >> 4);
        long now = System.currentTimeMillis();
        Entry entry = chunks.get(key);
        if (entry == null || now - entry.made > TTL_MS) {
            int[] colors = new int[256];
            Arrays.fill(colors, UNKNOWN);
            entry = new Entry(colors, now);
            chunks.put(key, entry);
        }
        int i = ((worldZ & 15) << 4) | (worldX & 15);
        int c = entry.colors[i];
        if (c == UNKNOWN) {
            c = sampler.color(level, worldX, worldZ) & 0xFFFFFF;
            entry.colors[i] = c;
        }
        return c;
    }

    /**
     * Limits how often a map redraws while it's dragged: at most every {@link #GAP_NS}; a move in between is
     * drawn by the next {@link #flush} (call that from the map's TICK listener).
     */
    public static final class Throttle {
        private static final long GAP_NS = 40_000_000L;
        private long last;
        private boolean pending;

        public void request(Runnable redraw) {
            long now = System.nanoTime();
            if (now - last >= GAP_NS) {
                last = now;
                pending = false;
                redraw.run();
            } else {
                pending = true;
            }
        }

        public void flush(Runnable redraw) {
            if (pending) request(redraw);
        }
    }
}
