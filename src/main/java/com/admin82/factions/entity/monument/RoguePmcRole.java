package com.admin82.factions.entity.monument;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

public enum RoguePmcRole {
    SNIPER("Rogue Sniper PMC", 40.0, 0.27, 56.0, 28.0, 42.0, 70, 34, 10.0f, 7.0f,
            List.of("tacz:ai_awp", "tacz:m700", "tacz:sks")),
    ASSAULT("Rogue Assault PMC", 32.0, 0.34, 40.0, 10.0, 24.0, 42, 9, 5.5f, 11.0f,
            List.of("tacz:m4a1", "tacz:scar_l", "tacz:ak47")),
    HEAVY_GUNNER("Rogue Heavy Gunner PMC", 48.0, 0.23, 44.0, 12.0, 28.0, 52, 5, 4.5f, 14.0f,
            List.of("tacz:m249", "tacz:rpk", "tacz:mg42")),
    LIGHT_GUNNER("Rogue Light Gunner PMC", 26.0, 0.40, 32.0, 5.0, 16.0, 32, 6, 4.0f, 13.0f,
            List.of("tacz:ump45", "tacz:mp5a5", "tacz:p90"));

    private final String displayName;
    private final double health;
    private final double speed;
    private final double range;
    private final double minimumRange;
    private final double preferredRange;
    private final int acquisitionTicks;
    private final int shotCooldownTicks;
    private final float damage;
    private final float maximumConeDegrees;
    private final List<String> gunIds;

    RoguePmcRole(String displayName, double health, double speed, double range, double minimumRange,
                 double preferredRange, int acquisitionTicks, int shotCooldownTicks, float damage,
                 float maximumConeDegrees, List<String> gunIds) {
        this.displayName = displayName;
        this.health = health;
        this.speed = speed;
        this.range = range;
        this.minimumRange = minimumRange;
        this.preferredRange = preferredRange;
        this.acquisitionTicks = acquisitionTicks;
        this.shotCooldownTicks = shotCooldownTicks;
        this.damage = damage;
        this.maximumConeDegrees = maximumConeDegrees;
        this.gunIds = gunIds;
    }

    public String displayName() { return displayName; }
    public double health() { return health; }
    public double speed() { return speed; }
    public double range() { return range; }
    public double minimumRange() { return minimumRange; }
    public double preferredRange() { return preferredRange; }
    public int acquisitionTicks() { return acquisitionTicks; }
    public int shotCooldownTicks() { return shotCooldownTicks; }
    public float damage() { return damage; }
    public float maximumConeDegrees() { return maximumConeDegrees; }
    public List<ResourceLocation> gunIds() {
        return gunIds.stream().map(ResourceLocation::parse).toList();
    }
}