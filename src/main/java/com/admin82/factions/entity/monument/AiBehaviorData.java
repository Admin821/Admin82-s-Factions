package com.admin82.factions.entity.monument;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.EnumMap;
import java.util.Map;

/** World-wide AI behavior profile per guard class. Monuments pick the difficulty per class. */
public final class AiBehaviorData extends SavedData {
    public static final int DIFFICULTY_COUNT = 5;
    public static final String[] DIFFICULTY_NAMES = {"Easy", "Relaxed", "Normal", "Hard", "Extreme"};
    private static final String DATA_NAME = "adminsfactions_ai_behavior";

    private final Map<RoguePmcRole, AiBehaviorProfile> profiles = new EnumMap<>(RoguePmcRole.class);

    public AiBehaviorData() {
        for (RoguePmcRole role : RoguePmcRole.values()) profiles.put(role, AiBehaviorProfile.defaults(role));
    }

    public static AiBehaviorData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AiBehaviorData::new, AiBehaviorData::load, null), DATA_NAME);
    }

    public AiBehaviorProfile getProfile(RoguePmcRole role) {
        return profiles.get(role);
    }

    public void setProfile(RoguePmcRole role, AiBehaviorProfile profile) {
        profiles.put(role, profile.validated());
        setDirty();
    }

    public AiTuning tuning(RoguePmcRole role, int difficulty) {
        return getProfile(role).tuning(role, difficulty);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (RoguePmcRole role : RoguePmcRole.values()) tag.put(role.name(), getProfile(role).save());
        return tag;
    }

    public static AiBehaviorData load(CompoundTag tag, HolderLookup.Provider registries) {
        AiBehaviorData data = new AiBehaviorData();
        for (RoguePmcRole role : RoguePmcRole.values()) {
            if (tag.contains(role.name())) {
                data.profiles.put(role, AiBehaviorProfile.load(tag.getCompound(role.name()),
                        AiBehaviorProfile.defaults(role)));
            }
        }
        return data;
    }
}
