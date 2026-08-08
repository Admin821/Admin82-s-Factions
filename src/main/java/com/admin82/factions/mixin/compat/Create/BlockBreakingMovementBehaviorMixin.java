package com.admin82.factions.mixin.compat.Create;


import com.admin82.factions.AdminsFactions;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockBreakingMovementBehaviour.class)
public class BlockBreakingMovementBehaviorMixin {

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    public void destroyBlock(MovementContext context, BlockPos breakingPos, CallbackInfo ci){
        if (!(context.world instanceof ServerLevel level)) return;
        AdminsFactions.LOGGER.debug("destroyBlock Mixin");

        int x = SectionPos.blockToSectionCoord(breakingPos.getX());
        int z = SectionPos.blockToSectionCoord(breakingPos.getZ());
        BlockPos contraption = context.contraption.anchor;
        int cx = SectionPos.blockToSectionCoord(contraption.getX());
        int cz = SectionPos.blockToSectionCoord(contraption.getZ());

        String dim = level.dimension().location().toString();

        FactionManager fmgr = FactionManager.get(level.getServer());
        Faction claimingFaction = null;
        Faction contraptionFaction = null;
        for (Faction f : fmgr.getAllFactions().values()) {
            if (f.hasClaim(cx, cx, dim)) {contraptionFaction = f;}
            if (f.hasClaim(x, z, dim)) { claimingFaction = f; }
        }
        AdminsFactions.LOGGER.debug("Contraption Faction: {}, BlockFaction: {}", contraptionFaction, claimingFaction);
        if (claimingFaction != null && contraptionFaction == null || (claimingFaction != null && contraptionFaction != null && !claimingFaction.getId().equals(contraptionFaction.getId()))){
            AdminsFactions.LOGGER.debug("outOfFaction");
            ci.cancel();
        }
    }
}
