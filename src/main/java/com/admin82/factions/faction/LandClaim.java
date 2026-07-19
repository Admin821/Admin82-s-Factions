package com.admin82.factions.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

public record LandClaim(int chunkX, int chunkZ, ResourceLocation dimension, long dailyCost) {

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

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", chunkX);
        tag.putInt("z", chunkZ);
        tag.putString("dim", dimension.toString());
        tag.putLong("dailyCost", dailyCost);
        return tag;
    }

    public static LandClaim load(CompoundTag tag) {
        return new LandClaim(
                tag.getInt("x"),
                tag.getInt("z"),
                ResourceLocation.parse(tag.getString("dim")),
                tag.getLong("dailyCost") // 0 for old data — treated as free
        );
    }

    public void toNetwork(FriendlyByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeResourceLocation(dimension);
        buf.writeLong(dailyCost);
    }

    public static LandClaim fromNetwork(FriendlyByteBuf buf) {
        return new LandClaim(buf.readInt(), buf.readInt(), buf.readResourceLocation(), buf.readLong());
    }

    @Override
    public String toString() {
        return "(" + chunkX + ", " + chunkZ + ") in " + dimension.getPath();
    }
}
