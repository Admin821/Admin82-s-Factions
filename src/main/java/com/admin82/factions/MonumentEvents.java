package com.admin82.factions;

import com.admin82.factions.blockentity.MonumentControllerBlockEntity;
import com.admin82.factions.blockentity.MonumentCrateBlockEntity;
import com.admin82.factions.supplydrop.SupplyDropPool;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.registry.ModBlocks;
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
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

@EventBusSubscriber(modid = AdminsFactions.MODID)
public final class MonumentEvents {
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
        return respawnedAny;
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