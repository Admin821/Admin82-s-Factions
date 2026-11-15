package com.admin82.factions.entity.monument;

import java.util.List;
import java.util.Locale;

/**
 * Concrete AI numbers derived from an {@link AiBehaviorProfile} and a difficulty level.
 *
 * @param visionRange        blocks at which a standing, moving player in daylight can be noticed
 * @param fieldOfViewDegrees full width of the vision cone
 * @param hearingMultiplier  scales every sound's audible radius
 * @param spotTicks          ticks to fully notice a close player in the centre of view
 * @param turnSpeed          max head turn in degrees per tick
 * @param lockOnTicks        base ticks of steady aim before shots become accurate
 * @param coneDegrees        shot spread at max range with no aim settled
 * @param fireThreshold      how settled the aim (0-1) must be before firing
 * @param shotCooldownTicks  ticks between shots
 * @param engagementRange    max distance to shoot at
 * @param preferredRange     distance the guard tries to fight from
 * @param rushRange          inside this distance the guard pushes (or a sniper repositions)
 * @param aggression         0-1, how readily the guard pushes instead of taking cover
 * @param searchTicks        how long the guard searches after losing a target
 */
public record AiTuning(double visionRange, double fieldOfViewDegrees, double hearingMultiplier,
                       double spotTicks, double turnSpeed, double lockOnTicks, double coneDegrees,
                       double fireThreshold, int shotCooldownTicks, double engagementRange,
                       double preferredRange, double rushRange, double aggression, int searchTicks) {

    /** Footsteps of a walking player, before the hearing multiplier. */
    public static final double FOOTSTEP_RADIUS = 10.0;
    public static final double GUNSHOT_RADIUS = 96.0;

    /** One row of the plain-language summary shown in the admin UI. */
    public record Stat(String group, String label, String value) {}

    /** Plain-language summary for the admin UI, grouped as Senses / Shooting / Movement. */
    public List<Stat> describe() {
        return List.of(
                new Stat("Senses", "Sees you (standing)", Math.round(visionRange) + " blocks"),
                new Stat("Senses", "Sees you (crouch / prone)", "~" + Math.round(visionRange * 0.65) + " / ~"
                        + Math.round(visionRange * 0.35) + " blocks"),
                new Stat("Senses", "Field of view", Math.round(fieldOfViewDegrees) + "°"),
                new Stat("Senses", "Time to notice you", seconds(spotTicks) + " up close"),
                new Stat("Senses", "Hears footsteps", "~" + Math.round(FOOTSTEP_RADIUS * hearingMultiplier) + " blocks"),
                new Stat("Senses", "Hears gunshots", "~" + Math.round(GUNSHOT_RADIUS * hearingMultiplier) + " blocks"),
                new Stat("Shooting", "Turn speed", Math.round(turnSpeed * 20) + "°/s"),
                new Stat("Shooting", "Aim time", seconds(lockOnTicks) + " to steady"),
                new Stat("Shooting", "Shot spread", String.format(Locale.ROOT, "%.1f°", coneDegrees)),
                new Stat("Shooting", "Shoots from", Math.round(engagementRange) + " blocks"),
                new Stat("Movement", "Fights from", "~" + Math.round(preferredRange) + " blocks"),
                new Stat("Movement", "Pushes you inside", Math.round(rushRange) + " blocks"),
                new Stat("Movement", "Searches for", seconds(searchTicks) + " after losing you"));
    }

    private static String seconds(double ticks) {
        return String.format(Locale.ROOT, "%.1fs", ticks / 20.0);
    }
}
