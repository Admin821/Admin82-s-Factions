package com.admin82.factions.meter;

import com.admin82.factions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Lets pipes and cables reach the meter's battery or tank (bottom block only), following its side settings, and
 * hoppers or item pipes reach its coin slots through either block.
 */
public final class MeterCapabilities {

    private MeterCapabilities() {}

    public static void register(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.METER.get(),
                (be, side) -> be.energyFor(side));
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.METER.get(),
                (be, side) -> be.fluidFor(side));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.METER.get(), (be, side) -> be.coinHandler);
        event.registerBlock(Capabilities.ItemHandler.BLOCK,
                (level, pos, state, be, side) -> level.getBlockEntity(pos.below()) instanceof MeterBlockEntity meter ? meter.coinHandler : null,
                com.admin82.factions.registry.ModBlocks.POWER_METER_TOP.get(), com.admin82.factions.registry.ModBlocks.FLUID_METER_TOP.get());
        // Seam Extractor: power in only through the energy ports on its sides, ore out to hoppers and pipes anywhere.
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.SEAM_EXTRACTOR.get(), (be, side) -> {
            BlockState s = be.getBlockState();
            if (!s.hasProperty(com.admin82.factions.seam.SeamExtractorBlock.FACING)) return be.energyStorage;
            return com.admin82.factions.seam.SeamExtractorBlock.energyPort(s, s.getValue(com.admin82.factions.seam.SeamExtractorBlock.CX), 0,
                    s.getValue(com.admin82.factions.seam.SeamExtractorBlock.CZ), side) ? be.energyStorage : null;
        });
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.SEAM_EXTRACTOR.get(), (be, side) -> be.items);
        // ...and through any of its parts, so cables and hoppers can go anywhere on the 2x2x3 machine
        event.registerBlock(Capabilities.EnergyStorage.BLOCK, (level, pos, state, be, side) -> {
            BlockPos main = com.admin82.factions.seam.SeamExtractorPartBlock.main(level, pos, state);
            if (main == null || !(level.getBlockEntity(main) instanceof com.admin82.factions.seam.SeamExtractorBlockEntity ex)) return null;
            boolean port = com.admin82.factions.seam.SeamExtractorBlock.energyPort(level.getBlockState(main),
                    state.getValue(com.admin82.factions.seam.SeamExtractorPartBlock.CX), state.getValue(com.admin82.factions.seam.SeamExtractorPartBlock.CY),
                    state.getValue(com.admin82.factions.seam.SeamExtractorPartBlock.CZ), side);
            return port ? ex.energyStorage : null;
        }, com.admin82.factions.registry.ModBlocks.SEAM_EXTRACTOR_PART.get());
        event.registerBlock(Capabilities.ItemHandler.BLOCK, (level, pos, state, be, side) -> {
            BlockPos main = com.admin82.factions.seam.SeamExtractorPartBlock.main(level, pos, state);
            return main != null && level.getBlockEntity(main) instanceof com.admin82.factions.seam.SeamExtractorBlockEntity ex ? ex.items : null;
        }, com.admin82.factions.registry.ModBlocks.SEAM_EXTRACTOR_PART.get());
        // Outpost flag: hoppers can put coins into its coin storage (taking out is for officers, in its screen).
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.OUTPOST_MANAGER.get(), (be, side) -> {
            if (!(be.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return null;
            var data = com.admin82.factions.outpost.OutpostData.get(level.getServer());
            var entry = data.getOutpostAtPos(be.getBlockPos(), level.dimension().location().toString());
            if (entry == null || entry.disintegrating) return null;
            return new net.neoforged.neoforge.items.wrapper.InvWrapper(entry.coinSlots) {
                @Override
                public net.minecraft.world.item.ItemStack insertItem(int slot, net.minecraft.world.item.ItemStack stack, boolean simulate) {
                    var left = super.insertItem(slot, stack, simulate);
                    if (!simulate && left.getCount() != stack.getCount()) data.setDirty();
                    return left;
                }

                @Override
                public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
                    return net.minecraft.world.item.ItemStack.EMPTY;
                }
            };
        });
    }
}
