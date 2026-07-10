package com.admin82.factions.barracks;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Server-side persistent data for the Barracks system.
 * Stores kit definitions per player. Kits are consumed (deleted) when taken.
 */
public class BarracksData extends SavedData {

    private static final String DATA_NAME = "adminsfactions_barracks";

    /** playerId → ordered list of kits */
    private final Map<UUID, List<KitData>> playerKits = new HashMap<>();

    // ── Static access ─────────────────────────────────────────────────────────

    public static BarracksData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(BarracksData::new, BarracksData::load, null),
                DATA_NAME
        );
    }

    // ── Kit management ────────────────────────────────────────────────────────

    public List<KitData> getKits(UUID playerId) {
        return Collections.unmodifiableList(playerKits.getOrDefault(playerId, Collections.emptyList()));
    }

    @Nullable
    public KitData getKit(UUID playerId, String kitName) {
        return playerKits.getOrDefault(playerId, Collections.emptyList()).stream()
                .filter(k -> k.getName().equalsIgnoreCase(kitName))
                .findFirst().orElse(null);
    }

    /** Returns false if the name is already taken. */
    public boolean createKit(UUID playerId, String kitName) {
        if (kitName == null || kitName.isBlank()) return false;
        List<KitData> kits = playerKits.computeIfAbsent(playerId, k -> new ArrayList<>());
        if (kits.stream().anyMatch(k -> k.getName().equalsIgnoreCase(kitName))) return false;
        if (kits.size() >= 3) return false; // cap at 3 kits per player
        kits.add(new KitData(kitName));
        setDirty();
        return true;
    }

    /** Removes the kit (e.g. when consumed by a player). */
    public boolean deleteKit(UUID playerId, String kitName) {
        List<KitData> kits = playerKits.get(playerId);
        if (kits == null) return false;
        boolean removed = kits.removeIf(k -> k.getName().equalsIgnoreCase(kitName));
        if (removed) setDirty();
        return removed;
    }

    /** Updates a single slot within a kit. */
    public void saveKitSlot(UUID playerId, String kitName, int slotIndex, net.minecraft.world.item.ItemStack stack) {
        KitData kit = getKitMutable(playerId, kitName);
        if (kit == null) return;
        kit.setSlot(slotIndex, stack);
        setDirty();
    }

    /** Replaces ALL slots of a kit at once. */
    public void saveKitAllSlots(UUID playerId, String kitName, net.minecraft.world.item.ItemStack[] items) {
        KitData kit = getKitMutable(playerId, kitName);
        if (kit == null) return;
        for (int i = 0; i < Math.min(items.length, KitData.SLOT_COUNT); i++)
            kit.setSlot(i, items[i]);
        setDirty();
    }

    @Nullable
    private KitData getKitMutable(UUID playerId, String kitName) {
        List<KitData> kits = playerKits.get(playerId);
        if (kits == null) return null;
        return kits.stream().filter(k -> k.getName().equalsIgnoreCase(kitName)).findFirst().orElse(null);
    }

    // ── Player cleanup ───────────────────────────────────────────────────────

    public void removePlayer(UUID playerId) {
        playerKits.remove(playerId);
        setDirty();
    }

    // ── SavedData NBT ─────────────────────────────────────────────────────────

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag kitList = new ListTag();
        playerKits.forEach((playerId, kits) -> {
            CompoundTag fTag = new CompoundTag();
            fTag.putUUID("playerId", playerId);
            ListTag kl = new ListTag();
            for (KitData kit : kits) kl.add(kit.save(registries));
            fTag.put("kits", kl);
            kitList.add(fTag);
        });
        tag.put("playerKits", kitList);
        return tag;
    }

    public static BarracksData load(CompoundTag tag, HolderLookup.Provider registries) {
        BarracksData data = new BarracksData();
        String rootKey = tag.contains("playerKits", Tag.TAG_LIST) ? "playerKits" : "factionKits";
        ListTag kitList = tag.getList(rootKey, Tag.TAG_COMPOUND);
        for (int i = 0; i < kitList.size(); i++) {
            CompoundTag fTag = kitList.getCompound(i);
            String idKey = fTag.hasUUID("playerId") ? "playerId" : "factionId";
            UUID ownerId = fTag.getUUID(idKey);
            ListTag kl = fTag.getList("kits", Tag.TAG_COMPOUND);
            List<KitData> kits = new ArrayList<>();
            for (int j = 0; j < kl.size(); j++) kits.add(KitData.load(kl.getCompound(j), registries));
            data.playerKits.put(ownerId, kits);
        }
        return data;
    }
}
