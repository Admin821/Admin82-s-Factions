package com.admin82.factions.entity.monument;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.EnumMap;
import java.util.Map;

public final class AiCombatProfileData extends SavedData {
    public static final int DIFFICULTY_COUNT = 5;
    public static final String[] DIFFICULTY_NAMES = {"Easy", "Relaxed", "Normal", "Hard", "Extreme"};
    private static final String DATA_NAME = "adminsfactions_ai_combat_profiles";

    private final Map<RoguePmcRole, AiCombatProfile[]> profiles = new EnumMap<>(RoguePmcRole.class);

    public AiCombatProfileData() {
        for (RoguePmcRole role : RoguePmcRole.values()) {
            AiCombatProfile[] roleProfiles = new AiCombatProfile[DIFFICULTY_COUNT];
            for (int difficulty = 1; difficulty <= DIFFICULTY_COUNT; difficulty++) {
                roleProfiles[difficulty - 1] = defaultProfile(role, difficulty);
            }
            profiles.put(role, roleProfiles);
        }
    }

    public static AiCombatProfileData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(AiCombatProfileData::new, AiCombatProfileData::load, null), DATA_NAME);
    }

    public AiCombatProfile getProfile(RoguePmcRole role, int difficulty) {
        return profiles.get(role)[Math.clamp(difficulty, 1, DIFFICULTY_COUNT) - 1];
    }

    public void setProfile(RoguePmcRole role, int difficulty, AiCombatProfile profile) {
        profiles.get(role)[Math.clamp(difficulty, 1, DIFFICULTY_COUNT) - 1] = profile.validated();
        setDirty();
    }

    public static AiCombatProfile defaultProfile(RoguePmcRole role, int difficulty) {
        double scale = (Math.clamp(difficulty, 1, DIFFICULTY_COUNT) - 3) / 2.0;
        double threshold = switch (role) {
            case SNIPER -> 0.92;
            case ASSAULT -> 0.62;
            case HEAVY_GUNNER -> 0.48;
            case LIGHT_GUNNER -> 0.55;
        };
        double range = switch (role) {
            case SNIPER -> 160.0;
            case HEAVY_GUNNER -> 96.0;
            case ASSAULT -> 72.0;
            case LIGHT_GUNNER -> 56.0;
        };
        return new AiCombatProfile(
                role.maximumConeDegrees() * (1.0 - 0.15 * scale),
                Math.max(1, (int) Math.round(role.acquisitionTicks() / (1.0 + 0.35 * scale))),
                range,
                role.minimumRange(),
                role.preferredRange(),
                role.minimumRange(),
                role.preferredRange(),
                Math.clamp(threshold - 0.05 * scale, 0.05, 1.0),
                Math.max(1, (int) Math.round(role.shotCooldownTicks() * (1.0 - 0.3 * scale))));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (RoguePmcRole role : RoguePmcRole.values()) {
            CompoundTag roleTag = new CompoundTag();
            for (int difficulty = 1; difficulty <= DIFFICULTY_COUNT; difficulty++) {
                AiCombatProfile profile = getProfile(role, difficulty);
                CompoundTag profileTag = new CompoundTag();
                profileTag.putDouble("cone", profile.maximumConeDegrees());
                profileTag.putInt("lockTicks", profile.lockOnTicks());
                profileTag.putDouble("range", profile.combatRange());
                profileTag.putDouble("minimumRange", profile.minimumRange());
                profileTag.putDouble("preferredRange", profile.preferredRange());
                profileTag.putDouble("rushRange", profile.rushRange());
                profileTag.putDouble("coverRange", profile.coverRange());
                profileTag.putDouble("fireThreshold", profile.fireThreshold());
                profileTag.putInt("cooldownTicks", profile.shotCooldownTicks());
                roleTag.put(Integer.toString(difficulty), profileTag);
            }
            tag.put(role.name(), roleTag);
        }
        return tag;
    }

    public static AiCombatProfileData load(CompoundTag tag, HolderLookup.Provider registries) {
        AiCombatProfileData data = new AiCombatProfileData();
        for (RoguePmcRole role : RoguePmcRole.values()) {
            CompoundTag roleTag = tag.getCompound(role.name());
            for (int difficulty = 1; difficulty <= DIFFICULTY_COUNT; difficulty++) {
                String key = Integer.toString(difficulty);
                if (!roleTag.contains(key)) continue;
                CompoundTag profileTag = roleTag.getCompound(key);
                data.setProfile(role, difficulty, new AiCombatProfile(
                        profileTag.getDouble("cone"), profileTag.getInt("lockTicks"),
                        profileTag.getDouble("range"), profileTag.getDouble("minimumRange"),
                        profileTag.getDouble("preferredRange"),
                        profileTag.contains("rushRange") ? profileTag.getDouble("rushRange") : profileTag.getDouble("minimumRange"),
                        profileTag.contains("coverRange") ? profileTag.getDouble("coverRange") : profileTag.getDouble("preferredRange"),
                        profileTag.getDouble("fireThreshold"),
                        profileTag.getInt("cooldownTicks")));
            }
        }
        data.setDirty(false);
        return data;
    }
}