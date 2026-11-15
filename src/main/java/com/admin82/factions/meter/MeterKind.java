package com.admin82.factions.meter;

/** What a meter measures and sells. */
public enum MeterKind {
    POWER("FE", "Power"),
    FLUID("mB", "Fluid");

    public final String unit;
    public final String label;

    MeterKind(String unit, String label) {
        this.unit = unit;
        this.label = label;
    }
}
