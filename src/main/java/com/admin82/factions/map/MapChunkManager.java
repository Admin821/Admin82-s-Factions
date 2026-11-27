package com.admin82.factions.map;

import com.admin82.factions.AdminsFactions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.telemetry.events.WorldLoadEvent;
import net.minecraft.client.telemetry.events.WorldUnloadEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@EventBusSubscriber(modid = AdminsFactions.MODID)
public class MapChunkManager {

    private static final int WORKER_THREADS = 4;
    private static final int QUEUE_CAPACITY = 8192;

    private static RegionStorage regionStorage;

    private static volatile ResourceLocation activeDimension = null;
    private static volatile Level activeLevel = null;
    private static final Set<Long> loadingRegions = ConcurrentHashMap.newKeySet();

    private static final ThreadPoolExecutor MAP_EXECUTOR = new ThreadPoolExecutor(
            WORKER_THREADS,
            WORKER_THREADS,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new ThreadFactory() {
                private final AtomicInteger idx = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "AdminsFactions-MapWorker-" + idx.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            }
    );

    private static final ExecutorService IO_EXECUTOR = Executors.newSingleThreadExecutor(
            namedDaemonFactory("AdminsFactions-MapIO-")
    );

    private static final ConcurrentHashMap<Long, Byte> ageMap = new ConcurrentHashMap<>();
    private static volatile byte iter = 1;

    public void run(Level level){
        if (iter == 0){
            iter = 1;
        }
        final byte currentIter = iter;
        ageMap.forEach((Long k, Byte v) -> {
            if (Byte.compareUnsigned(currentIter, v) >= 0 || v == 0){
                int x = MapChunks.unpackChunkX(k);
                int z = MapChunks.unpackChunkZ(k);
                updateSurface(x, z, level, currentIter);
            }
        });

        iter = (byte) (currentIter + 1);
    }

    @SubscribeEvent
    public static void newChunkEvent(ChunkEvent.Load event){
        if (!event.getLevel().isClientSide())
            return;

        final int chunkX = event.getChunk().getPos().x;
        final int chunkZ = event.getChunk().getPos().z;
        final long packedPos = MapChunks.packChunkPos(chunkX, chunkZ);
        final Level level = event.getChunk().getLevel();
        final byte currentIter = iter;

        try {
            MAP_EXECUTOR.execute(() -> {
                byte chunkAge = ageMap.computeIfAbsent(packedPos, k -> (byte) 0);
                if (chunkAge == 0 || Byte.compareUnsigned(currentIter, chunkAge) >= 0) {
                    updateSurface(chunkX, chunkZ, level, currentIter);
                }
            });
        } catch (RejectedExecutionException e){
            ageMap.put(packedPos, (byte) 0);
        }
        //ChunkPos chunkPos = event.getChunk().getPos();
        //byte chunkAge = ageMap.computeIfAbsent(MapChunks.packChunkPos(chunkPos.x, chunkPos.z), k -> {return (byte)0;});
        //if (chunkAge == 0 || Byte.compareUnsigned(iter, chunkAge) >= 0){
        //    updateSurface(chunkPos.x, chunkPos.z, event.getChunk().getLevel());
        //}
    }

    @SubscribeEvent
    public static void chunkUnloadEvent(ChunkEvent.Unload event){
        if (!event.getLevel().isClientSide())
            return;
        final int chunkX = event.getChunk().getPos().x;
        final int chunkZ = event.getChunk().getPos().z;
        final long packedPos = MapChunks.packChunkPos(chunkX, chunkZ);
        if (ageMap.computeIfAbsent(packedPos, k -> (byte) 0) != 0) {
            ageMap.put(packedPos, (byte) 120);
        }
    }

    @SubscribeEvent
    public static void chunkTickEvent(ClientTickEvent.Post event){
        Level level = Minecraft.getInstance().level;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if(iter == 120){
            byte tmp = iter;
            iter = (byte) (tmp + 1);
        }
        int renderDistance = mc.options.getEffectiveRenderDistance();

        final byte currentIter = iter;
        if (player != null) {
            int x = player.getBlockX();
            int z = player.getBlockZ();
            int startX = (x >> 4) - renderDistance;
            int startZ = (z >> 4) - renderDistance;
            for (int xi = startX; xi < startX + (renderDistance * 2); xi++){
                for (int zi = startZ; zi < startZ + (renderDistance * 2); zi++) {
                    byte v = ageMap.computeIfAbsent(MapChunks.packChunkPos(xi, zi), (k) -> (byte) 0);
                    if (level.getChunkSource().hasChunk(xi, zi) && (Math.abs(Byte.compareUnsigned(v, currentIter)) >= 2 || v == 0)) {
                        final int cx = xi;
                        final int cz = zi;
                        try {
                            MAP_EXECUTOR.execute(() -> updateSurface(cx, cz, level, currentIter));
                        } catch (RejectedExecutionException ignored) {

                        }
                    }
                }
            }
        }
        iter = (byte) (currentIter + 1);
    }

    @SubscribeEvent
    public static void playerTickEvent(PlayerTickEvent.Post event){
        Player player = event.getEntity();
        if(!player.level().isClientSide()){
            return;
        }
        Vec3 position = player.position();
        double px = position.x;
        double pz = position.z;
        if(MapHud.SQUARE_TEXTURE != null){
            MapHud.SQUARE_TEXTURE.clear();
            int x = ((int) position.x) >> 4;
            int z = ((int) position.z) >> 4;
            for(int xi = 0; xi < 32; xi++){
                for(int zi = 0; zi < 32; zi++){
                    int cx = xi + x - 16;
                    int cz = zi + z - 16;
                    int[][] colors = getSlice(cx, cz, Integer.MAX_VALUE);
                    if(colors != null) {
                        for (int xc = 0; xc < 16; xc++) {
                            for (int zc = 0; zc < 16; zc++) {
                                MapHud.SQUARE_TEXTURE.setPixel((xi << 4) + xc, (zi << 4) + zc, colors[xc][zc]);
                            }
                        }
                    }
                }
            }
            int chunkX = Mth.floor(px) >> 4;
            int chunkZ = Mth.floor(pz) >> 4;
            float subX = (float) (px - (chunkX << 4)) + 8;
            float subZ = (float) (pz - (chunkZ << 4)) + 8;
            MapHud.SQUARE_TEXTURE.setSubPixelOffset(subX, subZ);
            MapHud.SQUARE_TEXTURE.upload();
        }
    }

    public static int[][] getSlice(int x, int z, int depth){
        if(!MapChunks.hasSlice(x, z, depth)){
            requestRegionLoad(x >> 5, z >> 5);
        }
        return MapChunks.getSlice(x, z, depth);
    }

    @SubscribeEvent
    public static void onClientLevelLoad(LevelEvent.Load event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() instanceof Level level) {
            onLevelChange(level);
        }
    }

    @SubscribeEvent
    public static void onClientLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (event.getLevel() == activeLevel) {
            onLevelChange(null);
        }
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RegionStorage currentStorage = regionStorage;
        Future<?> flushFuture = flushToStorage(currentStorage, true);
        if (flushFuture != null) {
            try {
                flushFuture.get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        onLevelChange(null);
    }

    public static void saveSnapshot(java.util.Map<Long, MapChunks.ChunkColorColumn> snapshot, boolean mergeExisting) {
        RegionStorage storage = regionStorage;
        if (storage == null || snapshot == null || snapshot.isEmpty()) {
            return;
        }

        IO_EXECUTOR.execute(() -> {
            try {
                storage.saveChunks(snapshot, mergeExisting);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public static void shutdown() {
        MAP_EXECUTOR.shutdown();
        IO_EXECUTOR.shutdown();
    }

    public static void onLevelChange(Level level) {
        RegionStorage previousStorage = regionStorage;

        if (level == null) {
            flushToStorage(previousStorage, true);
            activeDimension = null;
            activeLevel = null;
            regionStorage = null;
            loadingRegions.clear();
            ageMap.clear();
            MapChunks.clear();
            return;
        }

        if (!level.isClientSide()) {
            return;
        }

        ResourceLocation dimLoc = level.dimension().location();
        if (dimLoc.equals(activeDimension) && regionStorage != null) {
            activeLevel = level;
            return;
        }

        // Flush previous dimension before clearing in-memory state or swapping storage.
        flushToStorage(previousStorage, true);

        activeDimension = dimLoc;
        activeLevel = level;
        loadingRegions.clear();
        ageMap.clear();
        MapChunks.clear();
        regionStorage = new RegionStorage(resolveDimensionDir(dimLoc));
    }

    public static void requestRegionLoad(int regionX, int regionZ) {
        RegionStorage storage = regionStorage;
        ResourceLocation expectedDim = activeDimension;
        if (storage == null || expectedDim == null) {
            return;
        }

        long packedRegion = RegionStorage.packRegionPos(regionX, regionZ);
        if (!loadingRegions.add(packedRegion)) {
            return;
        }

        // The region stays in loadingRegions afterwards so each region file is only read once per dimension session.
        IO_EXECUTOR.execute(() -> {
            try {
                // Guard against dimension changes while queued.
                if (regionStorage != storage || !expectedDim.equals(activeDimension)) {
                    return;
                }
                java.util.Map<Long, RegionStorage.ChunkRecord> loaded = storage.loadRegion(regionX, regionZ);
                if (loaded.isEmpty()) {
                    return;
                }
                if (regionStorage != storage || !expectedDim.equals(activeDimension)) {
                    return;
                }
                MapChunks.putAllFromRecords(loaded);
                for (Long packedChunk : loaded.keySet()) {
                    ageMap.putIfAbsent(packedChunk, (byte) 0);
                }
            } catch (IOException e) {
                AdminsFactions.LOGGER.warn("Failed to load map region {}, {}", regionX, regionZ, e);
            }
        });
    }

    private static Path resolveDimensionDir(ResourceLocation dimLoc){
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        String safeNamespace = sanitizePathSegment(dimLoc.getNamespace());
        String safePath = sanitizePathSegment(dimLoc.getPath());
        return gameDir
                .resolve("factions_map")
                .resolve(safeNamespace)
                .resolve(safePath);
    }

    private static String sanitizePathSegment(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Future<?> flushToStorage(RegionStorage targetStorage, boolean mergeExisting) {
        if (targetStorage == null) {
            return null;
        }
        java.util.Map<Long, MapChunks.ChunkColorColumn> snapshot = MapChunks.snapshotAllColumns();
        if (snapshot.isEmpty()) {
            return null;
        }
        return IO_EXECUTOR.submit(() -> {
            try {
                targetStorage.saveChunks(snapshot, mergeExisting);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    private void update(int chunkX, int chunkZ, int depthY, Level level){
        int[][] newSlice = new int[16][16];
        MapChunks.putSlice(chunkX, chunkZ, depthY, newSlice);
    }

    private static void updateSurface(int chunkX, int chunkZ, Level level, byte currentIter){
        int[][] newSlice = new int[16][16];
        short[][] newHeight = new short[16][16];
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        int blockX;
        int blockZ;
        int color;
        long packedPos = MapChunks.packChunkPos(chunkX, chunkZ);

        for(int x = 0; x < 16; x++){
            for(int z = 0; z < 16; z++){
                blockX = startX + x;
                blockZ = startZ + z;
                try {
                    color = TerrainColors.computeBlockColorWithExep(level, blockX, blockZ);
                } catch (Exception e){
                    ageMap.put(packedPos, (byte) 0);
                    return;
                }
                newSlice[x][z] = color;
                newHeight[x][z] = (short)level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ);
            }
        }
        ageMap.put(packedPos,currentIter);
        MapChunks.putSlice(chunkX, chunkZ, Integer.MAX_VALUE, newSlice);
        MapChunks.putHeightMap(chunkX, chunkZ, newHeight);
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger idx = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + idx.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }

}