package com.admin82.factions.block;

import com.admin82.factions.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One of the three invisible "ghost" blocks that fill the remaining corners of the
 * 2×2 Faction Table footprint.  The main (north-west) block is a {@link FactionTableBlock};
 * these fillers have no block entity of their own — they redirect interaction and
 * cascade removal back to the main block.
 */
public class FactionTableFillerBlock extends Block {

    public static final MapCodec<FactionTableFillerBlock> CODEC = simpleCodec(FactionTableFillerBlock::new);

    // ── Part enum ─────────────────────────────────────────────────────────────

    /**
     * Which corner of the 2×2 footprint this filler occupies, relative to the
     * north-west main block.
     * <pre>
     *   [MAIN]  [NE]
     *   [ SW ]  [SE]
     * </pre>
     * (+X = East, +Z = South in Minecraft)
     */
    public enum Part implements StringRepresentable {
        NE("ne"),  // main is one block to the West  (−X)
        SW("sw"),  // main is one block to the North (−Z)
        SE("se");  // main is one block to the West and North (−X, −Z)

        private final String name;
        Part(String n) { this.name = n; }

        @Override
        public String getSerializedName() { return name; }

        /** Returns the world position of the main (NW) block given this filler's position. */
        public BlockPos getMainPos(BlockPos fillerPos) {
            return switch (this) {
                case NE -> fillerPos.west();
                case SW -> fillerPos.north();
                case SE -> fillerPos.west().north();
            };
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    // ── Block setup ───────────────────────────────────────────────────────────

    @Override
    protected MapCodec<? extends Block> codec() { return CODEC; }

    public FactionTableFillerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PART, Part.NE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }

    /** Fillers are invisible — the main block renders the entire 2×2 model. */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    // Prevent filler blocks from occluding adjacent block faces.
    @Override
    public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    public boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    /** Redirect right-click (empty hand) to the main faction table block. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        BlockPos mainPos = state.getValue(PART).getMainPos(pos);
        BlockState mainState = level.getBlockState(mainPos);
        if (mainState.is(ModBlocks.FACTION_TABLE.get())) {
            BlockHitResult mainHit = new BlockHitResult(
                    hit.getLocation(), hit.getDirection(), mainPos, hit.isInside());
            return mainState.useWithoutItem(level, player, mainHit);
        }
        return InteractionResult.PASS;
    }

    // ── Removal cascade ───────────────────────────────────────────────────────

    /**
     * When this filler is removed, cascade-remove the main block.
     * {@link FactionTableBlock#onRemove} will then clean up the remaining fillers.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            BlockPos mainPos = state.getValue(PART).getMainPos(pos);
            if (level.getBlockState(mainPos).is(ModBlocks.FACTION_TABLE.get())) {
                level.removeBlock(mainPos, false);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
