package com.admin82.factions.outpost;

import com.admin82.factions.Config;
import com.admin82.factions.storage.ItemVault;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Server-side state for a single placed Outpost.
 */
public class OutpostEntry {

    // Identity
    public final UUID     id;
    public UUID           ownerFactionId;

    // Block location
    public final BlockPos managerPos;
    public final String   dimension;

    // Capture (KOTH)
    @Nullable public UUID  capturingFactionId; // faction currently standing on the point
    public float           captureProgress;    // seconds of uncontested presence (0..CAPTURE_TIME)

    // Upkeep
    public long            upkeepNextDue;      // epoch-ms when next 500c charge is due

    /** When upkeep was first missed (epoch ms), or 0 while supplied. Unsupplied for too long, the outpost falls apart. */
    public long            unsuppliedSince;

    // Level, flag health, coins and storage
    public int             level = 1;
    /** Flag health. Players of a faction that can capture the outpost knock off 1 per break. */
    public int             hp;
    /** When the flag was last hit (epoch ms); it repairs fully after a while without hits. */
    public long            lastHitAt;
    @Nullable public UUID  lastHitBy;
    /** Coin storage of the outpost; upkeep is paid from it. Copper that didn't fit in the slots is in {@link #coins}. */
    public final net.minecraft.world.SimpleContainer coinSlots = com.admin82.factions.economy.CoinSlots.create(() -> {});
    /** Copper outside the coin slots (from before the slots existed, or that didn't fit). */
    public long            coins;
    public final ItemVault storage = new ItemVault();
    @Nullable public BlockPos storageBoxPos;

    // Disintegration
    public boolean         disintegrating;
    public long            disintegrateStartMs;
    public final List<BlockPos> structureBlocks = new ArrayList<>();

    // Constants
    /** Upkeep interval: 24 real hours. */
    public static final long  UPKEEP_INTERVAL_MS = 86_400_000L;
    /** How often unpaid upkeep is retried while the outpost is unsupplied. */
    public static final long  UPKEEP_RETRY_MS = 3_600_000L;
    /** Default capture time; the real one is config war.outpostCaptureTimeSeconds. */
    public static final float CAPTURE_TIME_SECONDS = 60f;
    /** Disintegration duration in milliseconds (1 hour). */
    public static final long  DISINTEGRATE_MS = 3_600_000L;

    public static final int MAX_LEVEL = 3;

    /** Everything in the coin storage, in copper. */
    public long coinBalance() {
        return coins + com.admin82.factions.economy.CoinSlots.value(coinSlots);
    }

    /** Sets the coin storage to {@code copper}, with as few coins as possible. */
    public void setCoinBalance(long copper) {
        coins = com.admin82.factions.economy.CoinSlots.fill(coinSlots, copper);
    }

    /** Exchanges coins up (copper into silver and so on). Returns whether anything changed. */
    public boolean consolidateCoins() {
        long before = coinBalance();
        java.util.List<net.minecraft.world.item.ItemStack> old = new java.util.ArrayList<>();
        for (int i = 0; i < coinSlots.getContainerSize(); i++) old.add(coinSlots.getItem(i).copy());
        coins = com.admin82.factions.economy.CoinSlots.fill(coinSlots, before);
        for (int i = 0; i < old.size(); i++) if (!net.minecraft.world.item.ItemStack.matches(old.get(i), coinSlots.getItem(i))) return true;
        return false;
    }

    /** Empties the coin storage; returns what it held in copper. */
    public long takeAllCoins() {
        long total = coinBalance();
        coinSlots.clearContent();
        coins = 0;
        return total;
    }

    public int maxHp() {
        int base = Config.OUTPOST_FLAG_HP.get();
        return base + base * (level - 1) / 2;
    }

    public int storageKinds() {
        return Config.OUTPOST_STORAGE_KINDS.get() + 5 * (level - 1);
    }

    public long storagePerKind() {
        return (long) Config.OUTPOST_STORAGE_PER_KIND.get() << (level - 1);
    }

    /** Warbonds to go up from the current level. */
    public int upgradeCost() {
        return Config.OUTPOST_UPGRADE_WARBONDS.get() * level;
    }

    /** The outpost's name, chosen by its owners. */
    public String name = "Outpost";

    /** The chunks this outpost claims: its own, then a plus shape at level 2, then 3×3 at level 3. */
    public static List<long[]> chunkOffsets(int level) {
        List<long[]> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int ring = Math.abs(dx) + Math.abs(dz);
                if (ring == 0 || level >= 2 && ring == 1 || level >= 3) out.add(new long[]{dx, dz});
            }
        }
        return out;
    }

    /** Capture area radius in blocks (config outposts.captureRadiusBlocks). */
    public static int captureRadius() {
        return Config.OUTPOST_CAPTURE_RADIUS_BLOCKS.get();
    }

    public boolean isUnsupplied() { return unsuppliedSince > 0 && !disintegrating; }

    /** When the unsupplied period ends and the outpost starts falling apart. */
    public long unsuppliedUntil() {
        return unsuppliedSince + Config.OUTPOST_UNSUPPLIED_GRACE_HOURS.get() * 3_600_000L;
    }

    // Constructor

    public OutpostEntry(UUID id, UUID ownerFactionId, BlockPos managerPos,
                        String dimension, List<BlockPos> structureBlocks) {
        this.id              = id;
        this.ownerFactionId  = ownerFactionId;
        this.managerPos      = managerPos;
        this.dimension       = dimension;
        this.upkeepNextDue   = System.currentTimeMillis() + UPKEEP_INTERVAL_MS;
        this.hp              = maxHp();
        this.structureBlocks.addAll(structureBlocks);
    }

    // NBT

    public CompoundTag save(HolderLookup.Provider registries) {
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
        tag.putLong("unsuppliedSince", unsuppliedSince);
        tag.putInt("level", level);
        tag.putString("name", name);
        tag.putInt("hp", hp);
        tag.putLong("lastHitAt", lastHitAt);
        if (lastHitBy != null) tag.putUUID("lastHitBy", lastHitBy);
        tag.putLong("coins", coins);
        tag.put("coinSlots", coinSlots.createTag(registries));
        tag.put("storage", storage.save(registries));
        if (storageBoxPos != null) tag.putLong("storageBox", storageBoxPos.asLong());

        ListTag blocks = new ListTag();
        for (BlockPos bp : structureBlocks) {
            blocks.add(LongTag.valueOf(bp.asLong()));
        }
        tag.put("structureBlocks", blocks);
        return tag;
    }

    public static OutpostEntry load(CompoundTag tag, HolderLookup.Provider registries) {
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
        e.unsuppliedSince   = tag.getLong("unsuppliedSince");
        e.level             = Math.max(1, Math.min(MAX_LEVEL, tag.contains("level") ? tag.getInt("level") : 1));
        if (!tag.contains("level")) {
            // Outposts from before upkeep came from their own coins: give owners a day to put coins in.
            e.upkeepNextDue = Math.max(e.upkeepNextDue, System.currentTimeMillis() + UPKEEP_INTERVAL_MS);
        }
        e.hp                = tag.contains("hp") ? tag.getInt("hp") : e.maxHp();
        if (tag.contains("name")) e.name = tag.getString("name");
        e.lastHitAt         = tag.getLong("lastHitAt");
        if (tag.hasUUID("lastHitBy")) e.lastHitBy = tag.getUUID("lastHitBy");
        e.coins             = tag.getLong("coins");
        if (tag.contains("coinSlots")) e.coinSlots.fromTag(tag.getList("coinSlots", Tag.TAG_COMPOUND), registries);
        e.consolidateCoins();
        var loaded = ItemVault.load(tag.getCompound("storage"), registries);
        for (var item : loaded.takeAll()) e.storage.putBack(item);
        if (tag.contains("storageBox")) e.storageBoxPos = BlockPos.of(tag.getLong("storageBox"));
        return e;
    }
}
