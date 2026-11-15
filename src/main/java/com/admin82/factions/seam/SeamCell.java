package com.admin82.factions.seam;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/** One chunk's seam as sent to a client for a seam map. */
public record SeamCell(int chunkX, int chunkZ, String ore, int tier, boolean endless, long remaining, long amount, long scannedAt) {

    /** Map key of a chunk. */
    public long key() {
        return ChunkPos.asLong(chunkX, chunkZ);
    }

    /** The same cell, stamped with when it was scanned. */
    public SeamCell scanned(long time) {
        return new SeamCell(chunkX, chunkZ, ore, tier, endless, remaining, amount, time);
    }

    /** Whether the chunk has a seam (minable or depleted). */
    public boolean hasSeam() {
        return !ore.isEmpty();
    }

    public boolean minable() {
        return !ore.isEmpty() && tier != SeamTier.DEPLETED.ordinal();
    }

    public SeamTier seamTier() {
        return SeamTier.values()[Math.max(0, Math.min(SeamTier.values().length - 1, tier))];
    }

    public static SeamCell of(ChunkPos chunk, Seam seam) {
        return new SeamCell(chunk.x, chunk.z, seam.exists() ? seam.ore : "", seam.minable() ? seam.tier.ordinal() : SeamTier.DEPLETED.ordinal(),
                seam.endless, (long) Math.ceil(seam.remaining), seam.tier.amount(), 0);
    }

    /** The seams in a square of {@code size} chunks (odd) centred on a chunk, row by row from the north-west. */
    public static List<SeamCell> area(ServerLevel level, ChunkPos center, int size) {
        SeamData data = SeamData.get(level.getServer());
        int half = size / 2;
        List<SeamCell> cells = new ArrayList<>(size * size);
        for (int dz = -half; dz <= half; dz++) {
            for (int dx = -half; dx <= half; dx++) {
                ChunkPos c = new ChunkPos(center.x + dx, center.z + dz);
                cells.add(of(c, data.get(level, c)));
            }
        }
        return cells;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(chunkX); buf.writeVarInt(chunkZ); buf.writeUtf(ore, 256); buf.writeVarInt(tier);
        buf.writeBoolean(endless); buf.writeVarLong(remaining); buf.writeVarLong(amount); buf.writeLong(scannedAt);
    }

    public static SeamCell read(FriendlyByteBuf buf) {
        return new SeamCell(buf.readVarInt(), buf.readVarInt(), buf.readUtf(256), buf.readVarInt(), buf.readBoolean(),
                buf.readVarLong(), buf.readVarLong(), buf.readLong());
    }

    public static void writeList(FriendlyByteBuf buf, List<SeamCell> cells) {
        buf.writeVarInt(cells.size());
        cells.forEach(c -> c.write(buf));
    }

    public net.minecraft.nbt.CompoundTag save() {
        var t = new net.minecraft.nbt.CompoundTag();
        t.putInt("x", chunkX); t.putInt("z", chunkZ); t.putString("ore", ore); t.putInt("tier", tier);
        t.putBoolean("endless", endless); t.putLong("remaining", remaining); t.putLong("amount", amount); t.putLong("at", scannedAt);
        return t;
    }

    public static SeamCell load(net.minecraft.nbt.CompoundTag t) {
        return new SeamCell(t.getInt("x"), t.getInt("z"), t.getString("ore"), t.getInt("tier"), t.getBoolean("endless"),
                t.getLong("remaining"), t.getLong("amount"), t.getLong("at"));
    }

    public static List<SeamCell> readList(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), 4096);
        List<SeamCell> cells = new ArrayList<>(n);
        for (int i = 0; i < n; i++) cells.add(read(buf));
        return cells;
    }
}
