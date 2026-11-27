package com.admin82.factions.map;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class RegionStorage {
    private static final int MAGIC = 0x464D4150; // "FMAP"
    private static final byte VERSION = 1;
    private static final byte FLAG_HAS_HEIGHTMAP = 0x01;
    private static final int DEFLATE_LEVEL = Deflater.BEST_SPEED;

    private final Path dimensionDir;

    public RegionStorage(Path dimensionDir) {
        this.dimensionDir = dimensionDir;
    }

    public static long packRegionPos(int regionX, int regionZ) {
        return (((long) regionX) << 32) | (regionZ & 0xFFFFFFFFL);
    }

    public static int unpackRegionX(long packedRegion) {
        return (int) (packedRegion >> 32);
    }

    public static int unpackRegionZ(long packedRegion) {
        return (int) packedRegion;
    }

    public Path regionFile(int regionX, int regionZ) {
        return dimensionDir.resolve("r." + regionX + "." + regionZ + ".fmap");
    }

    /**
     * Convenience overload that overwrites region entries with only the provided chunks.
     */
    public void saveChunks(Map<Long, MapChunks.ChunkColorColumn> entries) throws IOException {
        saveChunks(entries, false);
    }

    /**
     * Groups the provided chunk entries by 32x32 region and atomically writes each region file.
     *
     * @param entries       map of packedChunkPos -> ChunkColorColumn snapshot
     * @param mergeExisting if true, loads existing entries from each affected region file first
     *                      and overlays the incoming chunks so subsets are preserved
     */
    public void saveChunks(Map<Long, MapChunks.ChunkColorColumn> entries, boolean mergeExisting) throws IOException {
        if (entries.isEmpty()) {
            return;
        }

        Map<Long, Map<Long, ChunkRecord>> byRegion = new HashMap<>();
        for (Map.Entry<Long, MapChunks.ChunkColorColumn> e : entries.entrySet()) {
            long packedChunk = e.getKey();
            int chunkX = MapChunks.unpackChunkX(packedChunk);
            int chunkZ = MapChunks.unpackChunkZ(packedChunk);
            int rx = chunkX >> 5;
            int rz = chunkZ >> 5;
            long packedRegion = packRegionPos(rx, rz);

            byRegion
                    .computeIfAbsent(packedRegion, k -> new HashMap<>())
                    .put(packedChunk, ChunkRecord.fromColumn(packedChunk, e.getValue()));
        }

        Files.createDirectories(dimensionDir);

        for (Map.Entry<Long, Map<Long, ChunkRecord>> regionEntry : byRegion.entrySet()) {
            int rx = unpackRegionX(regionEntry.getKey());
            int rz = unpackRegionZ(regionEntry.getKey());
            Map<Long, ChunkRecord> incoming = regionEntry.getValue();

            Map<Long, ChunkRecord> merged;
            if (mergeExisting) {
                merged = loadRegion(rx, rz);
                merged.putAll(incoming);
            } else {
                merged = incoming;
            }

            writeRegionAtomic(rx, rz, new ArrayList<>(merged.values()));
        }
    }

    /**
     * Loads all chunks stored in a single 32x32 region file.
     * Returns an empty map if the region file does not exist yet.
     */
    public Map<Long, ChunkRecord> loadRegion(int regionX, int regionZ) throws IOException {
        Path target = regionFile(regionX, regionZ);
        Map<Long, ChunkRecord> loaded = new HashMap<>();
        if (!Files.exists(target)) {
            return loaded;
        }

        try (InputStream rawIn = new BufferedInputStream(Files.newInputStream(target));
             InflaterInputStream infIn = new InflaterInputStream(rawIn);
             DataInputStream in = new DataInputStream(infIn)) {

            int magic = in.readInt();
            if (magic != MAGIC) {
                throw new IOException("Invalid FMAP magic in " + target + ": 0x" + Integer.toHexString(magic));
            }

            byte version = in.readByte();
            if (version != VERSION) {
                throw new IOException("Unsupported FMAP version " + version + " in " + target);
            }

            int chunkCount = in.readUnsignedShort();
            for (int i = 0; i < chunkCount; i++) {
                long packedChunk = in.readLong();
                byte flags = in.readByte();

                short[][] heightMap = null;
                if ((flags & FLAG_HAS_HEIGHTMAP) != 0) {
                    heightMap = new short[16][16];
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            heightMap[x][z] = in.readShort();
                        }
                    }
                }

                int sliceCount = in.readUnsignedShort();
                Int2ObjectOpenHashMap<int[][]> slices = new Int2ObjectOpenHashMap<>(Math.max(4, sliceCount));
                for (int s = 0; s < sliceCount; s++) {
                    int depthY = in.readInt();
                    int[][] colors = new int[16][16];
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            colors[x][z] = in.readInt();
                        }
                    }
                    slices.put(depthY, colors);
                }

                loaded.put(packedChunk, new ChunkRecord(packedChunk, heightMap, slices));
            }
        } catch (EOFException eof) {
            throw new IOException("Truncated FMAP region file: " + target, eof);
        }

        return loaded;
    }

    private void writeRegionAtomic(int regionX, int regionZ, List<ChunkRecord> records) throws IOException {
        Path target = regionFile(regionX, regionZ);
        Path temp = dimensionDir.resolve("r." + regionX + "." + regionZ + ".fmap.tmp");

        Deflater deflater = new Deflater(DEFLATE_LEVEL);
        try {
            try (OutputStream rawOut = new BufferedOutputStream(Files.newOutputStream(temp));
                 DeflaterOutputStream defOut = new DeflaterOutputStream(rawOut, deflater);
                 DataOutputStream out = new DataOutputStream(defOut)) {

                out.writeInt(MAGIC);
                out.writeByte(VERSION);
                out.writeShort(Math.min(records.size(), 1024));

                int written = 0;
                for (ChunkRecord rec : records) {
                    if (written++ >= 1024) {
                        break;
                    }
                    out.writeLong(rec.packedChunk);

                    byte flags = rec.heightMap != null ? FLAG_HAS_HEIGHTMAP : 0;
                    out.writeByte(flags);

                    if (rec.heightMap != null) {
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                out.writeShort(rec.heightMap[x][z]);
                            }
                        }
                    }

                    out.writeShort(rec.slices.size());
                    for (Int2ObjectMap.Entry<int[][]> sliceEntry : rec.slices.int2ObjectEntrySet()) {
                        out.writeInt(sliceEntry.getIntKey());
                        int[][] colors = sliceEntry.getValue();
                        for (int x = 0; x < 16; x++) {
                            for (int z = 0; z < 16; z++) {
                                out.writeInt(colors[x][z]);
                            }
                        }
                    }
                }

                out.flush();
                defOut.finish();
            }

            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            deflater.end();
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
        }
    }

    public record ChunkRecord(
            long packedChunk,
            short[][] heightMap,
            Int2ObjectOpenHashMap<int[][]> slices
    ) {
        public static ChunkRecord fromColumn(long packedChunk, MapChunks.ChunkColorColumn column) {
            Int2ObjectOpenHashMap<int[][]> copy = new Int2ObjectOpenHashMap<>(4);
            // Replace with a dedicated snapshot getter on ChunkColorColumn so you read the volatile map once.
            return new ChunkRecord(packedChunk, column.getHeightMap(), copy);
        }
    }
}