package com.admin82.factions.monument;

import java.util.Locale;
import net.minecraft.util.StringRepresentable;

public enum MonumentCrateType implements StringRepresentable {
    SUPPLY(0),
    AMMO(18),
    GUN(36);

    private final int poolStartSlot;

    MonumentCrateType(int poolStartSlot) {
        this.poolStartSlot = poolStartSlot;
    }

    public int getPoolStartSlot() {
        return poolStartSlot;
    }

    @Override
    public String getSerializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static MonumentCrateType parse(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}