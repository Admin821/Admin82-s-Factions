package com.admin82.factions;

import com.admin82.factions.blockentity.MonumentControllerBlockEntity;
import com.admin82.factions.blockentity.MonumentCrateBlockEntity;
import com.admin82.factions.supplydrop.SupplyDropPool;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.entity.monument.RoguePmcEntity;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.registry.ModBlocks;
import com.admin82.factions.registry.ModEntities;
import com.admin82.factions.menu.MonumentMenu;
import com.admin82.factions.util.BypassManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

@EventBusSubscriber(modid = AdminsFactions.MODID)
public final class MonumentEvents {
    private static final String AI_DROP_MONUMENT_TAG = "AdminFactionsMonumentAiDrop";
    private static int tickCounter;
    private static final Map<UUID, UUID> PLAYER_MONUMENTS = new HashMap<>();
    private static final Map<String, Set<BlockPos>> PENDING_ORE_RESETS = new HashMap<>();

    private MonumentEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        resetMinedGenerators(event.getServer());
        if (++tickCounter < 20) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        MonumentData data = MonumentData.get(server);
        updatePlayerMonuments(server, data);
        int onlinePlayers = server.getPlayerCount();
        double timerRate = Math.clamp(onlinePlayers / 10.0, 0.5, 2.0);

        for (MonumentEntry monument : data.getAll()) {
            ServerLevel level = getLevel(server, monument.dimension);
            if (level == null || hasPlayerInside(server, monument)) continue;
            if (monument.getRemainingRespawnTicks() > 0.0) {
                monument.setRemainingRespawnTicks(monument.getRemainingRespawnTicks() - 20.0 * timerRate);
                data.changed();
            }
            if (monument.getRemainingRespawnTicks() <= 0.0 && refill(level, monument)) {
                monument.resetRespawnTimer();
                data.changed();
            }
        }
    }

    private static void updatePlayerMonuments(MinecraftServer server, MonumentData data) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            MonumentEntry monument = data.getAt(
                    player.blockPosition(), player.level().dimension().location().toString());
            UUID previousId = PLAYER_MONUMENTS.get(player.getUUID());
            UUID currentId = monument == null ? null : monument.id;
            if (Objects.equals(previousId, currentId)) continue;

            if (currentId == null) {
                PLAYER_MONUMENTS.remove(player.getUUID());
                MonumentEntry previous = previousId == null ? null : data.get(previousId);
                if (previous != null) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 40, 10));
                    player.connection.send(new ClientboundSetTitleTextPacket(
                            Component.literal("§7Leaving §f" + previous.getName())));
                }
                continue;
            }

            PLAYER_MONUMENTS.put(player.getUUID(), currentId);
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 50, 10));
            player.connection.send(new ClientboundSetTitleTextPacket(
                    Component.literal("§6Entering §f" + monument.getName())));
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PLAYER_MONUMENTS.remove(event.getEntity().getUUID());
    }

    private static boolean hasPlayerInside(MinecraftServer server, MonumentEntry monument) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (monument.contains(player.blockPosition(), player.level().dimension().location().toString())) return true;
        }
        return false;
    }

    private static boolean refill(ServerLevel level, MonumentEntry monument) {
        boolean respawnedAny = false;
        if (level.hasChunkAt(monument.controllerPos)
                && level.getBlockEntity(monument.controllerPos) instanceof MonumentControllerBlockEntity controller) {
            for (Map.Entry<BlockPos, String> linkedCrate : monument.getCrates().entrySet()) {
                BlockPos pos = linkedCrate.getKey();
                if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof MonumentCrateBlockEntity crate)) continue;
                respawnedAny = true;
                fillCrate(level, monument.getLootPool(linkedCrate.getValue()), crate, monument.getTier());
            }
        }
        for (Map.Entry<BlockPos, String> generator : monument.getOreGenerators().entrySet()) {
            BlockPos pos = generator.getKey();
            if (!level.hasChunkAt(pos)) continue;
            ResourceLocation blockId = ResourceLocation.tryParse(generator.getValue());
            if (blockId == null || blockId.equals(ResourceLocation.withDefaultNamespace("cobblestone"))) continue;
            var block = BuiltInRegistries.BLOCK.getOptional(blockId);
            if (block.isEmpty()) continue;
            if (level.getBlockState(pos).is(ModBlocks.ORE_GENERATOR.get())) {
                level.setBlock(pos, block.get().defaultBlockState(), 3);
            }
            respawnedAny = true;
        }
        if (Config.MONUMENT_PMCS_ENABLED.get()) {
            respawnedAny |= replenishPmcSquad(level, monument);
        }
        return respawnedAny;
    }

    private static boolean replenishPmcSquad(ServerLevel level, MonumentEntry monument) {
        List<Long> chunks = new ArrayList<>(monument.getDesignatedChunks());
        for (long chunkKey : chunks) {
            ChunkPos chunk = new ChunkPos(chunkKey);
            level.getChunk(chunk.x, chunk.z);
        }
        AABB territory = monumentBounds(level, monument);
        level.getEntitiesOfClass(ItemEntity.class, territory, item ->
                item.getPersistentData().hasUUID(AI_DROP_MONUMENT_TAG)
                        && monument.id.equals(item.getPersistentData().getUUID(AI_DROP_MONUMENT_TAG)))
                .forEach(ItemEntity::discard);

        List<RoguePmcEntity> survivors = level.getEntitiesOfClass(RoguePmcEntity.class, territory,
                pmc -> monument.id.equals(pmc.getMonumentId()) && pmc.isAlive());
        boolean initialGeneration = !monument.hasAiGenerationStarted() && survivors.isEmpty();
        if (!monument.hasAiGenerationStarted()) monument.markAiGenerationStarted();
        boolean allRevived = true;
        for (RoguePmcRole role : RoguePmcRole.values()) {
            int count;
            if (initialGeneration) {
                int minimum = monument.getAiMinimumCount(role);
                int maximum = Math.max(minimum, monument.getAiMaximumCount(role));
                count = minimum + level.random.nextInt(maximum - minimum + 1);
            } else {
                count = monument.getDeadAiCount(role);
            }
            for (int index = 0; index < count; index++) {
                boolean spawned = false;
                for (int attempt = 0; attempt < 8; attempt++) {
                    ChunkPos chunk = new ChunkPos(chunks.get(level.random.nextInt(chunks.size())));
                    int x = chunk.getMinBlockX() + 2 + level.random.nextInt(12);
                    int z = chunk.getMinBlockZ() + 2 + level.random.nextInt(12);
                    if (!level.hasChunk(x >> 4, z >> 4)) continue;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos spawnPos = new BlockPos(x, y, z);
                    RoguePmcEntity pmc = ModEntities.pmcType(role).spawn(level, spawnPos, MobSpawnType.EVENT);
                    if (pmc == null) continue;
                    pmc.bindToMonument(monument.id, monument.controllerPos);
                    pmc.applyConfiguredLoadout(monument.getAiLoadout(role));
                    if (!initialGeneration) monument.reviveAi(role);
                    spawned = true;
                    break;
                }
                if (!spawned) allRevived = false;
            }
        }
        List<RoguePmcEntity> activeSquad = level.getEntitiesOfClass(RoguePmcEntity.class, territory,
                pmc -> monument.id.equals(pmc.getMonumentId()) && pmc.isAlive());
        assignBalancedSquads(activeSquad);
        syncOpenAiEditors(level.getServer(), monument);
        return allRevived;
    }

    private static void assignBalancedSquads(List<RoguePmcEntity> pmcs) {
        if (pmcs.isEmpty()) return;
        pmcs.sort(Comparator.comparing(RoguePmcEntity::getUUID));
        int squadCount = Math.max(1, (pmcs.size() + 4) / 5);
        UUID[] squadIds = new UUID[squadCount];
        for (int index = 0; index < squadCount; index++) squadIds[index] = UUID.randomUUID();
        for (int index = 0; index < pmcs.size(); index++) {
            pmcs.get(index).assignSquad(squadIds[index % squadCount]);
        }
    }

    @SubscribeEvent
    public static void onRoguePmcDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof RoguePmcEntity pmc)
                || !(pmc.level() instanceof ServerLevel level) || pmc.getMonumentId() == null) return;
        MonumentData data = MonumentData.get(level.getServer());
        MonumentEntry monument = data.get(pmc.getMonumentId());
        if (monument == null) return;
        monument.markAiDead(pmc.getRole());
        for (ItemStack stack : monument.getAiDropPool().generateItems(new java.util.Random(level.random.nextLong()))) {
            ItemEntity drop = new ItemEntity(level, pmc.getX(), pmc.getY() + 0.25, pmc.getZ(), stack);
            drop.getPersistentData().putUUID(AI_DROP_MONUMENT_TAG, monument.id);
            drop.setDefaultPickUpDelay();
            level.addFreshEntity(drop);
        }
        data.changed();
        syncOpenAiEditors(level.getServer(), monument);
    }

    private static void syncOpenAiEditors(MinecraftServer server, MonumentEntry monument) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof MonumentMenu menu
                    && monument.id.equals(menu.getSelectedId())) {
                menu.syncAiEditor(monument);
            }
        }
    }

    private static AABB monumentBounds(ServerLevel level, MonumentEntry monument) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long key : monument.getDesignatedChunks()) {
            ChunkPos chunk = new ChunkPos(key);
            minX = Math.min(minX, chunk.getMinBlockX());
            minZ = Math.min(minZ, chunk.getMinBlockZ());
            maxX = Math.max(maxX, chunk.getMaxBlockX());
            maxZ = Math.max(maxZ, chunk.getMaxBlockZ());
        }
        return new AABB(minX, level.getMinBuildHeight(), minZ,
                maxX + 1.0, level.getMaxBuildHeight(), maxZ + 1.0).inflate(16.0);
    }

    private static void resetMinedGenerators(MinecraftServer server) {
        if (PENDING_ORE_RESETS.isEmpty()) return;
        Map<String, Set<BlockPos>> pending = new HashMap<>(PENDING_ORE_RESETS);
        PENDING_ORE_RESETS.clear();
        pending.forEach((dimension, positions) -> {
            ServerLevel level = getLevel(server, dimension);
            if (level == null) return;
            MonumentData data = MonumentData.get(server);
            for (BlockPos pos : positions) {
                MonumentEntry monument = data.getAt(pos, dimension);
                if (monument != null && monument.hasOreGenerator(pos)) {
                    level.setBlock(pos, ModBlocks.ORE_GENERATOR.get().defaultBlockState(), 3);
                }
            }
        });
    }

    private static void fillCrate(ServerLevel level, SupplyDropPool lootPool,
                                  MonumentCrateBlockEntity crate, int tier) {
        List<ItemStack> pool = lootPool == null ? new ArrayList<>()
            : lootPool.generateItems(new java.util.Random(level.random.nextLong()));
        crate.clearContent();
        int rolls = Math.min(pool.size(), Math.max(1, tier + 2));
        for (int slot = 0; slot < rolls; slot++) {
            int selected = level.random.nextInt(pool.size());
            crate.setItem(slot, pool.remove(selected));
        }
        crate.setChanged();
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MonumentEntry monument = MonumentData.get(level.getServer())
                .getAt(event.getPos(), level.dimension().location().toString());
        if (monument == null) return;
        boolean bypassing = BypassManager.isBypassing(event.getPlayer().getUUID());
        if (monument.hasOreGenerator(event.getPos())) {
            if (bypassing) {
                monument.unlinkOreGenerator(event.getPos());
                MonumentData.get(level.getServer()).changed();
                return;
            }
            if (level.getBlockState(event.getPos()).is(ModBlocks.ORE_GENERATOR.get())) {
                event.setCanceled(true);
                event.getPlayer().displayClientMessage(Component.literal(
                        "§7This Ore Generator is dormant until the monument loot respawns."), true);
                return;
            }
            PENDING_ORE_RESETS.computeIfAbsent(monument.dimension, ignored -> new HashSet<>())
                    .add(event.getPos().immutable());
            return;
        }
        if (!bypassing) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MonumentEntry monument = MonumentData.get(level.getServer())
                .getAt(event.getPos(), level.dimension().location().toString());
        if (monument == null) return;
        if (event.getEntity() instanceof ServerPlayer player
            && BypassManager.isBypassing(player.getUUID())) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        MonumentData data = MonumentData.get(level.getServer());
        String dimension = level.dimension().location().toString();
        event.getAffectedBlocks().removeIf(pos -> data.getAt(pos, dimension) != null);
    }

    private static ServerLevel getLevel(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, id));
    }
}