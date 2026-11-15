package com.admin82.factions.meter;

/** What pipes and cables on one side of a meter's storage may do. */
public enum SideMode {
    ANY("Any", "§f"), INPUT("Input", "§9"), OUTPUT("Output", "§6");

    public final String label;
    public final String color;

    SideMode(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public SideMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static SideMode of(int i) {
        return i >= 0 && i < values().length ? values()[i] : ANY;
    }
}
