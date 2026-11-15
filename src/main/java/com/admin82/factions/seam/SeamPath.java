package com.admin82.factions.seam;

/**
 * The faction's seam upgrade path. A faction picks one (Speed, Quantity or Efficiency) and is locked to it; each of
 * the 4 levels doubles the power every one of its Seam Extractors uses.
 */
public enum SeamPath {
    NONE("None"),
    /** +1x extraction speed and +0.25x depletion per level (5x faster, 2x faster wear at level 4). */
    SPEED("Speed"),
    /** +0.25 bonus ore per extraction per level (2 ore at level 4); the seam wears at the normal rate. */
    QUANTITY("Quantity"),
    /** +1x slower depletion and +0.25x slower extraction per level (5x slower wear, 2x slower mining at level 4). */
    EFFICIENCY("Efficiency");

    public static final int MAX_LEVEL = 4;

    public final String label;

    SeamPath(String label) {
        this.label = label;
    }

    public static SeamPath of(int ordinal) {
        return ordinal >= 0 && ordinal < values().length ? values()[ordinal] : NONE;
    }

    /** Time per ore is multiplied by this. */
    public double timeFactor(int level) {
        return switch (this) {
            case SPEED -> 1.0 / (1 + level);
            case EFFICIENCY -> 1 + 0.25 * level;
            default -> 1;
        };
    }

    /** Seam worn down per extraction. */
    public double wearPerExtraction(int level) {
        return switch (this) {
            case SPEED, EFFICIENCY -> (1 + 0.25 * level) / (1 + level);
            default -> 1;
        };
    }

    /** Ore given per extraction (fractions add up over time). */
    public double orePerExtraction(int level) {
        return this == QUANTITY ? 1 + 0.25 * level : 1;
    }

    /** The power multiplier: doubles every level of whichever path was picked. */
    public static long powerFactor(int level) {
        return 1L << Math.max(0, Math.min(MAX_LEVEL, level));
    }

    /** Short summary for the upgrades tab, stats separated by " · ". */
    public String stats(int level) {
        String power = "Power ×" + powerFactor(level);
        return switch (this) {
            case SPEED -> "Mining ×" + trim(1 + level) + " faster · Depletion ×" + trim(1 + 0.25 * level) + " faster · " + power;
            case QUANTITY -> "Ore per extraction: " + trim(1 + 0.25 * level) + " · Depletion: normal · " + power;
            case EFFICIENCY -> "Depletion ×" + trim(1 + level) + " slower · Mining ×" + trim(1 + 0.25 * level) + " slower · " + power;
            default -> power;
        };
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.valueOf(v);
    }
}
