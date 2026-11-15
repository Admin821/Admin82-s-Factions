package com.admin82.factions.faction;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.economy.EconomyManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps faction chunkloading deeds in sync with real force-loaded chunks.
 *
 * <p>The source of truth is the claim data saved in {@link FactionManager} (deed owned + enabled flags).
 * Saved NeoForge tickets are dropped on world load and rebuilt from that data once the server has started,
 * so a crash or restart can never leave chunks loaded that shouldn't be, or skip ones that should.
 * Chunkloading pauses while a faction's vault is empty (its claims are unprotected) and resumes on its own
 * once upkeep is paid again.
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public final class ChunkLoadManager {

    /** Saved tickets are discarded on load; {@link #onServerStarted} re-creates the correct set. */
    public static final TicketController TICKETS = new TicketController(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "faction_chunkloading"),
            (level, helper) -> new ArrayList<>(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets));

    private record LoadedChunk(String dimension, int chunkX, int chunkZ) {}

    /** Chunks we currently hold a ticket for, and which faction owns that ticket. */
    private static final Map<LoadedChunk, UUID> ACTIVE = new HashMap<>();

    private ChunkLoadManager() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        ACTIVE.clear();
        sync(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE.clear();
    }

    /** Whether the chunk is force-loaded right now (deed on and faction able to pay). */
    public static boolean isActive(String dimension, int chunkX, int chunkZ) {
        return ACTIVE.containsKey(new LoadedChunk(dimension, chunkX, chunkZ));
    }

    /** Adds and removes tickets so that exactly the enabled chunks of solvent factions are loaded. */
    public static void sync(MinecraftServer server) {
        FactionManager factions = FactionManager.get(server);
        EconomyManager economy = EconomyManager.get(server);
        Map<LoadedChunk, UUID> desired = new HashMap<>();
        for (Faction faction : factions.getAllFactions().values()) {
            if (!economy.isProtected(faction.getId())) continue;
            for (LandClaim claim : faction.getLandClaims()) {
                if (claim.isChunkloading()) {
                    desired.put(new LoadedChunk(claim.dimension().toString(), claim.chunkX(), claim.chunkZ()),
                            faction.getId());
                }
            }
        }

        Set<LoadedChunk> toRemove = new HashSet<>();
        ACTIVE.forEach((chunk, owner) -> {
            if (!owner.equals(desired.get(chunk))) toRemove.add(chunk);
        });
        for (LoadedChunk chunk : toRemove) {
            UUID owner = ACTIVE.remove(chunk);
            ServerLevel level = level(server, chunk.dimension());
            if (level != null) TICKETS.forceChunk(level, owner, chunk.chunkX(), chunk.chunkZ(), false, true);
        }

        desired.forEach((chunk, owner) -> {
            if (owner.equals(ACTIVE.get(chunk))) return;
            ServerLevel level = level(server, chunk.dimension());
            if (level == null) return;
            TICKETS.forceChunk(level, owner, chunk.chunkX(), chunk.chunkZ(), true, true);
            ACTIVE.put(chunk, owner);
        });
    }

    private static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }
}
