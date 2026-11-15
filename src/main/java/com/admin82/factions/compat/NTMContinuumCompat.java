package com.admin82.factions.compat;

import com.admin82.factions.faction.FactionManager;
import dev.golem.core.world.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public class NTMContinuumCompat implements Protection.Provider {

    @Override
    public Protection.Coverage coverage(Level level, int chunkX, int chunkZ, Protection.Source source, Protection.Cause cause) {
        FactionManager mngr = FactionManager.get((ServerLevel) level);

        return mngr.getChunkOwner(chunkX, chunkZ, level.dimension().location().toString()) != null ? Protection.Coverage.CLOSED : Protection.Coverage.OPEN;
    }

    @Override
    public boolean mayAlter(Level level, BlockPos pos, Protection.Source source, Protection.Cause cause) {
        FactionManager mngr = FactionManager.get((ServerLevel) level);
        int x = SectionPos.blockToSectionCoord(pos.getX());
        int z = SectionPos.blockToSectionCoord(pos.getZ());

        return mngr.getChunkOwner(x, z, level.dimension().location().toString()) != null;
    }

}
