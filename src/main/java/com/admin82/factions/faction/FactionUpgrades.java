package com.admin82.factions.faction;

import com.admin82.factions.Config;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Faction upgrade levels that aren't stored elsewhere: the land limit and outpost slots. */
public class FactionUpgrades extends SavedData {

    private static final String DATA_NAME = "adminsfactions_upgrades";
    /** Land upgrades have no real limit; this only keeps the numbers sane. */
    public static final int MAX_LAND_LEVEL = 10_000;
    public static final int MAX_OUTPOST_SLOTS = 3;

    private final Map<UUID, int[]> levels = new HashMap<>(); // [land, outpostSlots, meterEfficiency, seamMiners, seamPath, seamPathLevel]

    public static FactionUpgrades get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(FactionUpgrades::new, FactionUpgrades::load, null), DATA_NAME);
    }

    private int[] of(UUID faction) {
        return levels.computeIfAbsent(faction, id -> new int[]{1, 1, 1, 1, 0, 0});
    }

    public int landLevel(UUID faction) { return of(faction)[0]; }
    public int outpostSlots(UUID faction) { return of(faction)[1]; }

    public void setLandLevel(UUID faction, int level) { of(faction)[0] = level; setDirty(); }
    public int meterLevel(UUID faction) { return of(faction)[2]; }
    public void setMeterLevel(UUID faction, int level) { of(faction)[2] = level; setDirty(); }
    public void setOutpostSlots(UUID faction, int slots) { of(faction)[1] = slots; setDirty(); }

    /** Seam Extractors the faction may have on one seam. */
    public int seamMiners(UUID faction) { return of(faction)[3]; }
    public void setSeamMiners(UUID faction, int miners) { of(faction)[3] = miners; setDirty(); }
    /** The seam upgrade path the faction is locked to (NONE until it buys its first level). */
    public com.admin82.factions.seam.SeamPath seamPath(UUID faction) { return com.admin82.factions.seam.SeamPath.of(of(faction)[4]); }
    public int seamPathLevel(UUID faction) { return of(faction)[5]; }
    public void setSeamPath(UUID faction, com.admin82.factions.seam.SeamPath path, int level) {
        of(faction)[4] = path.ordinal();
        of(faction)[5] = level;
        setDirty();
    }
    public static final int MAX_SEAM_MINERS = 3;

    public void remove(UUID faction) {
        if (levels.remove(faction) != null) setDirty();
    }

    /** How far from the faction table (in chunks, every direction) the faction may claim. */
    public int landRadius(UUID faction) {
        return Config.LAND_BASE_RADIUS.get() + landLevel(faction) - 1;
    }

    /** Warbonds for the land upgrade after {@code level}: the first price, times the ramp for each one before. */
    public static int landUpgradeCost(int level) {
        double cost = Config.LAND_UPGRADE_WARBONDS.get() * Math.pow(Config.LAND_UPGRADE_RAMP.get(), level - 1);
        return (int) Math.min(Integer.MAX_VALUE, Math.round(cost));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        levels.forEach((id, l) -> all.putIntArray(id.toString(), l));
        tag.put("levels", all);
        return tag;
    }

    public static FactionUpgrades load(CompoundTag tag, HolderLookup.Provider registries) {
        FactionUpgrades data = new FactionUpgrades();
        CompoundTag all = tag.getCompound("levels");
        for (String key : all.getAllKeys()) {
            int[] l = all.getIntArray(key);
            if (l.length < 2) continue;
            try {
                data.levels.put(UUID.fromString(key), new int[]{Math.max(1, l[0]), Math.max(1, l[1]), l.length > 2 ? Math.max(1, l[2]) : 1,
                        l.length > 3 ? Math.max(1, l[3]) : 1, l.length > 4 ? Math.max(0, l[4]) : 0, l.length > 5 ? Math.max(0, l[5]) : 0});
            } catch (IllegalArgumentException ignored) { }
        }
        return data;
    }
}
