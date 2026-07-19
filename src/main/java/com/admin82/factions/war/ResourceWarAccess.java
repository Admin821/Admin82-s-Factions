package com.admin82.factions.war;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/**
 * Tracks active Resource-War loot access granted to the winning faction
 * after a {@link WarType#RESOURCE} victory.
 *
 * The winning faction may:
 *  – Open container blocks in the losing faction's claimed territory.
 *  – Break up to {@link #blockLimit} blocks in that territory.
 *
 * Access automatically expires after 10 minutes.
 */
public class ResourceWarAccess {

    public UUID winnerFactionId;
    public UUID loserFactionId;
    public int  blocksBroken;
    public int  blockLimit;
    public long expiresAt;   // epoch ms

    public ResourceWarAccess() {}

    public ResourceWarAccess(UUID winnerFactionId, UUID loserFactionId, int blockLimit) {
        this.winnerFactionId = winnerFactionId;
        this.loserFactionId  = loserFactionId;
        this.blocksBroken    = 0;
        this.blockLimit      = blockLimit;
        this.expiresAt       = System.currentTimeMillis() + 10L * 60_000L; // 10 min
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    public boolean canBreak() {
        return !isExpired() && blocksBroken < blockLimit;
    }

    public int remaining() {
        return Math.max(0, blockLimit - blocksBroken);
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("winner",      winnerFactionId);
        tag.putUUID("loser",       loserFactionId);
        tag.putInt("blocksBroken", blocksBroken);
        tag.putInt("blockLimit",   blockLimit);
        tag.putLong("expiresAt",   expiresAt);
        return tag;
    }

    public static ResourceWarAccess load(CompoundTag tag) {
        ResourceWarAccess a = new ResourceWarAccess();
        a.winnerFactionId = tag.getUUID("winner");
        a.loserFactionId  = tag.getUUID("loser");
        a.blocksBroken    = tag.getInt("blocksBroken");
        a.blockLimit      = tag.getInt("blockLimit");
        a.expiresAt       = tag.getLong("expiresAt");
        return a;
    }
}
