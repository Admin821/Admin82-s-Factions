package com.admin82.factions.faction;

import com.admin82.factions.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/**
 * A claimed chunk.
 *
 * @param dailyCost        upkeep per day; also what the chunk deed originally cost
 * @param chunkloadDeed    the faction has bought a chunkloading deed for this chunk
 * @param chunkloadEnabled chunkloading is switched on (only takes effect with a deed)
 */
public record LandClaim(int chunkX, int chunkZ, ResourceLocation dimension, long dailyCost,
                        boolean chunkloadDeed, boolean chunkloadEnabled) {

    public LandClaim(int chunkX, int chunkZ, ResourceLocation dimension, long dailyCost) {
        this(chunkX, chunkZ, dimension, dailyCost, false, false);
    }

    /** Backward-compat constructor (zero daily cost). */
    public LandClaim(int chunkX, int chunkZ, ResourceLocation dimension) {
        this(chunkX, chunkZ, dimension, 0L);
    }

    public LandClaim(ChunkPos pos, ResourceLocation dimension) {
        this(pos.x, pos.z, dimension, 0L);
    }

    public ChunkPos chunkPos() {
        return new ChunkPos(chunkX, chunkZ);
    }

    public boolean matches(int x, int z, String dim) {
        return chunkX == x && chunkZ == z && dimension.toString().equals(dim);
    }

    // Chunkloading

    /** The chunk's original price. Claims stored with no cost fall back to the base claim cost. */
    public long chunkloadBasis() {
        return dailyCost > 0 ? dailyCost : Config.CLAIM_COST_COPPER.get();
    }

    /** One-time price of the chunkloading deed: twice the chunk's original cost. */
    public long chunkloadDeedCost() {
        return chunkloadBasis() * 2L;
    }

    /** True when this chunk should currently be force-loaded (deed owned and switched on). */
    public boolean isChunkloading() {
        return chunkloadDeed && chunkloadEnabled;
    }

    /** Upkeep actually charged per day: doubled while chunkloading is on. */
    public long effectiveDailyCost() {
        return isChunkloading() ? dailyCost + chunkloadBasis() : dailyCost;
    }

    public LandClaim withDailyCost(long cost) {
        return new LandClaim(chunkX, chunkZ, dimension, cost, chunkloadDeed, chunkloadEnabled);
    }

    public LandClaim withChunkload(boolean deed, boolean enabled) {
        return new LandClaim(chunkX, chunkZ, dimension, dailyCost, deed, enabled);
    }

    // Serialisation

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", chunkX);
        tag.putInt("z", chunkZ);
        tag.putString("dim", dimension.toString());
        tag.putLong("dailyCost", dailyCost);
        tag.putBoolean("chunkloadDeed", chunkloadDeed);
        tag.putBoolean("chunkloadEnabled", chunkloadEnabled);
        return tag;
    }

    public static LandClaim load(CompoundTag tag) {
        return new LandClaim(
                tag.getInt("x"),
                tag.getInt("z"),
                ResourceLocation.parse(tag.getString("dim")),
                tag.getLong("dailyCost"), // 0 for old data, treated as free
                tag.getBoolean("chunkloadDeed"),
                tag.getBoolean("chunkloadEnabled")
        );
    }

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeResourceLocation(dimension);
        buf.writeLong(dailyCost);
        buf.writeBoolean(chunkloadDeed);
        buf.writeBoolean(chunkloadEnabled);
    }

    public static LandClaim fromNetwork(FriendlyByteBuf buf) {
        return new LandClaim(buf.readInt(), buf.readInt(), buf.readResourceLocation(), buf.readLong(),
                buf.readBoolean(), buf.readBoolean());
    }

    @Override
    public String toString() {
        return "(" + chunkX + ", " + chunkZ + ") in " + dimension.getPath();
    }
}
