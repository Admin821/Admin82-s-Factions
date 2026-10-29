package com.admin82.factions.entity.monument;

public record AiCombatProfile(double maximumConeDegrees, int lockOnTicks, double combatRange,
                              double minimumRange, double preferredRange, double rushRange,
                              double coverRange, double fireThreshold,
                              int shotCooldownTicks) {
    public AiCombatProfile validated() {
        double minimum = Math.clamp(minimumRange, 0.0, 256.0);
        double combat = Math.clamp(combatRange, Math.max(1.0, minimum), 256.0);
        return new AiCombatProfile(
                Math.clamp(maximumConeDegrees, 0.1, 45.0),
                Math.clamp(lockOnTicks, 1, 1200),
                combat,
                minimum,
                Math.clamp(preferredRange, minimum, combat),
                Math.clamp(rushRange, minimum, combat),
                Math.clamp(coverRange, minimum, combat),
                Math.clamp(fireThreshold, 0.05, 1.0),
                Math.clamp(shotCooldownTicks, 1, 200));
    }
}