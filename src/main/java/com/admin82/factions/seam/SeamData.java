package com.admin82.factions.seam;

import com.admin82.factions.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Some chunks have a seam (spread out, see {@link #generate}), worked out from the world seed so it never needs saving until something changes it
 * (mined, or edited by an admin). Also keeps track of which Seam Extractors sit on which seam.
 */
public class SeamData extends SavedData {

    private static final String DATA_NAME = "adminsfactions_seams";

    /** Seams that differ from what the seed gives: dimension → chunk → seam. */
    private final Map<String, Map<Long, Seam>> changed = new HashMap<>();
    /** Extractors: dimension → chunk → extractor position → owning faction. */
    private final Map<String, Map<Long, Map<Long, UUID>>> extractors = new HashMap<>();

    /** Ore lists per dimension, rebuilt when the blacklist changes. */
    private static final Map<String, List<String>> ORE_CACHE = new HashMap<>();
    private static int cachedBlacklist;

    public static SeamData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SeamData::new, SeamData::load, null), DATA_NAME);
    }

    private static String dim(ServerLevel level) {
        return level.dimension().location().toString();
    }

    // Seams

    /** The seam in a chunk. Don't change what this returns; use {@link #forMining} for that. */
    public Seam get(ServerLevel level, ChunkPos chunk) {
        Seam s = changed.getOrDefault(dim(level), Map.of()).get(chunk.toLong());
        return s != null ? s : generate(level, chunk);
    }

    /** The seam in a chunk, saved so changes to it last. */
    public Seam forMining(ServerLevel level, ChunkPos chunk) {
        return changed.computeIfAbsent(dim(level), d -> new HashMap<>())
                .computeIfAbsent(chunk.toLong(), c -> generate(level, chunk));
    }

    /** Replaces a chunk's seam (admins). */
    public void set(ServerLevel level, ChunkPos chunk, Seam seam) {
        changed.computeIfAbsent(dim(level), d -> new HashMap<>()).put(chunk.toLong(), seam);
        setDirty();
    }

    /** Puts a chunk's seam back to what the world seed gives. */
    public void reset(ServerLevel level, ChunkPos chunk) {
        Map<Long, Seam> m = changed.get(dim(level));
        if (m != null && m.remove(chunk.toLong()) != null) setDirty();
    }

    /**
     * The seam the world seed gives a chunk: a Random seeded with the world seed xor the chunk coordinates (x in the
     * high 32 bits, z in the low), which picks an ore and then how rich the seam is.
     */
    public static Seam generate(ServerLevel level, ChunkPos chunk) {
        if (!hasSeam(level, chunk)) return Seam.none();
        return generateAt(level, chunk);
    }

    /** The seam rolled for a chunk that has one. */
    private static Seam generateAt(ServerLevel level, ChunkPos chunk) {
        long seed = level.getSeed() ^ (((long) chunk.x << 32) | (chunk.z & 0xFFFFFFFFL));
        Random random = new Random(seed);
        List<String> ores = ores(level);
        if (ores.isEmpty()) return Seam.none();
        String ore = ores.get(random.nextInt(ores.size()));

        // overflowing, rich, middling, poor, depleted, endless
        int kind = pick(random, Config.SEAM_CHANCE);
        if (kind == 4 || kind < 0) return new Seam(ore, SeamTier.DEPLETED, false);
        if (kind == 5) {
            int speed = pick(random, Config.SEAM_ENDLESS_CHANCE);
            return new Seam(ore, speed < 0 ? SeamTier.POOR : SeamTier.values()[speed], true);
        }
        return new Seam(ore, SeamTier.values()[kind], false);
    }

    /**
     * Whether the world seed puts a seam in this chunk.
     * <p>
     * The world is split into squares {@code spacing} chunks across, and each square's seam sits somewhere in the
     * square's top-left {@code span} × {@code span} chunks. Two neighbouring seams are then between
     * {@code spacing - span + 1} and {@code spacing + span - 1} chunks apart, i.e. {@code spacing - span} to
     * {@code spacing + span - 2} empty chunks between them. Picking {@code span = (max - min) / 2 + 1} and
     * {@code spacing = min + span} makes that exactly min..max empty chunks (6..10 → 9-chunk squares, 3×3 spots).
     */
    public static boolean hasSeam(ServerLevel level, ChunkPos chunk) {
        int spacing = spacing();
        ChunkPos spot = spot(level, Math.floorDiv(chunk.x, spacing), Math.floorDiv(chunk.z, spacing), spacing);
        return spot != null && spot.equals(chunk);
    }

    /** Chunks across a spot of the grid where a seam can be. */
    private static int span() {
        int min = Math.max(0, Config.SEAM_MIN_GAP.get());
        int max = Math.max(min, Config.SEAM_MAX_GAP.get());
        return (max - min) / 2 + 1;
    }

    /** Chunks across each square of the grid (one seam per square). */
    private static int spacing() {
        return Math.max(1, Math.max(0, Config.SEAM_MIN_GAP.get()) + span());
    }

    /** The chunk of square (rx, rz) that has its seam, or null when the square has none. */
    private static ChunkPos spot(ServerLevel level, int rx, int rz, int spacing) {
        Random region = new Random(level.getSeed() ^ 0x5EA4_5EEDL ^ (rx * 341873128712L + rz * 132897987541L));
        boolean spawns = region.nextInt(100) < Config.SEAM_SPAWN_CHANCE.get();
        int span = Math.min(span(), spacing);
        int ox = region.nextInt(span), oz = region.nextInt(span);
        return spawns ? new ChunkPos(rx * spacing + ox, rz * spacing + oz) : null;
    }

    /**
     * Every seam (minable or depleted) in a rectangle of chunks, including admin and mining changes, up to
     * {@code max} of them. Walks the spacing squares rather than every chunk, so big areas are quick.
     */
    public List<SeamCell> seamsIn(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int max) {
        List<SeamCell> out = new java.util.ArrayList<>();
        Map<Long, Seam> mine = changed.getOrDefault(dim(level), Map.of());
        int spacing = spacing();
        if (!ores(level).isEmpty()) {
            for (int rz = Math.floorDiv(minZ, spacing); rz <= Math.floorDiv(maxZ, spacing) && out.size() < max; rz++) {
                for (int rx = Math.floorDiv(minX, spacing); rx <= Math.floorDiv(maxX, spacing) && out.size() < max; rx++) {
                    ChunkPos c = spot(level, rx, rz, spacing);
                    if (c == null || c.x < minX || c.x > maxX || c.z < minZ || c.z > maxZ || mine.containsKey(c.toLong())) continue;
                    Seam seam = generateAt(level, c);
                    if (seam.exists()) out.add(SeamCell.of(c, seam));
                }
            }
        }
        for (var e : mine.entrySet()) {
            if (out.size() >= max) break;
            ChunkPos c = new ChunkPos(e.getKey());
            if (c.x < minX || c.x > maxX || c.z < minZ || c.z > maxZ || !e.getValue().exists()) continue;
            out.add(SeamCell.of(c, e.getValue()));
        }
        return out;
    }

    /** Picks an index with the given chances (out of their total, normally 100). -1 if they're all 0. */
    private static int pick(Random random, net.neoforged.neoforge.common.ModConfigSpec.IntValue[] chances) {
        int total = 0;
        for (var c : chances) total += Math.max(0, c.get());
        if (total <= 0) return -1;
        int roll = random.nextInt(total) + 1; // 1..total
        for (int i = 0; i < chances.length; i++) {
            roll -= Math.max(0, chances[i].get());
            if (roll <= 0) return i;
        }
        return chances.length - 1;
    }

    public static List<String> ores(ServerLevel level) {
        int blacklist = Config.SEAM_ORE_BLACKLIST.get().hashCode();
        if (blacklist != cachedBlacklist) {
            ORE_CACHE.clear();
            cachedBlacklist = blacklist;
        }
        return ORE_CACHE.computeIfAbsent(dim(level), d -> SeamOres.forLevel(level));
    }

    /** Forgets cached ore lists (when tags reload). */
    public static void clearOreCache() {
        ORE_CACHE.clear();
    }

    // Extractors

    public void addExtractor(ServerLevel level, BlockPos pos, UUID faction) {
        extractors.computeIfAbsent(dim(level), d -> new HashMap<>())
                .computeIfAbsent(new ChunkPos(pos).toLong(), c -> new HashMap<>()).put(pos.asLong(), faction);
        setDirty();
    }

    public void removeExtractor(ServerLevel level, BlockPos pos) {
        Map<Long, Map<Long, UUID>> byChunk = extractors.get(dim(level));
        if (byChunk == null) return;
        long key = new ChunkPos(pos).toLong();
        Map<Long, UUID> here = byChunk.get(key);
        if (here != null && here.remove(pos.asLong()) != null) {
            if (here.isEmpty()) byChunk.remove(key);
            setDirty();
        }
    }

    /** How many extractors a faction has on a chunk's seam. */
    public int extractorsOn(ServerLevel level, ChunkPos chunk, UUID faction) {
        Map<Long, UUID> here = extractors.getOrDefault(dim(level), Map.of()).get(chunk.toLong());
        if (here == null) return 0;
        int n = 0;
        for (UUID f : here.values()) if (f.equals(faction)) n++;
        return n;
    }

    // Saving

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag seams = new ListTag();
        changed.forEach((dim, m) -> m.forEach((chunk, seam) -> {
            CompoundTag t = seam.save();
            t.putString("dim", dim);
            t.putLong("chunk", chunk);
            seams.add(t);
        }));
        tag.put("seams", seams);
        ListTag ext = new ListTag();
        extractors.forEach((dim, byChunk) -> byChunk.forEach((chunk, here) -> here.forEach((pos, faction) -> {
            CompoundTag t = new CompoundTag();
            t.putString("dim", dim);
            t.putLong("pos", pos);
            t.putUUID("faction", faction);
            ext.add(t);
        })));
        tag.put("extractors", ext);
        return tag;
    }

    public static SeamData load(CompoundTag tag, HolderLookup.Provider registries) {
        SeamData data = new SeamData();
        for (Tag t : tag.getList("seams", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            data.changed.computeIfAbsent(c.getString("dim"), d -> new HashMap<>()).put(c.getLong("chunk"), Seam.load(c));
        }
        for (Tag t : tag.getList("extractors", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            BlockPos pos = BlockPos.of(c.getLong("pos"));
            data.extractors.computeIfAbsent(c.getString("dim"), d -> new HashMap<>())
                    .computeIfAbsent(new ChunkPos(pos).toLong(), k -> new HashMap<>()).put(pos.asLong(), c.getUUID("faction"));
        }
        return data;
    }
}
