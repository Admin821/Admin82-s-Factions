package com.admin82.factions.outpost;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side state for a single placed Outpost.
 */
public class OutpostEntry {

    // ── Identity ──────────────────────────────────────────────────────────────
    public final UUID     id;
    public UUID           ownerFactionId;

    // ── Block location ────────────────────────────────────────────────────────
    public final BlockPos managerPos;
    public final String   dimension;

    // ── Capture (KOTH) ────────────────────────────────────────────────────────
    @Nullable public UUID  capturingFactionId; // faction currently standing on the point
    public float           captureProgress;    // seconds of uncontested presence (0..CAPTURE_TIME)

    // ── Upkeep ────────────────────────────────────────────────────────────────
    public long            upkeepNextDue;      // epoch-ms when next 500c charge is due

    // ── Disintegration ───────────────────────────────────────────────────────
    public boolean         disintegrating;
    public long            disintegrateStartMs;
    public final List<BlockPos> structureBlocks = new ArrayList<>();

    // ── Constants ─────────────────────────────────────────────────────────────
    /** Upkeep interval: 24 real hours. */
    public static final long  UPKEEP_INTERVAL_MS = 86_400_000L;
    /** Upkeep cost per interval in copper (5 silver). */
    public static final long  UPKEEP_COST_COPPER = 500L;
    /** Seconds of continuous presence needed to capture an outpost (1 minutes). */
    public static final float CAPTURE_TIME_SECONDS = 60f;
    /** Block radius counted as the capture zone around the manager block. */
    public static final int   CAPTURE_RADIUS_BLOCKS = 10;
    /** Disintegration duration in milliseconds (1 hour). */
    public static final long  DISINTEGRATE_MS = 3_600_000L;

    // ── Constructor ───────────────────────────────────────────────────────────

    public OutpostEntry(UUID id, UUID ownerFactionId, BlockPos managerPos,
                        String dimension, List<BlockPos> structureBlocks) {
        this.id              = id;
        this.ownerFactionId  = ownerFactionId;
        this.managerPos      = managerPos;
        this.dimension       = dimension;
        this.upkeepNextDue   = System.currentTimeMillis() + UPKEEP_INTERVAL_MS;
        this.structureBlocks.addAll(structureBlocks);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id",             id);
        tag.putUUID("owner",          ownerFactionId);
        tag.putInt ("x",              managerPos.getX());
        tag.putInt ("y",              managerPos.getY());
        tag.putInt ("z",              managerPos.getZ());
        tag.putString("dim",          dimension);
        if (capturingFactionId != null) tag.putUUID("capturing", capturingFactionId);
        tag.putFloat("captureProgress", captureProgress);
        tag.putLong("upkeepNextDue",  upkeepNextDue);
        tag.putBoolean("disintegrating", disintegrating);
        tag.putLong("disintegrateStart", disintegrateStartMs);

        ListTag blocks = new ListTag();
        for (BlockPos bp : structureBlocks) {
            blocks.add(LongTag.valueOf(bp.asLong()));
        }
        tag.put("structureBlocks", blocks);
        return tag;
    }

    public static OutpostEntry load(CompoundTag tag) {
        UUID id      = tag.getUUID("id");
        UUID owner   = tag.getUUID("owner");
        BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
        String dim   = tag.getString("dim");

        List<BlockPos> blocks = new ArrayList<>();
        ListTag list = tag.getList("structureBlocks", Tag.TAG_LONG);
        for (int i = 0; i < list.size(); i++) {
            blocks.add(BlockPos.of(((LongTag) list.get(i)).getAsLong()));
        }

        OutpostEntry e = new OutpostEntry(id, owner, pos, dim, blocks);
        if (tag.hasUUID("capturing"))   e.capturingFactionId  = tag.getUUID("capturing");
        e.captureProgress   = tag.getFloat("captureProgress");
        e.upkeepNextDue     = tag.getLong("upkeepNextDue");
        e.disintegrating    = tag.getBoolean("disintegrating");
        e.disintegrateStartMs = tag.getLong("disintegrateStart");
        return e;
    }
}
