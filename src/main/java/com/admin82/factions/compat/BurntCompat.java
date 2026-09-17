package com.admin82.factions.compat;


import com.admin82.factions.faction.FactionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.pixelbank.burnt.util.BurntProtection;

public class BurntCompat{
    public static boolean isProtected(LevelAccessor levelAccessor, BlockPos from, BlockPos to) {
        FactionManager mngr = FactionManager.get((ServerLevel) levelAccessor);
        int x = SectionPos.blockToSectionCoord(to.getX());
        int z = SectionPos.blockToSectionCoord(to.getZ());


        return mngr.getChunkOwner(x, z, ((ServerLevel) levelAccessor).dimension().location().toString()) != null;
    }
}
