package com.admin82.factions.meter;

import com.admin82.factions.Config;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionUpgrades;
import com.admin82.factions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.registry.ModItems;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.EnergyStorage;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * The bottom half of a power or fluid meter: its battery or tank (open to pipes and cables), plus what the
 * meter is doing. A supplier meter's stock is sold on its listing; a buyer meter has a secret maximum price and
 * is connected automatically to a listing at or under it, paying from its coin storage.
 */
public class MeterBlockEntity extends BlockEntity {

    public enum Role { NONE, SUPPLIER, CONSUMER }

    public final MeterKind kind;
    @Nullable public UUID owner;
    public String ownerName = "";
    public Role role = Role.NONE;
    @Nullable public UUID networkId;
    /** Coin slots: what the meter buys with. Only coins go in. */
    public static final int COIN_SLOTS = com.admin82.factions.economy.CoinSlots.SIZE;
    public final SimpleContainer coinSlots = com.admin82.factions.economy.CoinSlots.create(this::setChanged);
    /** Most copper the coin storage holds (grows with Meter Efficiency); refreshed every second. */
    public long coinCap = Config.METER_COIN_CAP.get();
    /** Sales not yet worth a whole copper, in millionths of a copper. */
    private long saleMicro;
    /** Buyers: the most they'll pay, {@code maxPriceCopper} per {@code maxPerUnits} (-1 = not set yet). Never shown to others. */
    public long maxPriceCopper = -1;
    public long maxPerUnits = 1000;
    /** Buyers: part of a copper already paid but not used yet, in millionths of a copper. */
    private long payMicro;
    /** Buyers: game time they connected to their current seller. */
    public long connectedSince;
    /** Fluid buyers: id of the fluid they buy ("" = not picked yet). */
    public String wantFluid = "";
    /** Buyers: next tick to look for a (different) seller. */
    private long nextSearch;
    /** Coins earned minus coins spent since the last second, in millionths of a copper. */
    private long flowMicro;
    /** Smoothed coin flow in millionths of a copper per second (+ earning, - spending), over about the last half minute. */
    private double coinRateMicro;
    /** Last coin-storage warning sent to the owner: 0 none, 1 nearly full, 2 full. */
    private int coinWarning;
    /**
     * The coin slots for hoppers, pipes and Create funnels, from any side of either block: only coins go in, and
     * only as many as fit under {@link #coinCap}. Anything can take coins out (land claims protect the meter).
     */
    public final net.neoforged.neoforge.items.IItemHandler coinHandler = new net.neoforged.neoforge.items.wrapper.InvWrapper(coinSlots) {
        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            long each = EconomyManager.stackCopper(stack.copyWithCount(1));
            if (each <= 0) return stack;
            long fits = coinRoom() / each;
            if (fits <= 0) return stack;
            if (fits >= stack.getCount()) return super.insertItem(slot, stack, simulate);
            ItemStack left = super.insertItem(slot, stack.copyWithCount((int) fits), simulate);
            return stack.copyWithCount(stack.getCount() - (int) fits + left.getCount());
        }
    };
    /** Copper that didn't fit in the coin slots (change, or coins from before the slots), moved in when there's room. */
    public long coins;
    /** Money owed to the network owner from before sales went into supplier meters; paid to their wallet. */
    private long owed;
    /** Why the last purchase couldn't happen, shown in the GUI. */
    public String status = "";
    /**
     * Side settings for the front, left, back and right (as you look at the screen). In the model these are the
     * blue, green, yellow and pink ports. The top and bottom faces always allow both.
     */
    private final SideMode[] sides = {SideMode.ANY, SideMode.ANY, SideMode.ANY, SideMode.ANY};
    /** What players last saw in the tank (see {@link #syncTank}). */
    private int syncedAmount = -1;
    private int syncedCapacity = -1;
    private FluidStack syncedFluid = FluidStack.EMPTY;
    /** Set after the first size check, so a freshly loaded meter is resized right away. */
    private boolean sizeChecked;

    // What the PC screen shows (sent to players; on the client these hold the last values received)
    /** Short state for the screen, e.g. "§aSELLING" or "§cOUT OF COINS". */
    public String screenState = "";
    /** The network's name, "" when not on one. */
    public String screenNetwork = "";
    /** Coins moving per hour in copper: + earned (selling), - spent (buying). */
    public long screenRate;
    /** Coins in the meter's coin storage, in copper. */
    public long screenCoins;
    private int screenSignature;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    /** Whatever is next to each side, looked up once and kept fresh by NeoForge. */
    @Nullable private BlockCapabilityCache<IEnergyStorage, Direction>[] energyNeighbours;
    @Nullable private BlockCapabilityCache<IFluidHandler, Direction>[] fluidNeighbours;

    public final MeterEnergy energy;
    public final FluidTank tank;

    public MeterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.METER.get(), pos, state);
        this.kind = state.getBlock() instanceof MeterBlock mb ? mb.kind : MeterKind.POWER;
        this.energy = new MeterEnergy(Config.METER_POWER_CAPACITY.get()) {
            @Override
            public int receiveEnergy(int toReceive, boolean simulate) {
                int r = super.receiveEnergy(toReceive, simulate);
                if (r > 0 && !simulate) setChanged();
                return r;
            }

            @Override
            public int extractEnergy(int toExtract, boolean simulate) {
                int r = super.extractEnergy(toExtract, simulate);
                if (r > 0 && !simulate) setChanged();
                return r;
            }
        };
        this.tank = new FluidTank(Config.METER_FLUID_CAPACITY.get()) {
            @Override
            protected void onContentsChanged() {
                setChanged();
            }
        };
    }

    /** A battery whose size can change (it grows with the owner's Meter Efficiency). */
    public static class MeterEnergy extends EnergyStorage {
        MeterEnergy(int capacity) {
            super(capacity, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        void setCapacity(int newCapacity) {
            capacity = newCapacity;
            if (energy > capacity) energy = capacity;
        }

        /** Client side: what the server says is stored (for the glowing wires). */
        void setStored(int stored) {
            energy = Math.max(0, Math.min(capacity, stored));
        }
    }

    /** {@code base} raised by the Meter Efficiency bonus: each level above 1 adds the configured percent. */
    public static long scaled(long base, int efficiency) {
        return base * (100L + bonusPercent(efficiency)) / 100L;
    }

    /** Total bonus for a Meter Efficiency level, in percent (level 1 = 0). */
    public static int bonusPercent(int efficiency) {
        return Math.max(0, efficiency - 1) * Config.METER_EFFICIENCY_PERCENT.get();
    }

    /** Battery or tank size for a Meter Efficiency level. */
    public long capacityFor(int efficiency) {
        long base = kind == MeterKind.POWER ? Config.METER_POWER_CAPACITY.get() : Config.METER_FLUID_CAPACITY.get();
        return Math.min(Integer.MAX_VALUE, scaled(base, efficiency));
    }

    /** Resizes the battery or tank to match the owner's faction upgrades. Anything over a smaller size is lost. */
    private void updateCapacity(MinecraftServer server) {
        int eff = efficiency(server);
        long cap = scaled(Config.METER_COIN_CAP.get(), eff);
        if (cap != coinCap) { coinCap = cap; setChanged(); }
        int size = (int) capacityFor(eff);
        if (size == capacity()) return;
        if (kind == MeterKind.POWER) {
            energy.setCapacity(size);
        } else {
            tank.setCapacity(size);
            if (tank.getFluidAmount() > size) tank.getFluid().setAmount(size);
        }
        setChanged();
    }

    // Sides

    public SideMode side(int i) {
        return sides[i];
    }

    public void setSide(int i, SideMode mode) {
        sides[i] = mode;
        setChanged();
        if (level != null) level.invalidateCapabilities(worldPosition); // pipes pick up the change
    }

    /** The world direction of side {@code i}, turned to match where the meter faces. */
    public Direction worldSide(int i) {
        Direction facing = getBlockState().hasProperty(MeterBlock.FACING) ? getBlockState().getValue(MeterBlock.FACING) : Direction.NORTH;
        return switch (i) {
            case 0 -> facing;
            case 1 -> facing.getClockWise();
            case 2 -> facing.getOpposite();
            default -> facing.getCounterClockWise();
        };
    }

    @Nullable
    private SideMode modeFor(@Nullable Direction face) {
        for (int i = 0; i < SIDES.length; i++) if (worldSide(i) == face) return sides[i];
        return null; // top, bottom, or no side
    }

    /** What a cable on {@code face} gets. */
    @Nullable
    public IEnergyStorage energyFor(@Nullable Direction face) {
        if (kind != MeterKind.POWER) return null;
        SideMode m = modeFor(face);
        return m == null || m == SideMode.ANY ? energy : SideHandlers.energy(energy, m == SideMode.INPUT, m == SideMode.OUTPUT);
    }

    /** What a pipe on {@code face} gets. */
    @Nullable
    public IFluidHandler fluidFor(@Nullable Direction face) {
        if (kind != MeterKind.FLUID) return null;
        SideMode m = modeFor(face);
        return m == null || m == SideMode.ANY ? tank : SideHandlers.fluid(tank, m == SideMode.INPUT, m == SideMode.OUTPUT);
    }

    /** Empties the battery or tank. */
    public void dump() {
        if (kind == MeterKind.POWER) energy.extractEnergy(energy.getEnergyStored(), false);
        else tank.setFluid(FluidStack.EMPTY);
        setChanged();
    }

    // Stock

    public long stored() {
        return kind == MeterKind.POWER ? energy.getEnergyStored() : tank.getFluidAmount();
    }

    public long capacity() {
        return kind == MeterKind.POWER ? energy.getMaxEnergyStored() : tank.getCapacity();
    }

    /** What's in the tank, or "" for power / an empty tank. */
    public String contentName() {
        if (kind == MeterKind.POWER || tank.isEmpty()) return "";
        return tank.getFluid().getHoverName().getString();
    }

    // Coins

    /** Everything the meter has to buy with, in copper. */
    public long coinBalance() {
        long total = coins;
        for (int i = 0; i < coinSlots.getContainerSize(); i++) total += EconomyManager.stackCopper(coinSlots.getItem(i));
        return total;
    }

    /** Sets what the meter has to spend: the slots are refilled with as few coins as possible. */
    private void setCoinBalance(long copper) {
        coins = com.admin82.factions.economy.CoinSlots.fill(coinSlots, copper);
        setChanged();
    }

    /** Exchanges the coins up (copper into silver and so on) and moves loose copper in when there's room. */
    private void refillCoinSlots() {
        long left = com.admin82.factions.economy.CoinSlots.consolidate(coinSlots, coins);
        if (left != coins) { coins = left; setChanged(); }
    }

    /** Whether the coin storage is at its cap: a supplier in that state stops selling until it's emptied. */
    public boolean coinsFull() {
        return coinBalance() >= coinCap;
    }

    /** How much more copper fits in the coin storage. */
    public long coinRoom() {
        return Math.max(0, coinCap - coinBalance());
    }

    /** A supplier is paid {@code micro} millionths of a copper for what it delivered, into its coin storage. */
    private void earn(MinecraftServer server, MeterNetworks.Network network, long micro) {
        if (micro <= 0) return;
        flowMicro += micro;
        saleMicro += micro;
        long copper = saleMicro / MeterNetworks.MICRO;
        saleMicro %= MeterNetworks.MICRO;
        if (copper <= 0) { setChanged(); return; }
        coins += copper;
        network.earned += copper;
        refillCoinSlots();
        checkCoinWarning(server, network);
    }

    /** Tells the owner (if online) once when the coin storage passes 80%, and again when it's full. */
    private void checkCoinWarning(MinecraftServer server, @Nullable MeterNetworks.Network network) {
        long balance = coinBalance();
        int state = balance >= coinCap ? 2 : balance * 5 >= coinCap * 4 ? 1 : 0;
        if (state <= coinWarning) { coinWarning = state; return; }
        coinWarning = state;
        setChanged();
        if (owner == null) return;
        net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null) return;
        String where = (kind == MeterKind.POWER ? "power" : "fluid") + " meter at " + worldPosition.getX() + ", "
                + worldPosition.getY() + ", " + worldPosition.getZ() + (network != null ? " (" + network.name + ")" : "");
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(state == 2
                ? "§c[Meter] Your " + where + " is full of coins and has stopped selling. Collect them from its Coin storage."
                : "§e[Meter] Your " + where + " is 80% full of coins. Collect them from its Coin storage."), false);
    }

    /**
     * How long until the coin storage is empty (buying) or full (selling) at the current rate, e.g. "Full in 2h 15m".
     * "" when coins aren't really moving.
     */
    public String coinEta() {
        double perSecond = coinRateMicro / MeterNetworks.MICRO;
        if (Math.abs(perSecond) < 1.0 / 3600) return ""; // less than a copper an hour
        long balance = coinBalance();
        if (perSecond > 0) {
            if (balance >= coinCap) return "§cFull";
            return "§7Full in §f" + duration((coinCap - balance) / perSecond) + " §8(+" + perHour(perSecond) + "/h)";
        }
        if (balance <= 0) return "§cEmpty";
        return "§7Empty in §f" + duration(balance / -perSecond) + " §8(-" + perHour(-perSecond) + "/h)";
    }

    private static String perHour(double perSecond) {
        return com.admin82.factions.economy.Currency.format(Math.max(1, Math.round(perSecond * 3600))).replaceAll("§.", "");
    }

    private static String duration(double seconds) {
        long s = (long) Math.ceil(seconds);
        if (s < 60) return "under a minute";
        long m = s / 60, h = m / 60, d = h / 24;
        if (d > 0) return d + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + (m % 60) + "m";
        return m + "m";
    }

    /** Takes every coin out (for when the meter is broken), in copper. */
    public long takeAllCoins() {
        long total = coinBalance();
        coinSlots.clearContent();
        coins = 0;
        setChanged();
        return total;
    }

    // Buying (consumers)

    /** Meter efficiency of the owner's faction (1 = base rate). */
    public int efficiency(MinecraftServer server) {
        if (owner == null) return 1;
        Faction f = FactionManager.get(server).getFactionForPlayer(owner);
        return f == null ? 1 : FactionUpgrades.get(server).meterLevel(f.getId());
    }

    /** Most this meter buys per tick. */
    public long ratePerTick(MinecraftServer server) {
        int eff = efficiency(server);
        return kind == MeterKind.POWER ? scaled(Config.METER_POWER_RATE.get(), eff)
                : Math.max(1, scaled(Config.METER_FLUID_RATE.get(), eff) / 20);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MeterBlockEntity be) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        MinecraftServer server = serverLevel.getServer();
        if (!be.sizeChecked || level.getGameTime() % 20 == 0) {
            be.updateCapacity(server);
            be.sizeChecked = true;
        }
        if (be.role == Role.CONSUMER) MeterMarket.queue(be); // buyers are served in order, see MeterMarket
        if (level.getGameTime() % 5 == 0) be.syncTank();
        be.pushOut(serverLevel);
        if (be.owed > 0 && level.getGameTime() % 100 == 0) be.payOwner(server);
        if (level.getGameTime() % 20 == 0) {
            be.updateScreen(server);
            be.refillCoinSlots(); // also exchanges coins put in by hand or by hoppers
            be.coinRateMicro += (be.flowMicro - be.coinRateMicro) / 30.0;
            be.flowMicro = 0;
            if (be.coinWarning > 0) be.checkCoinWarning(server, null); // clears once it's been emptied
        }
    }

    /**
     * Output sides push into the block next to them, like a battery or tank from any other mod. Plenty of
     * cables and pipes never pull, so without this they'd get nothing.
     */
    @SuppressWarnings("unchecked")
    private void pushOut(ServerLevel level) {
        if (stored() <= 0) return;
        for (int i = 0; i < SIDES.length; i++) {
            if (sides[i] != SideMode.OUTPUT) continue;
            Direction dir = worldSide(i);
            if (kind == MeterKind.POWER) {
                if (energyNeighbours == null) energyNeighbours = new BlockCapabilityCache[SIDES.length];
                if (energyNeighbours[i] == null) energyNeighbours[i] = BlockCapabilityCache.create(
                        Capabilities.EnergyStorage.BLOCK, level, worldPosition.relative(dir), dir.getOpposite());
                IEnergyStorage target = energyNeighbours[i].getCapability();
                if (target == null || !target.canReceive()) continue;
                int offer = energy.extractEnergy(energy.getEnergyStored(), true);
                int accepted = target.receiveEnergy(offer, false);
                if (accepted > 0) energy.extractEnergy(accepted, false);
                if (energy.getEnergyStored() <= 0) return;
            } else {
                if (fluidNeighbours == null) fluidNeighbours = new BlockCapabilityCache[SIDES.length];
                if (fluidNeighbours[i] == null) fluidNeighbours[i] = BlockCapabilityCache.create(
                        Capabilities.FluidHandler.BLOCK, level, worldPosition.relative(dir), dir.getOpposite());
                IFluidHandler target = fluidNeighbours[i].getCapability();
                if (target == null) continue;
                FluidStack offer = tank.drain(tank.getFluidAmount(), IFluidHandler.FluidAction.SIMULATE);
                int accepted = target.fill(offer, IFluidHandler.FluidAction.EXECUTE);
                if (accepted > 0) tank.drain(accepted, IFluidHandler.FluidAction.EXECUTE);
                if (tank.isEmpty()) return;
            }
        }
    }

    /** Registry id of a fluid, like "minecraft:water". */
    public static String fluidId(FluidStack stack) {
        return stack.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString();
    }

    /** The name of the fluid this buyer wants, for messages. */
    private String wantName() {
        var id = net.minecraft.resources.ResourceLocation.tryParse(wantFluid);
        var fluid = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.FLUID.get(id);
        return fluid == null || fluid == net.minecraft.world.level.material.Fluids.EMPTY ? wantFluid
                : new FluidStack(fluid, 1).getHoverName().getString();
    }

    /**
     * Step 1 of buying, run for every buyer by {@link MeterMarket} each tick: keeps the meter connected to a seller
     * at or under its maximum price (switching when its seller can't deliver and another can) and returns how much
     * it wants this tick (limited by speed, space and coins). 0 if it can't buy now; {@link #status} says why.
     */
    long requestAmount(MinecraftServer server) {
        if (isRemoved() || level == null) return 0;
        MeterNetworks all = MeterNetworks.get(server);
        if (maxPriceCopper < 0) { // joined a network before price limits: keep paying up to its price
            MeterNetworks.Network old = all.get(networkId);
            maxPriceCopper = old != null ? old.priceCopper : 0;
            maxPerUnits = old != null ? old.perUnits : 1000;
            setChanged();
        }
        if (kind == MeterKind.FLUID) {
            if (wantFluid.isEmpty() && !tank.isEmpty()) { wantFluid = fluidId(tank.getFluid()); setChanged(); }
            if (wantFluid.isEmpty()) { setStatus("Pick which fluid to buy (Max price → Fluid)."); return 0; }
            if (!tank.isEmpty() && !fluidId(tank.getFluid()).equals(wantFluid)) {
                setStatus("Your tank has " + contentName() + " in it. Empty it (bottom block → Dump) to buy " + wantName() + ".");
                return 0;
            }
        }
        long want = Math.min(ratePerTick(server), capacity() - stored());
        if (want <= 0) { setStatus(""); return 0; }
        long maxMicro = MeterNetworks.micro(maxPriceCopper, maxPerUnits);

        MeterNetworks.Network current = all.get(networkId);
        if (current != null && (MeterNetworks.micro(current.priceCopper, current.perUnits) > maxMicro
                || current.kind != kind || !MeterNetworks.allowed(server, current, owner))) {
            connect(null); // their price went above ours, or we can't buy from them any more
            current = null;
        } else if (current == null && networkId != null) {
            connect(null); // the listing was closed
        }
        if (current != null && sellable(server, current, MeterNetworks.micro(current.priceCopper, current.perUnits)) > 0) {
            return affordable(current, want);
        }

        // The current seller has nothing (or there isn't one): look for another, once a second.
        long now = level.getGameTime();
        if (now < nextSearch) return 0;
        nextSearch = now + 20;
        String[] reason = {""};
        MeterNetworks.Network other = findSeller(server, all, maxMicro, current, reason);
        if (other != null) {
            connect(other);
            return affordable(other, want);
        }
        setStatus(current != null ? unavailable(server, current) + " No other seller at your price has any." : reason[0]);
        return 0;
    }

    /** {@code want}, cut down to what the coins pay for at this listing's price. */
    private long affordable(MeterNetworks.Network network, long want) {
        long micro = MeterNetworks.micro(network.priceCopper, network.perUnits);
        if (micro > 0) {
            long canPay = (payMicro + coinBalance() * MeterNetworks.MICRO) / micro;
            if (canPay <= 0) { setStatus("Out of coins: put more in to keep buying."); return 0; }
            want = Math.min(want, canPay);
        }
        setStatus("");
        return want;
    }

    /** What the connected listing can sell to this meter right now (shared by every buyer of the same listing and fluid). */
    long sellerStock(MinecraftServer server) {
        MeterNetworks.Network network = MeterNetworks.get(server).get(networkId);
        return network == null ? 0 : sellable(server, network, MeterNetworks.micro(network.priceCopper, network.perUnits));
    }

    /** Step 2: takes this buyer's share of its seller's stock and pays for it. */
    void receive(MinecraftServer server, long amount) {
        MeterNetworks all = MeterNetworks.get(server);
        MeterNetworks.Network network = all.get(networkId);
        if (network != null && amount > 0) deliverFrom(server, all, network, amount);
    }

    void connect(@Nullable MeterNetworks.Network network) {
        networkId = network == null ? null : network.id;
        connectedSince = network == null || level == null ? 0 : level.getGameTime();
        setChanged();
    }

    /** The cheapest listing (oldest first on a tie) this meter may buy from that can deliver right now. */
    @Nullable
    private MeterNetworks.Network findSeller(MinecraftServer server, MeterNetworks all, long maxMicro,
                                             @Nullable MeterNetworks.Network skip, String[] reason) {
        MeterNetworks.Network best = null;
        long bestMicro = Long.MAX_VALUE;
        int listed = 0, inRange = 0;
        long cheapest = Long.MAX_VALUE;
        String why = "";
        for (MeterNetworks.Network n : all.all()) {
            if (n.kind != kind || n == skip || !MeterNetworks.allowed(server, n, owner)) continue;
            listed++;
            long micro = MeterNetworks.micro(n.priceCopper, n.perUnits);
            cheapest = Math.min(cheapest, micro);
            if (micro > maxMicro) continue;
            inRange++;
            if (sellable(server, n, micro) <= 0) { if (why.isEmpty()) why = unavailable(server, n); continue; }
            if (micro < bestMicro) { best = n; bestMicro = micro; }
        }
        String what = kind == MeterKind.POWER ? "power" : "fluid";
        reason[0] = listed == 0 ? "Nobody is selling " + what + " right now."
                : inRange == 0 ? "No seller at or under your max price. The cheapest is " + MeterNetworks.per1000(cheapest)
                        + " per 1000 " + kind.unit + "."
                : why;
        return best;
    }

    /** How much a listing can sell to this meter right now (loaded suppliers with room for coins and the right fluid). */
    private long sellable(MinecraftServer server, MeterNetworks.Network network, long micro) {
        long available = 0;
        for (GlobalPos g : network.suppliers) {
            MeterBlockEntity s = supplier(server, g, network.id);
            if (s == null || (micro > 0 && s.coinsFull()) || !fluidFits(s)) continue;
            available += s.stored();
        }
        return available;
    }

    /** Fluid buyers only take the fluid they picked (and that fits the tank). */
    private boolean fluidFits(MeterBlockEntity s) {
        if (kind != MeterKind.FLUID) return true;
        if (s.tank.isEmpty() || !fluidId(s.tank.getFluid()).equals(wantFluid)) return false;
        return tank.isEmpty() || FluidStack.isSameFluidSameComponents(s.tank.getFluid(), tank.getFluid());
    }

    /** Why a listing can't sell to this meter right now. */
    private String unavailable(MinecraftServer server, MeterNetworks.Network network) {
        int loaded = 0, full = 0, otherFluid = 0;
        long micro = MeterNetworks.micro(network.priceCopper, network.perUnits);
        for (GlobalPos g : network.suppliers) {
            MeterBlockEntity s = supplier(server, g, network.id);
            if (s == null) continue;
            loaded++;
            if (micro > 0 && s.coinsFull()) full++;
            else if (!fluidFits(s) && s.stored() > 0) otherFluid++;
        }
        if (network.suppliers.isEmpty()) return network.name + " has no supplier meters.";
        if (loaded == 0) return network.name + " isn't loaded (nobody is near it). A Chunkloading Deed keeps it running.";
        if (full == loaded) return network.name + "'s coin storage is full. Its owner needs to collect.";
        if (otherFluid > 0) return network.name + " doesn't have " + wantName() + ".";
        return network.name + " has run out.";
    }

    /** Buys up to {@code want} from a listing. Returns what was bought, 0 if it had nothing, -1 if out of coins. */
    private long deliverFrom(MinecraftServer server, MeterNetworks all, MeterNetworks.Network network, long want) {
        long micro = MeterNetworks.micro(network.priceCopper, network.perUnits);
        long deliver = Math.min(want, sellable(server, network, micro));
        if (deliver <= 0) return 0;
        if (micro > 0) {
            long affordable = (payMicro + coinBalance() * MeterNetworks.MICRO) / micro;
            deliver = Math.min(deliver, affordable);
            if (deliver <= 0) return -1;
        }

        // Take it from the suppliers' storage, in order; each is paid for what it gave.
        long moved = 0;
        for (GlobalPos g : network.suppliers) {
            if (moved >= deliver) break;
            MeterBlockEntity s = supplier(server, g, network.id);
            if (s == null || (micro > 0 && s.coinsFull()) || !fluidFits(s)) continue;
            long left = deliver - moved;
            long got;
            if (kind == MeterKind.POWER) {
                int take = s.energy.extractEnergy((int) Math.min(Integer.MAX_VALUE, left), true);
                got = energy.receiveEnergy(take, false);
                s.energy.extractEnergy((int) got, false);
            } else {
                if (s.tank.isEmpty()) continue;
                FluidStack drained = s.tank.drain((int) Math.min(Integer.MAX_VALUE, left), IFluidHandler.FluidAction.SIMULATE);
                got = tank.fill(drained, IFluidHandler.FluidAction.EXECUTE);
                s.tank.drain((int) got, IFluidHandler.FluidAction.EXECUTE);
            }
            moved += got;
            s.earn(server, network, got * micro);
        }
        if (moved <= 0) return 0;
        pay(moved * micro);
        network.unitsSold += moved;
        all.recordSale(kind, kind == MeterKind.FLUID ? wantFluid : "", moved, micro);
        all.changed();
        setChanged();
        return moved;
    }

    /** Takes {@code micro} millionths of a copper from the coin storage (whole coppers; the rest is kept for next time). */
    private void pay(long micro) {
        if (micro <= 0) return;
        flowMicro -= micro;
        if (micro > payMicro) {
            long copper = (micro - payMicro + MeterNetworks.MICRO - 1) / MeterNetworks.MICRO;
            setCoinBalance(coinBalance() - copper);
            payMicro += copper * MeterNetworks.MICRO;
        }
        payMicro -= micro;
    }

    @Nullable
    private static MeterBlockEntity supplier(MinecraftServer server, GlobalPos g, UUID network) {
        ServerLevel lvl = server.getLevel(g.dimension());
        if (lvl == null || !lvl.isLoaded(g.pos())) return null;
        return lvl.getBlockEntity(g.pos()) instanceof MeterBlockEntity s && s.role == Role.SUPPLIER && network.equals(s.networkId) ? s : null;
    }

    /** Pays what's owed to the network owner. */
    public void payOwner(MinecraftServer server) {
        if (owed <= 0) return;
        MeterNetworks.Network network = MeterNetworks.get(server).get(networkId);
        if (network != null) {
            EconomyManager.get(server).addWallet(network.owner, owed, (kind == MeterKind.POWER ? "Power" : "Fluid") + " sales on " + network.name);
        }
        owed = 0;
        setChanged();
    }

    /** Works out what the PC screen should say and sends it to players when it changed. */
    private void updateScreen(MinecraftServer server) {
        MeterNetworks.Network network = MeterNetworks.get(server).get(networkId);
        screenNetwork = network == null ? "" : network.name;
        screenRate = Math.round(coinRateMicro * 3600 / MeterNetworks.MICRO);
        screenState = screenState();
        screenCoins = coinBalance();
        int sig = java.util.Objects.hash(screenState, screenNetwork, screenRate, screenCoins);
        if (sig == screenSignature || level == null) return;
        screenSignature = sig;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    private String screenState() {
        if (role == Role.NONE || networkId == null) return "§8NOT CONNECTED";
        if (role == Role.SUPPLIER) {
            if (coinsFull()) return "§cCOINS FULL";
            return stored() <= 0 ? "§7NOTHING TO SELL" : screenRate > 0 ? "§aSELLING" : "§7READY TO SELL";
        }
        // Buyer: the status line says why it's stuck, in a sentence; the screen gets a short version
        if (status.startsWith("Out of coins")) return "§cOUT OF COINS";
        if (status.startsWith("Pick which fluid")) return "§ePICK A FLUID";
        if (status.startsWith("Your tank has")) return "§cWRONG FLUID";
        if (!status.isEmpty()) return "§cNO SELLER";
        if (stored() >= capacity() && capacity() > 0) return "§aFULL";
        return screenRate < 0 ? "§eBUYING" : "§7WAITING";
    }

    private void setStatus(String s) {
        if (!s.equals(status)) { status = s; setChanged(); }
    }

    // Saving

    /**
     * Sends the tank to nearby players when it has visibly changed (about 1% of a block's height, or a new fluid),
     * so the fluid drawn inside the meter rises and falls.
     */
    private void syncTank() {
        if (level == null) return;
        int amount = (int) Math.min(Integer.MAX_VALUE, stored());
        int cap = (int) Math.min(Integer.MAX_VALUE, capacity());
        boolean sameFluid = FluidStack.isSameFluidSameComponents(tank.getFluid(), syncedFluid) || (tank.isEmpty() && syncedFluid.isEmpty());
        if (sameFluid && cap == syncedCapacity && Math.abs(amount - syncedAmount) < Math.max(1, cap / 100)
                && (amount == 0) == (syncedAmount == 0)) return;
        syncedAmount = amount;
        syncedCapacity = cap;
        syncedFluid = tank.getFluid().copy();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    /** Only the tank or battery level goes to clients; the rest (owner, coins, listing) stays on the server. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("screenState", screenState);
        tag.putString("screenNetwork", screenNetwork);
        tag.putLong("screenRate", screenRate);
        tag.putLong("screenCoins", screenCoins);
        tag.putInt("role", role.ordinal());
        if (kind == MeterKind.FLUID) {
            tag.put("tank", tank.writeToNBT(registries, new CompoundTag()));
            tag.putInt("tankCapacity", tank.getCapacity());
        } else {
            tag.putInt("energy", energy.getEnergyStored());
            tag.putInt("energyCapacity", energy.getMaxEnergyStored());
        }
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        screenState = tag.getString("screenState");
        screenNetwork = tag.getString("screenNetwork");
        screenRate = tag.getLong("screenRate");
        screenCoins = tag.getLong("screenCoins");
        role = Role.values()[Math.max(0, Math.min(Role.values().length - 1, tag.getInt("role")))];
        if (tag.contains("energyCapacity")) {
            energy.setCapacity(tag.getInt("energyCapacity"));
            energy.setStored(tag.getInt("energy"));
            return;
        }
        if (tag.contains("tankCapacity")) tank.setCapacity(tag.getInt("tankCapacity"));
        tank.setFluid(FluidStack.EMPTY);
        if (tag.contains("tank")) tank.readFromNBT(registries, tag.getCompound("tank"));
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, ClientboundBlockEntityDataPacket packet, HolderLookup.Provider registries) {
        handleUpdateTag(packet.getTag(), registries);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("owner", owner);
        tag.putString("ownerName", ownerName);
        tag.putString("role", role.name());
        if (networkId != null) tag.putUUID("network", networkId);
        tag.putLong("coins", coins);
        tag.putLong("coinCap", coinCap);
        tag.putLong("saleMicro", saleMicro);
        tag.putLong("maxPrice", maxPriceCopper);
        tag.putLong("maxPer", maxPerUnits);
        tag.putLong("payMicro", payMicro);
        tag.putLong("connectedSince", connectedSince);
        tag.putString("wantFluid", wantFluid);
        tag.putInt("coinWarning", coinWarning);
        tag.put("coinSlots", coinSlots.createTag(registries));
        tag.putLong("owed", owed);
        tag.put("energy", energy.serializeNBT(registries));
        tag.put("tank", tank.writeToNBT(registries, new CompoundTag()));
        int[] s = new int[sides.length];
        for (int i = 0; i < s.length; i++) s[i] = sides[i].ordinal();
        tag.putIntArray("sides", s);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        ownerName = tag.getString("ownerName");
        try { role = Role.valueOf(tag.getString("role")); } catch (IllegalArgumentException e) { role = Role.NONE; }
        networkId = tag.hasUUID("network") ? tag.getUUID("network") : null;
        coins = tag.getLong("coins");
        if (tag.contains("coinCap")) coinCap = tag.getLong("coinCap");
        saleMicro = tag.getLong("saleMicro");
        maxPriceCopper = tag.contains("maxPrice") ? tag.getLong("maxPrice") : -1;
        maxPerUnits = tag.contains("maxPer") ? Math.max(1, tag.getLong("maxPer")) : 1000;
        payMicro = tag.getLong("payMicro");
        connectedSince = tag.getLong("connectedSince");
        wantFluid = tag.getString("wantFluid");
        coinWarning = tag.getInt("coinWarning");
        coinSlots.clearContent();
        if (tag.contains("coinSlots")) coinSlots.fromTag(tag.getList("coinSlots", 10), registries);
        owed = tag.getLong("owed");
        if (tag.contains("energy")) energy.deserializeNBT(registries, tag.get("energy"));
        tank.readFromNBT(registries, tag.getCompound("tank"));
        int[] s = tag.getIntArray("sides");
        for (int i = 0; i < sides.length; i++) sides[i] = i < s.length ? SideMode.of(s[i]) : SideMode.ANY;
    }
}
