package com.admin82.factions.war;

import com.admin82.factions.economy.Currency;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * A peace / surrender demand sent by one faction to the other during an active war.
 * If the receiver accepts, the server immediately resolves all terms.
 * Demands expire after 10 minutes if unanswered.
 */
public class WarDemand {

    public enum Status { PENDING, ACCEPTED, REJECTED, EXPIRED }

    // ── Identity ──────────────────────────────────────────────────────────────

    public UUID   demandId;
    public UUID   warId;

    public UUID   senderFactionId;
    public String senderFactionName;
    public UUID   receiverFactionId;
    public String receiverFactionName;

    public long   sentAt;
    public long   expiresAt;   // epoch ms; -1 = never
    public Status status;

    // ── Terms (any combination; all zeroed = nothing) ─────────────────────────

    /** Copper the receiver must transfer from their faction vault to the sender's vault. */
    public long moneyAmount;

    /**
     * Full registry name of an item (e.g. {@code "minecraft:diamond"}) the receiver
     * must supply from any online faction member's inventory, or "" for no item term.
     */
    public String itemId;
    /** How many of {@link #itemId} must be provided. */
    public int itemCount;

    /** Number of claimed chunks the receiver must release (taken from the most-recent claims). */
    public int landChunks;

    /** If {@code true} the receiver must become a vassal of the sender upon acceptance. */
    public boolean vassalTerm;

    // ── Constructor (used when creating a new demand) ─────────────────────────

    public WarDemand(UUID warId,
                     UUID senderFactionId, String senderFactionName,
                     UUID receiverFactionId, String receiverFactionName,
                     long moneyAmount,
                     String itemId, int itemCount,
                     int landChunks,
                     boolean vassalTerm) {
        this.demandId            = UUID.randomUUID();
        this.warId               = warId;
        this.senderFactionId     = senderFactionId;
        this.senderFactionName   = senderFactionName;
        this.receiverFactionId   = receiverFactionId;
        this.receiverFactionName = receiverFactionName;
        this.sentAt              = System.currentTimeMillis();
        this.expiresAt           = this.sentAt + 10L * 60_000L; // 10 min
        this.status              = Status.PENDING;
        this.moneyAmount         = moneyAmount;
        this.itemId              = itemId == null ? "" : itemId;
        this.itemCount           = itemCount;
        this.landChunks          = landChunks;
        this.vassalTerm          = vassalTerm;
    }

    /** Private no-arg constructor used by load / fromNetwork. */
    private WarDemand() {}

    public boolean isExpired() {
        return status == Status.PENDING && System.currentTimeMillis() > expiresAt;
    }

    /** §-formatted one-line summary of all non-zero terms. */
    public String termsSummary() {
        var sb = new StringBuilder();
        if (moneyAmount > 0)
            sb.append("§6").append(Currency.format(moneyAmount)).append(" §8| ");
        if (!itemId.isEmpty() && itemCount > 0)
            sb.append("§ax").append(itemCount).append(" §f").append(simplifyItem(itemId)).append(" §8| ");
        if (landChunks > 0)
            sb.append("§c-").append(landChunks).append(" chunks §8| ");
        if (vassalTerm)
            sb.append("§5Become vassal §8| ");
        if (sb.length() == 0) return "§8(nothing)";
        return sb.substring(0, sb.length() - 5); // trim trailing " §8| "
    }

    private static String simplifyItem(String id) {
        int colon = id.lastIndexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // ── NBT ───────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("demandId",          demandId);
        tag.putUUID("warId",             warId);
        tag.putUUID("senderFactionId",   senderFactionId);
        tag.putString("senderName",      senderFactionName);
        tag.putUUID("receiverFactionId", receiverFactionId);
        tag.putString("receiverName",    receiverFactionName);
        tag.putLong("sentAt",            sentAt);
        tag.putLong("expiresAt",         expiresAt);
        tag.putInt("status",             status.ordinal());
        tag.putLong("moneyAmount",       moneyAmount);
        tag.putString("itemId",          itemId);
        tag.putInt("itemCount",          itemCount);
        tag.putInt("landChunks",         landChunks);
        tag.putBoolean("vassalTerm",     vassalTerm);
        return tag;
    }

    public static WarDemand load(CompoundTag tag) {
        WarDemand d = new WarDemand();
        d.demandId            = tag.getUUID("demandId");
        d.warId               = tag.getUUID("warId");
        d.senderFactionId     = tag.getUUID("senderFactionId");
        d.senderFactionName   = tag.getString("senderName");
        d.receiverFactionId   = tag.getUUID("receiverFactionId");
        d.receiverFactionName = tag.getString("receiverName");
        d.sentAt              = tag.getLong("sentAt");
        d.expiresAt           = tag.getLong("expiresAt");
        d.status              = Status.values()[Math.min(tag.getInt("status"), Status.values().length - 1)];
        d.moneyAmount         = tag.getLong("moneyAmount");
        d.itemId              = tag.getString("itemId");
        d.itemCount           = tag.getInt("itemCount");
        d.landChunks          = tag.getInt("landChunks");
        d.vassalTerm          = tag.getBoolean("vassalTerm");
        return d;
    }

    // ── Network ───────────────────────────────────────────────────────────────

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeUUID(demandId);
        buf.writeUUID(warId);
        buf.writeUUID(senderFactionId);
        buf.writeUtf(senderFactionName, 64);
        buf.writeUUID(receiverFactionId);
        buf.writeUtf(receiverFactionName, 64);
        buf.writeLong(sentAt);
        buf.writeLong(expiresAt);
        buf.writeVarInt(status.ordinal());
        buf.writeLong(moneyAmount);
        buf.writeUtf(itemId, 256);
        buf.writeVarInt(itemCount);
        buf.writeVarInt(landChunks);
        buf.writeBoolean(vassalTerm);
    }

    public static WarDemand fromNetwork(FriendlyByteBuf buf) {
        WarDemand d = new WarDemand();
        d.demandId            = buf.readUUID();
        d.warId               = buf.readUUID();
        d.senderFactionId     = buf.readUUID();
        d.senderFactionName   = buf.readUtf(64);
        d.receiverFactionId   = buf.readUUID();
        d.receiverFactionName = buf.readUtf(64);
        d.sentAt              = buf.readLong();
        d.expiresAt           = buf.readLong();
        d.status              = Status.values()[Math.min(buf.readVarInt(), Status.values().length - 1)];
        d.moneyAmount         = buf.readLong();
        d.itemId              = buf.readUtf(256);
        d.itemCount           = buf.readVarInt();
        d.landChunks          = buf.readVarInt();
        d.vassalTerm          = buf.readBoolean();
        return d;
    }
}
