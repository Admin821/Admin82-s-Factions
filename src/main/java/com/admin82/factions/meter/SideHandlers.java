package com.admin82.factions.meter;

import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Views of a meter's battery or tank that only allow input or only output, for sides set that way. */
final class SideHandlers {

    private SideHandlers() {}

    static IEnergyStorage energy(IEnergyStorage inner, boolean in, boolean out) {
        return new IEnergyStorage() {
            @Override public int receiveEnergy(int amount, boolean simulate) { return in ? inner.receiveEnergy(amount, simulate) : 0; }
            @Override public int extractEnergy(int amount, boolean simulate) { return out ? inner.extractEnergy(amount, simulate) : 0; }
            @Override public int getEnergyStored() { return inner.getEnergyStored(); }
            @Override public int getMaxEnergyStored() { return inner.getMaxEnergyStored(); }
            @Override public boolean canExtract() { return out; }
            @Override public boolean canReceive() { return in; }
        };
    }

    static IFluidHandler fluid(IFluidHandler inner, boolean in, boolean out) {
        return new IFluidHandler() {
            @Override public int getTanks() { return inner.getTanks(); }
            @Override public FluidStack getFluidInTank(int tank) { return inner.getFluidInTank(tank); }
            @Override public int getTankCapacity(int tank) { return inner.getTankCapacity(tank); }
            @Override public boolean isFluidValid(int tank, FluidStack stack) { return in && inner.isFluidValid(tank, stack); }
            @Override public int fill(FluidStack resource, FluidAction action) { return in ? inner.fill(resource, action) : 0; }
            @Override public FluidStack drain(FluidStack resource, FluidAction action) { return out ? inner.drain(resource, action) : FluidStack.EMPTY; }
            @Override public FluidStack drain(int max, FluidAction action) { return out ? inner.drain(max, action) : FluidStack.EMPTY; }
        };
    }
}
