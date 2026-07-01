package com.admin82.factions.faction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

public class FactionManager extends SavedData {

    private static final String DATA_NAME = "adminsfactions_data";

    private final Map<UUID, Faction> factions = new HashMap<>();
    // playerUUID → factionUUID (for fast lookup)
    private final Map<UUID, UUID> playerFactionMap = new HashMap<>();
    // factionId → table location (persistent)
    private final Map<UUID, TableLocation> factionTables = new HashMap<>();
    // playerUUID → pending move (transient, not saved to NBT)
    private final Map<UUID, PendingMove> pendingMoves = new HashMap<>();
    // playerUUID → intended table location when opening Create Faction UI (transient)
    private final Map<UUID, TableLocation> pendingCreationTables = new HashMap<>();

    public FactionManager() {}

    // ── Inner data records ────────────────────────────────────────────────────

    public record TableLocation(BlockPos pos, String dimension) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("pos", pos.asLong());
            tag.putString("dim", dimension);
            return tag;
        }
        public static TableLocation load(CompoundTag tag) {
            return new TableLocation(BlockPos.of(tag.getLong("pos")), tag.getString("dim"));
        }
    }

    /** Transient; not persisted. Cleared on logout, death, or dimension change. */
    public record PendingMove(UUID factionId, BlockPos originalPos, String dimension) {}

    // ── Static access ─────────────────────────────────────────────────────────

    public static FactionManager get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FactionManager::new, FactionManager::load, null),
                DATA_NAME
        );
    }

    public static FactionManager get(ServerLevel level) {
        return get(level.getServer());
    }

    // ── Faction CRUD ──────────────────────────────────────────────────────────

    public Faction createFaction(String name, ServerPlayer owner, String description) {
        Faction faction = new Faction(UUID.randomUUID(), name, owner.getUUID(), description);
        faction.addMember(new FactionMember(owner.getUUID(), owner.getGameProfile().getName(), FactionRole.OWNER));
        factions.put(faction.getId(), faction);
        playerFactionMap.put(owner.getUUID(), faction.getId());
        setDirty();
        return faction;
    }

    @Nullable
    public Faction getFaction(UUID factionId) { return factions.get(factionId); }

    @Nullable
    public Faction getFactionForPlayer(UUID playerUUID) {
        UUID factionId = playerFactionMap.get(playerUUID);
        return factionId == null ? null : factions.get(factionId);
    }

    @Nullable
    public UUID getPlayerFactionId(UUID playerUUID) { return playerFactionMap.get(playerUUID); }

    @Nullable
    public Faction getFactionByName(String name) {
        return factions.values().stream()
                .filter(f -> f.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
    }

    public boolean isNameTaken(String name) {
        return factions.values().stream().anyMatch(f -> f.getName().equalsIgnoreCase(name));
    }

    public Map<UUID, Faction> getAllFactions() { return Collections.unmodifiableMap(factions); }

    public void disbandFaction(UUID factionId) {
        Faction faction = factions.remove(factionId);
        if (faction != null) {
            playerFactionMap.entrySet().removeIf(e -> e.getValue().equals(factionId));
            factionTables.remove(factionId);
            setDirty();
        }
    }

    // ── Faction table tracking ────────────────────────────────────────────────

    public void setFactionTable(UUID factionId, BlockPos pos, String dimension) {
        factionTables.put(factionId, new TableLocation(pos, dimension));
        setDirty();
    }

    @Nullable
    public TableLocation getFactionTable(UUID factionId) { return factionTables.get(factionId); }

    public void removeFactionTable(UUID factionId) {
        factionTables.remove(factionId);
        setDirty();
    }

    // ── Move mode (transient) ─────────────────────────────────────────────────

    public void startPendingMove(UUID playerUUID, UUID factionId, BlockPos originalPos, String dimension) {
        pendingMoves.put(playerUUID, new PendingMove(factionId, originalPos, dimension));
    }

    @Nullable
    public PendingMove getPendingMove(UUID playerUUID) { return pendingMoves.get(playerUUID); }

    public void clearPendingMove(UUID playerUUID) { pendingMoves.remove(playerUUID); }

    // ── Pending faction-creation placement (transient) ────────────────────────────

    /** Records where a Faction Table will be placed once the player creates their faction. */
    public void setPendingCreation(UUID playerUUID, TableLocation loc) {
        pendingCreationTables.put(playerUUID, loc);
    }

    @Nullable
    public TableLocation getPendingCreation(UUID playerUUID) { return pendingCreationTables.get(playerUUID); }

    public void clearPendingCreation(UUID playerUUID) { pendingCreationTables.remove(playerUUID); }

    // ── Membership ────────────────────────────────────────────────────────────

    public boolean addPlayerToFaction(UUID factionId, UUID playerUUID, String playerName) {
        if (playerFactionMap.containsKey(playerUUID)) return false;
        Faction faction = factions.get(factionId);
        if (faction == null) return false;
        faction.addMember(new FactionMember(playerUUID, playerName, FactionRole.MEMBER));
        playerFactionMap.put(playerUUID, factionId);
        setDirty();
        return true;
    }

    public boolean removePlayerFromFaction(UUID playerUUID) {
        UUID factionId = playerFactionMap.remove(playerUUID);
        if (factionId == null) return false;
        Faction faction = factions.get(factionId);
        if (faction != null) {
            faction.removeMember(playerUUID);
            setDirty();
        }
        return true;
    }

    // ── Land claims ───────────────────────────────────────────────────────────

    public boolean claimChunk(UUID factionId, int chunkX, int chunkZ, String dimension) {
        for (Faction f : factions.values()) {
            if (f.hasClaim(chunkX, chunkZ, dimension)) return false;
        }
        Faction faction = factions.get(factionId);
        if (faction == null) return false;
        faction.addClaim(new LandClaim(chunkX, chunkZ, net.minecraft.resources.ResourceLocation.parse(dimension)));
        setDirty();
        return true;
    }

    public boolean unclaimChunk(UUID factionId, int chunkX, int chunkZ, String dimension) {
        Faction faction = factions.get(factionId);
        if (faction == null) return false;
        boolean removed = faction.removeClaim(chunkX, chunkZ, dimension);
        if (removed) setDirty();
        return removed;
    }

    @Nullable
    public Faction getChunkOwner(int chunkX, int chunkZ, String dimension) {
        return factions.values().stream()
                .filter(f -> f.hasClaim(chunkX, chunkZ, dimension))
                .findFirst().orElse(null);
    }

    // ── Wars ──────────────────────────────────────────────────────────────────

    public boolean declareWar(UUID attackerId, UUID defenderId) {
        Faction attacker = factions.get(attackerId);
        Faction defender = factions.get(defenderId);
        if (attacker == null || defender == null) return false;
        if (attacker.isAtWarWith(defenderId)) return false;
        attacker.addWar(new WarEntry(defenderId, defender.getName(), System.currentTimeMillis()));
        defender.addWar(new WarEntry(attackerId, attacker.getName(), System.currentTimeMillis()));
        setDirty();
        return true;
    }

    public boolean endWar(UUID faction1Id, UUID faction2Id) {
        Faction f1 = factions.get(faction1Id);
        Faction f2 = factions.get(faction2Id);
        if (f1 == null && f2 == null) return false;
        if (f1 != null) f1.removeWar(faction2Id);
        if (f2 != null) f2.removeWar(faction1Id);
        setDirty();
        return true;
    }

    // ── Save / Load ───────────────────────────────────────────────────────────

    public static FactionManager load(CompoundTag tag, HolderLookup.Provider registries) {
        FactionManager manager = new FactionManager();
        ListTag factionList = tag.getList("factions", Tag.TAG_COMPOUND);
        for (int i = 0; i < factionList.size(); i++) {
            Faction faction = Faction.load(factionList.getCompound(i));
            manager.factions.put(faction.getId(), faction);
            for (FactionMember member : faction.getMembers()) {
                manager.playerFactionMap.put(member.getUuid(), faction.getId());
            }
        }
        CompoundTag tablesTag = tag.getCompound("tables");
        for (String key : tablesTag.getAllKeys()) {
            try {
                UUID id = UUID.fromString(key);
                manager.factionTables.put(id, TableLocation.load(tablesTag.getCompound(key)));
            } catch (IllegalArgumentException ignored) {}
        }
        return manager;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag factionList = new ListTag();
        factions.values().forEach(f -> factionList.add(f.save()));
        tag.put("factions", factionList);
        CompoundTag tablesTag = new CompoundTag();
        factionTables.forEach((id, loc) -> tablesTag.put(id.toString(), loc.save()));
        tag.put("tables", tablesTag);
        return tag;
    }
}
