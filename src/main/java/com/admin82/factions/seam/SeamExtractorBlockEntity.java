package com.admin82.factions.seam;

import com.admin82.factions.Config;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionUpgrades;
import com.admin82.factions.network.packet.SyncSeamExtractorPacket;
import com.admin82.factions.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Mines the seam of the chunk it stands in, using power. Ore goes into its 9 output slots, which hoppers and pipes
 * can empty. The faction's seam upgrade decides how fast it mines, how much it gives and how fast the seam wears.
 */
public class SeamExtractorBlockEntity extends BlockEntity {

    public static final int SLOTS = 9;
    /** The battery holds this many ticks of mining. */
    private static final int BUFFER_TICKS = 200;

    @Nullable public UUID faction;
    public final SimpleContainer output = new SimpleContainer(SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            SeamExtractorBlockEntity.this.setChanged();
        }
    };
    private long energy;
    /** Ticks of mining done toward the next ore. */
    private double progress;
    /** Bonus ore not yet whole (Quantity upgrade). */
    private double oreCarry;
    public String status = "Starting…";
    private boolean running;
    /** Ticks since it last mined; the beam stays on through short gaps in power. */
    private int idleTicks;

    // Client only: the rings' spin, eased up and down by the renderer, and the beam's hum
    public float ringAngle, ringSpeed;
    public long lastFrame;
    @Nullable public Object beamSound;

    // What the front screen shows (sent to clients when it changes)
    public String screenOre = "";
    public float screenProgress, screenCharge;
    public String screenState = "";
    /** Upgrade path and level: the beam's colour and width. */
    public int screenPath, screenLevel;
    private int sentSignature;

    // From the faction's upgrades, refreshed every second
    private SeamPath path = SeamPath.NONE;
    private int pathLevel;
    /** Whether it still stands on its faction's land (land can be lost to unclaiming, wars or captured outposts). */
    private boolean onLand = true;

    /** Cables can fill it from any side; nothing can take power out. */
    public final IEnergyStorage energyStorage = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int max, boolean simulate) {
            long room = capacity() - energy;
            int got = (int) Math.max(0, Math.min(room, max));
            if (!simulate && got > 0) { energy += got; setChanged(); }
            return got;
        }
        @Override public int extractEnergy(int max, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, energy); }
        @Override public int getMaxEnergyStored() { return (int) Math.min(Integer.MAX_VALUE, capacity()); }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    };

    /** Hoppers and pipes can take ore out but not put anything in. */
    public final IItemHandler items = new InvWrapper(output) {
        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stack;
        }
    };

    public SeamExtractorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SEAM_EXTRACTOR.get(), pos, state);
    }

    /** FE used per tick of mining. */
    public long powerPerTick() {
        return Config.SEAM_POWER.get() * SeamPath.powerFactor(pathLevel);
    }

    public long capacity() {
        return Math.max(10_000, powerPerTick() * BUFFER_TICKS);
    }

    public long energy() {
        return energy;
    }

    /** Ticks per extraction on this seam with the faction's upgrades. */
    public double ticksPerOre(Seam seam) {
        return Math.max(1, seam.tier.seconds() * 20.0 * path.timeFactor(pathLevel));
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SeamExtractorBlockEntity be) {
        if (!(level instanceof ServerLevel server)) return;
        if (level.getGameTime() % 20 == 0) {
            be.checkOwner(server);
            be.refreshUpgrades(server);
        }
        be.mine(server);
        be.showActive(server, pos, state);
        if (level.getGameTime() % 100 == 0) SeamExtractorBlock.placeParts(level, pos, state); // old 1x1 extractors grow their parts
        if (level.getGameTime() % 10 == 0) {
            be.syncViewers(server);
            be.updateScreen(server);
        }
    }

    /**
     * A disbanded owner leaves it unowned; an unowned extractor is taken over by the faction that claims its chunk
     * (if that faction has room for another extractor on this seam).
     */
    private void checkOwner(ServerLevel level) {
        var factions = FactionManager.get(level.getServer());
        if (faction != null && factions.getFaction(faction) == null) {
            faction = null;
            SeamData.get(level.getServer()).removeExtractor(level, worldPosition);
            setChanged();
        }
        if (faction == null) {
            ChunkPos chunk = new ChunkPos(worldPosition);
            var owner = factions.getChunkOwner(chunk.x, chunk.z, level.dimension().location().toString());
            if (owner != null) adopt(level, owner.getId());
        }
    }

    /** Makes {@code newOwner} the owner, unless it already has as many extractors on this seam as it may. */
    public boolean adopt(ServerLevel level, UUID newOwner) {
        SeamData data = SeamData.get(level.getServer());
        ChunkPos chunk = new ChunkPos(worldPosition);
        if (data.extractorsOn(level, chunk, newOwner) >= FactionUpgrades.get(level.getServer()).seamMiners(newOwner)) return false;
        faction = newOwner;
        data.addExtractor(level, worldPosition, newOwner);
        setChanged();
        refreshUpgrades(level);
        return true;
    }

    private void refreshUpgrades(ServerLevel level) {
        SeamPath oldPath = path;
        int oldLevel = pathLevel;
        var owner = faction == null ? null : FactionManager.get(level.getServer()).getFaction(faction);
        ChunkPos chunk = new ChunkPos(worldPosition);
        onLand = owner != null && owner.hasClaim(chunk.x, chunk.z, level.dimension().location().toString());
        if (owner == null) {
            path = SeamPath.NONE;
            pathLevel = 0;
        } else {
            FactionUpgrades up = FactionUpgrades.get(level.getServer());
            path = up.seamPath(faction);
            pathLevel = up.seamPathLevel(faction);
        }
        if (path != oldPath || pathLevel != oldLevel) {
            energy = Math.min(energy, capacity());
            setChanged();
        }
    }

    private void mine(ServerLevel level) {
        running = false;
        if (faction == null || FactionManager.get(level.getServer()).getFaction(faction) == null) {
            setStatus("§eUnowned: anyone in a faction can right-click it to take it over");
            return;
        }
        if (!onLand) { setStatus("§cNot on your faction's land anymore"); return; }
        SeamData data = SeamData.get(level.getServer());
        ChunkPos chunk = new ChunkPos(worldPosition);
        Seam peek = data.get(level, chunk);
        if (!peek.exists()) { setStatus("§8No seam in this chunk: find one with a Seam Viewer"); return; }
        if (!peek.minable()) { setStatus("§8This seam is depleted"); return; }
        Seam seam = data.forMining(level, chunk);
        ItemStack ore = seam.stack();
        if (!fits(ore)) { setStatus("§eOutput full: empty it or add a hopper"); return; }
        long cost = powerPerTick();
        if (energy < cost) {
            // Like a furnace going out: without power the extraction in progress winds back down, 2 ticks per tick
            if (progress > 0) { progress = Math.max(0, progress - 2); setChanged(); }
            setStatus("§cNeeds a steady " + String.format("%,d", cost) + " FE/t");
            return;
        }
        energy -= cost;
        running = true;
        setStatus("§aMining");
        progress += 1;
        double needed = ticksPerOre(seam);
        if (progress >= needed) {
            progress -= needed;
            oreCarry += path.orePerExtraction(pathLevel);
            int count = (int) Math.floor(oreCarry + 1e-9);
            oreCarry -= count;
            if (count > 0) output.addItem(ore.copyWithCount(count)); // whatever doesn't fit is lost; fits() checked one
            seam.consume(path.wearPerExtraction(pathLevel));
            data.setDirty();
        }
        setChanged();
    }

    /** Lights it up (beam, glow, sparks) while it mines; switches off after a second without mining. */
    private void showActive(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.hasProperty(SeamExtractorBlock.ACTIVE)) return;
        idleTicks = running ? 0 : idleTicks + 1;
        boolean shown = state.getValue(SeamExtractorBlock.ACTIVE);
        boolean want = running || (shown && idleTicks < 20);
        if (want != shown) level.setBlock(pos, state.setValue(SeamExtractorBlock.ACTIVE, want), 3);
    }

    /** Refreshes the front screen's readout, and sends it to nearby clients if anything on it changed. */
    private void updateScreen(ServerLevel level) {
        Seam seam = SeamData.get(level.getServer()).get(level, new ChunkPos(worldPosition));
        double ticks = seam.minable() ? ticksPerOre(seam) : 0;
        screenOre = seam.exists() ? seam.ore : "";
        screenProgress = ticks > 0 ? (float) Math.min(1, progress / ticks) : 0;
        screenCharge = capacity() > 0 ? (float) Math.min(1, energy / (double) capacity()) : 0;
        screenState = screenState();
        screenPath = path.ordinal();
        screenLevel = pathLevel;
        int signature = java.util.Objects.hash(screenOre, Math.round(screenProgress * 100), Math.round(screenCharge * 100),
                screenState, screenPath, screenLevel);
        if (signature == sentSignature) return;
        sentSignature = signature;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
    }

    /** A few words for the screen. */
    private String screenState() {
        if (running) return "MINING";
        if (status.contains("Needs a steady")) return "NO POWER";
        if (status.contains("Output full")) return "OUTPUT FULL";
        if (status.contains("No seam")) return "NO SEAM";
        if (status.contains("depleted")) return "DEPLETED";
        if (status.contains("land")) return "OFF CLAIM";
        if (status.contains("Unowned")) return "UNOWNED";
        return "IDLE";
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("ore", screenOre);
        tag.putFloat("progress", screenProgress);
        tag.putFloat("charge", screenCharge);
        tag.putString("state", screenState);
        tag.putInt("path", screenPath);
        tag.putInt("level", screenLevel);
        return tag;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        readScreen(tag);
    }

    @Override
    public void onDataPacket(net.minecraft.network.Connection net, net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet,
                             HolderLookup.Provider registries) {
        readScreen(packet.getTag());
    }

    private void readScreen(CompoundTag tag) {
        screenOre = tag.getString("ore");
        screenProgress = tag.getFloat("progress");
        screenCharge = tag.getFloat("charge");
        screenState = tag.getString("state");
        screenPath = tag.getInt("path");
        screenLevel = tag.getInt("level");
    }

    private boolean fits(ItemStack ore) {
        for (int i = 0; i < SLOTS; i++) {
            ItemStack s = output.getItem(i);
            if (s.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(s, ore) && s.getCount() + 2 <= s.getMaxStackSize()) return true;
        }
        return false;
    }

    private void setStatus(String s) {
        status = s;
    }

    /** Sends the screen's numbers to everyone who has it open. */
    private void syncViewers(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            if (p.containerMenu instanceof com.admin82.factions.menu.SeamExtractorMenu m && m.pos.equals(worldPosition)) {
                PacketDistributor.sendToPlayer(p, snapshot(level));
            }
        }
    }

    public SyncSeamExtractorPacket snapshot(ServerLevel level) {
        Seam seam = SeamData.get(level.getServer()).get(level, new ChunkPos(worldPosition));
        int miners = faction == null ? 0 : SeamData.get(level.getServer()).extractorsOn(level, new ChunkPos(worldPosition), faction);
        int limit = faction == null ? 0 : FactionUpgrades.get(level.getServer()).seamMiners(faction);
        double ticks = seam.minable() ? ticksPerOre(seam) : 0;
        return new SyncSeamExtractorPacket(worldPosition, seam.ore, seam.describe(), seam.endless, seam.tier.ordinal(),
                (long) Math.ceil(seam.remaining), seam.tier.amount(), ticks / 20.0, path.orePerExtraction(pathLevel),
                path.wearPerExtraction(pathLevel), ticks > 0 ? Math.min(1, progress / ticks) : 0, energy, capacity(),
                powerPerTick(), status, running, path.label, pathLevel, miners, limit);
    }

    // Saving

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (faction != null) tag.putUUID("faction", faction);
        tag.put("output", output.createTag(registries));
        tag.putLong("energy", energy);
        tag.putDouble("progress", progress);
        tag.putDouble("oreCarry", oreCarry);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        faction = tag.hasUUID("faction") ? tag.getUUID("faction") : null;
        output.clearContent();
        output.fromTag(tag.getList("output", 10), registries);
        energy = tag.getLong("energy");
        progress = tag.getDouble("progress");
        oreCarry = tag.getDouble("oreCarry");
    }
}
