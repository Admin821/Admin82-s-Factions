package com.admin82.factions.seam;

import com.admin82.factions.Config;

/**
 * How rich a seam is. Mining one down to nothing drops it to the next tier (Overflowing → Rich → Middling → Poor →
 * Depleted). Endless seams keep their tier forever.
 */
public enum SeamTier {
    OVERFLOWING("Overflowing", "Exceptional", "§d"),
    RICH("Rich", "Accelerated", "§b"),
    MIDDLING("Middling", "Middling", "§a"),
    POOR("Poor", "Poor", "§e"),
    DEPLETED("Depleted", "Depleted", "§8");

    public final String label;
    /** Its name as the speed of an endless seam ("Exceptional" rather than "Overflowing"). */
    public final String speedLabel;
    public final String color;

    SeamTier(String label, String speedLabel, String color) {
        this.label = label;
        this.speedLabel = speedLabel;
        this.color = color;
    }

    /** Seconds per ore before upgrades (0 for depleted). */
    public int seconds() {
        return this == DEPLETED ? 0 : Config.SEAM_SECONDS[ordinal()].get();
    }

    /** Ore in a full seam of this tier (0 for depleted). */
    public long amount() {
        return this == DEPLETED ? 0 : Config.SEAM_AMOUNT[ordinal()].get();
    }

    /** The tier it drops to once mined out. */
    public SeamTier next() {
        return this == DEPLETED ? DEPLETED : values()[ordinal() + 1];
    }

    /** The tier above (for admins); overflowing stays overflowing. */
    public SeamTier previous() {
        return this == OVERFLOWING ? OVERFLOWING : values()[ordinal() - 1];
    }

    public static SeamTier byName(String name) {
        for (SeamTier t : values()) if (t.name().equalsIgnoreCase(name) || t.label.equalsIgnoreCase(name)) return t;
        return DEPLETED;
    }
}
