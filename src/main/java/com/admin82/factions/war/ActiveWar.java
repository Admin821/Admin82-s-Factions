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
    public WarType       warType;           // terms chosen by the attacker before the war
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

    /** Location of the ATTACKING faction table (counter-capture target for defenders). */
    @javax.annotation.Nullable public BlockPos attackerTablePos;
    public String   attackerDimension = "";
    /** Defenders' capture progress toward the attacker's faction table. */
    public float    defenderCaptureProgress = 0f;

    /** When {@code true} the current capture target is the defender's outpost, not the table. */
    public boolean  outpostPhase  = false;
    /** Outpost block position during the outpost phase (null when not in outpost phase). */
    @javax.annotation.Nullable
    public BlockPos outpostPos;
    public String   outpostDim    = "";
    /** UUID of the {@link com.admin82.factions.outpost.OutpostEntry} being contested. */
    @javax.annotation.Nullable
    public java.util.UUID outpostId;

    /**
     * For {@link WarType#TERRITORY} wars: the chunk keys ("cx,cz,dim") the attacker
     * pre-selected as their territorial prize.  Auto-transferred on attacker victory.
     */
    public final List<String> targetChunkKeys = new ArrayList<>();

    public ActiveWar(UUID warId, UUID attackerFactionId, UUID defenderFactionId,
                     WarPhase phase, WarType warType, long graceEndsAt,
                     BlockPos defenderTablePos, String defenderDimension) {
        this.warId             = warId;
        this.attackerFactionId = attackerFactionId;
        this.defenderFactionId = defenderFactionId;
        this.phase             = phase;
        this.warType           = warType != null ? warType : WarType.FIGHT;
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
        tag.putInt("warType",            warType.ordinal());
        tag.putLong("graceEndsAt",       graceEndsAt);
        tag.putFloat("captureProgress",  captureProgress);
        tag.putLong("lastTickMs",        lastTickMs);
        tag.putLong("defenderTablePos",  defenderTablePos.asLong());
        tag.putString("defenderDim",     defenderDimension);
        if (attackerTablePos != null) tag.putLong("attackerTablePos", attackerTablePos.asLong());
        tag.putString("attackerDim",     attackerDimension);
        tag.putFloat("defenderCaptureProgress", defenderCaptureProgress);
        tag.putBoolean("outpostPhase",   outpostPhase);
        if (outpostPos != null) tag.putLong("outpostPos", outpostPos.asLong());
        tag.putString("outpostDim",      outpostDim);
        if (outpostId != null) tag.putUUID("outpostId", outpostId);
        if (attackerTablePos != null) tag.putLong("attackerTablePos", attackerTablePos.asLong());
        tag.putString("attackerDim", attackerDimension);
        tag.putFloat("defCapProg", defenderCaptureProgress);

        net.minecraft.nbt.ListTag chunkList = new net.minecraft.nbt.ListTag();
        targetChunkKeys.forEach(k -> chunkList.add(net.minecraft.nbt.StringTag.valueOf(k)));
        tag.put("targetChunkKeys", chunkList);

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
                WarType.fromOrdinal(tag.getInt("warType")),
                tag.getLong("graceEndsAt"),
                BlockPos.of(tag.getLong("defenderTablePos")),
                tag.getString("defenderDim")
        );
        war.captureProgress = tag.getFloat("captureProgress");
        war.lastTickMs      = tag.getLong("lastTickMs");
        war.outpostPhase    = tag.contains("outpostPhase") && tag.getBoolean("outpostPhase");
        if (tag.contains("outpostPos")) war.outpostPos = BlockPos.of(tag.getLong("outpostPos"));
        war.outpostDim      = tag.contains("outpostDim") ? tag.getString("outpostDim") : "";
        if (tag.hasUUID("outpostId")) war.outpostId = tag.getUUID("outpostId");
        if (tag.contains("attackerTablePos")) war.attackerTablePos = BlockPos.of(tag.getLong("attackerTablePos"));
        war.attackerDimension = tag.contains("attackerDim") ? tag.getString("attackerDim") : "";
        war.defenderCaptureProgress = tag.contains("defCapProg") ? tag.getFloat("defCapProg") : 0f;

        if (tag.contains("targetChunkKeys")) {
            var chunkList = tag.getList("targetChunkKeys", net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < chunkList.size(); i++) war.targetChunkKeys.add(chunkList.getString(i));
        }

        CompoundTag al = tag.getCompound("attackerLives");
        al.getAllKeys().forEach(k -> war.attackerLives.put(UUID.fromString(k), al.getInt(k)));

        CompoundTag dl = tag.getCompound("defenderLives");
        dl.getAllKeys().forEach(k -> war.defenderLives.put(UUID.fromString(k), dl.getInt(k)));

        return war;
    }
}
