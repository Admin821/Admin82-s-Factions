package com.admin82.factions.war;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.*;

/**
 * Runtime state for one active war between two factions.
 * Persisted via {@link WarManager}.
 */
public class ActiveWar {

    public final UUID    warId;
    public final UUID    attackerFactionId;
    public final UUID    defenderFactionId;
    public WarPhase      phase;
    public long          graceEndsAt;       // epoch ms when grace ends

    /** Committed players: UUID → lives remaining. 0 = eliminated. */
    public final Map<UUID, Integer> attackerLives = new HashMap<>();
    public final Map<UUID, Integer> defenderLives = new HashMap<>();

    /** Seconds of uncontested attacker time accrued on the capture point. */
    public float captureProgress = 0f;
    public long  lastTickMs;

    /** Location of the defending faction table (capture-point centre). */
    public BlockPos defenderTablePos;
    public String   defenderDimension;

    public ActiveWar(UUID warId, UUID attackerFactionId, UUID defenderFactionId,
                     WarPhase phase, long graceEndsAt,
                     BlockPos defenderTablePos, String defenderDimension) {
        this.warId             = warId;
        this.attackerFactionId = attackerFactionId;
        this.defenderFactionId = defenderFactionId;
        this.phase             = phase;
        this.graceEndsAt       = graceEndsAt;
        this.defenderTablePos  = defenderTablePos;
        this.defenderDimension = defenderDimension;
        this.lastTickMs        = System.currentTimeMillis();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public int totalAttackerLives() {
        return attackerLives.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int totalDefenderLives() {
        return defenderLives.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean isAttacker(UUID player) { return attackerLives.containsKey(player); }
    public boolean isDefender(UUID player) { return defenderLives.containsKey(player); }
    public boolean isParticipant(UUID player) { return isAttacker(player) || isDefender(player); }

    /** True if every committed attacker has 0 lives left. */
    public boolean allAttackersEliminated() {
        return !attackerLives.isEmpty() && attackerLives.values().stream().allMatch(l -> l <= 0);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("warId",             warId);
        tag.putUUID("attackerFactionId", attackerFactionId);
        tag.putUUID("defenderFactionId", defenderFactionId);
        tag.putInt("phase",              phase.ordinal());
        tag.putLong("graceEndsAt",       graceEndsAt);
        tag.putFloat("captureProgress",  captureProgress);
        tag.putLong("lastTickMs",        lastTickMs);
        tag.putLong("defenderTablePos",  defenderTablePos.asLong());
        tag.putString("defenderDim",     defenderDimension);

        CompoundTag al = new CompoundTag();
        attackerLives.forEach((uuid, lives) -> al.putInt(uuid.toString(), lives));
        tag.put("attackerLives", al);

        CompoundTag dl = new CompoundTag();
        defenderLives.forEach((uuid, lives) -> dl.putInt(uuid.toString(), lives));
        tag.put("defenderLives", dl);

        return tag;
    }

    public static ActiveWar load(CompoundTag tag) {
        ActiveWar war = new ActiveWar(
                tag.getUUID("warId"),
                tag.getUUID("attackerFactionId"),
                tag.getUUID("defenderFactionId"),
                WarPhase.values()[Math.min(tag.getInt("phase"), WarPhase.values().length - 1)],
                tag.getLong("graceEndsAt"),
                BlockPos.of(tag.getLong("defenderTablePos")),
                tag.getString("defenderDim")
        );
        war.captureProgress = tag.getFloat("captureProgress");
        war.lastTickMs      = tag.getLong("lastTickMs");

        CompoundTag al = tag.getCompound("attackerLives");
        al.getAllKeys().forEach(k -> war.attackerLives.put(UUID.fromString(k), al.getInt(k)));

        CompoundTag dl = tag.getCompound("defenderLives");
        dl.getAllKeys().forEach(k -> war.defenderLives.put(UUID.fromString(k), dl.getInt(k)));

        return war;
    }
}
