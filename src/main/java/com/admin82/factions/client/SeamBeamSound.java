package com.admin82.factions.client;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.seam.SeamExtractorBlock;
import com.admin82.factions.seam.SeamExtractorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Seam Extractor's laser hum, looping while it mines. It fades and rises in pitch as the drill spins up, and
 * winds down the same way when it stops.
 */
public class SeamBeamSound extends AbstractTickableSoundInstance {

    public static final SoundEvent BEAM = SoundEvent.createVariableRangeEvent(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "seam_extractor_beam"));

    private final SeamExtractorBlockEntity be;
    private float power;

    private SeamBeamSound(SeamExtractorBlockEntity be) {
        super(BEAM, SoundSource.BLOCKS, net.minecraft.util.RandomSource.create());
        this.be = be;
        BlockState state = be.getBlockState();
        BlockPos o = be.getBlockPos().offset(-state.getValue(SeamExtractorBlock.CX), 0, -state.getValue(SeamExtractorBlock.CZ));
        this.x = o.getX() + 1;
        this.y = o.getY() + 1;
        this.z = o.getZ() + 1;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.01f;
        this.pitch = 0.7f;
    }

    /** Starts the hum for {@code be} unless it's already playing. */
    public static void ensurePlaying(SeamExtractorBlockEntity be) {
        if (be.beamSound instanceof SeamBeamSound s && !s.isStopped()) return;
        SeamBeamSound sound = new SeamBeamSound(be);
        be.beamSound = sound;
        Minecraft.getInstance().getSoundManager().play(sound);
    }

    @Override
    public void tick() {
        BlockState state = be.getBlockState();
        boolean active = !be.isRemoved() && state.hasProperty(SeamExtractorBlock.ACTIVE) && state.getValue(SeamExtractorBlock.ACTIVE);
        power += ((active ? 1 : 0) - power) * (active ? 0.04f : 0.06f);
        if (!active && power < 0.02f) {
            stop();
            return;
        }
        volume = 0.75f * power;
        pitch = 0.7f + 0.35f * power;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
