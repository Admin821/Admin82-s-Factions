package com.admin82.factions.entity.monument;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Admin-facing AI tuning for one guard class. Four 0-100 "personality" stats plus two distances.
 * Everything the AI actually uses is derived from this in {@link #tuning(RoguePmcRole, int)}, and the
 * monument's per-class difficulty (1-5) shifts every stat by {@link #DIFFICULTY_STEP} points per level.
 */
public record AiBehaviorProfile(int accuracy, int reaction, int awareness, int aggression,
                                int engagementRange, int preferredRange) {
    public static final int DIFFICULTY_STEP = 15;

    public AiBehaviorProfile validated() {
        int range = Math.clamp(engagementRange, 8, 256);
        return new AiBehaviorProfile(
                Math.clamp(accuracy, 0, 100),
                Math.clamp(reaction, 0, 100),
                Math.clamp(awareness, 0, 100),
                Math.clamp(aggression, 0, 100),
                range,
                Math.clamp(preferredRange, 2, range));
    }

    public static AiBehaviorProfile defaults(RoguePmcRole role) {
        return switch (role) {
            case SNIPER -> new AiBehaviorProfile(80, 40, 80, 20, 160, 42);
            case ASSAULT -> new AiBehaviorProfile(55, 55, 55, 60, 72, 24);
            case HEAVY_GUNNER -> new AiBehaviorProfile(40, 45, 45, 40, 96, 28);
            case LIGHT_GUNNER -> new AiBehaviorProfile(45, 70, 50, 80, 56, 16);
        };
    }

    /** Derives the concrete numbers the AI runs on, for a given monument difficulty (1-5). */
    public AiTuning tuning(RoguePmcRole role, int difficulty) {
        int shift = (Math.clamp(difficulty, 1, 5) - 3) * DIFFICULTY_STEP;
        double a = Math.clamp(accuracy + shift, 0, 100) / 100.0;
        double r = Math.clamp(reaction + shift, 0, 100) / 100.0;
        double w = Math.clamp(awareness + shift, 0, 100) / 100.0;
        double g = Math.clamp(aggression + shift / 3, 0, 100) / 100.0;
        double baseThreshold = switch (role) {
            case SNIPER -> 0.92;
            case ASSAULT -> 0.62;
            case HEAVY_GUNNER -> 0.48;
            case LIGHT_GUNNER -> 0.55;
        };
        double preferred = Math.min(preferredRange, engagementRange);
        return new AiTuning(
                40.0 + 100.0 * w,
                80.0 + 70.0 * w,
                0.5 + w,
                50.0 - 40.0 * r,
                4.0 + 26.0 * r,
                Math.max(2.0, role.acquisitionTicks() * (1.5 - r)),
                Math.max(0.2, role.maximumConeDegrees() * (1.6 - 1.4 * a)),
                Math.clamp(baseThreshold + 0.15 - 0.3 * a, 0.2, 0.98),
                Math.max(1, (int) Math.round(role.shotCooldownTicks() * (1.25 - 0.5 * r))),
                engagementRange,
                preferred,
                Math.max(3.0, preferred * (0.15 + 0.35 * g)),
                g,
                (int) Math.round(20 * (15 + 45 * g)));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(accuracy);
        buffer.writeVarInt(reaction);
        buffer.writeVarInt(awareness);
        buffer.writeVarInt(aggression);
        buffer.writeVarInt(engagementRange);
        buffer.writeVarInt(preferredRange);
    }

    public static AiBehaviorProfile read(FriendlyByteBuf buffer) {
        return new AiBehaviorProfile(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()).validated();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("accuracy", accuracy);
        tag.putInt("reaction", reaction);
        tag.putInt("awareness", awareness);
        tag.putInt("aggression", aggression);
        tag.putInt("engagementRange", engagementRange);
        tag.putInt("preferredRange", preferredRange);
        return tag;
    }

    public static AiBehaviorProfile load(CompoundTag tag, AiBehaviorProfile fallback) {
        return new AiBehaviorProfile(
                tag.contains("accuracy") ? tag.getInt("accuracy") : fallback.accuracy(),
                tag.contains("reaction") ? tag.getInt("reaction") : fallback.reaction(),
                tag.contains("awareness") ? tag.getInt("awareness") : fallback.awareness(),
                tag.contains("aggression") ? tag.getInt("aggression") : fallback.aggression(),
                tag.contains("engagementRange") ? tag.getInt("engagementRange") : fallback.engagementRange(),
                tag.contains("preferredRange") ? tag.getInt("preferredRange") : fallback.preferredRange()).validated();
    }
}
