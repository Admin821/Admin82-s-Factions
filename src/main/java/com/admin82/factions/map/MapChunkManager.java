package com.admin82.factions.map;

import com.admin82.factions.AdminsFactions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@EventBusSubscriber(modid = AdminsFactions.MODID)
public class MapChunkManager {

    private static final int WORKER_THREADS = 4;
    private static final int QUEUE_CAPACITY = 8192;

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
                    if (Minecraft.getInstance().level.getChunkSource().hasChunk(xi, zi) && (Math.abs(Byte.compareUnsigned(v, currentIter)) >= 2 || v == 0)) {
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

        if(MapHud.SQUARE_TEXTURE != null && Minecraft.getInstance().player != null){
            MapHud.SQUARE_TEXTURE.clear();
            Vec3 position = Minecraft.getInstance().player.position();
            int x = ((int) position.x) >> 4;
            int z = ((int) position.z) >> 4;
            for(int xi = 0; xi < 32; xi++){
                for(int zi = 0; zi < 32; zi++){
                    int cx = xi + x - 16;
                    int cz = zi + z - 16;
                    int[][] colors = MapChunks.getSlice(cx, cz, Integer.MAX_VALUE);
                    if(colors != null) {
                        for (int xc = 0; xc < 16; xc++) {
                            for (int zc = 0; zc < 16; zc++) {
                                MapHud.SQUARE_TEXTURE.setPixel((xi << 4) + xc, (zi << 4) + zc, colors[xc][zc]);
                            }
                        }
                    }
                }
            }
            MapHud.SQUARE_TEXTURE.upload();
        }
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

}