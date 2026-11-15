package com.admin82.factions.seam;

import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionUpgrades;
import com.admin82.factions.menu.SeamExtractorMenu;
import com.admin82.factions.registry.ModBlockEntities;
import com.admin82.factions.registry.ModBlocks;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Seam Extractor: mines the seam of the chunk it's in. A faction can only have as many on one seam as its
 * Extractor Quantity upgrade allows.
 * <p>
 * It's a 2×2×3 laser drill. This block is one of its four bottom corners (CX/CZ say which: 0 = the west/north
 * side) and holds the block entity; {@link SeamExtractorPartBlock}s fill the rest. The frame is drawn by this
 * block's model and the roof by the part on top of the north-west corner; the spinning rings and the beam are
 * drawn by the block entity renderer. FACING is its front, with the screen; power only gets in through the energy ports on its sides.
 */
public class SeamExtractorBlock extends BaseEntityBlock implements com.admin82.factions.block.MultiblockPreview {

    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    public static final IntegerProperty CX = IntegerProperty.create("cx", 0, 1);
    public static final IntegerProperty CZ = IntegerProperty.create("cz", 0, 1);
    /** The side with the screen and the energy port (towards whoever placed it). */
    public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
            net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;

    private static final VoxelShape[] OUTLINES = new VoxelShape[12];

    public SeamExtractorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false).setValue(CX, 0).setValue(CZ, 0)
                .setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(SeamExtractorBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE, CX, CZ, FACING);
    }

    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    // The whole machine

    /** The bottom north-west cell of the machine a cell at {@code pos} belongs to. */
    public static BlockPos origin(BlockPos pos, int cx, int cy, int cz) {
        return pos.offset(-cx, -cy, -cz);
    }

    /**
     * Whether power may come in at the cell (cx, cy, cz) through {@code side}: only through the energy ports, on the
     * left and right sides of the bottom layer. A null side (no particular face) is allowed.
     */
    public static boolean energyPort(BlockState main, int cx, int cy, int cz, @Nullable net.minecraft.core.Direction side) {
        if (side == null) return true;
        net.minecraft.core.Direction front = main.getValue(FACING);
        if (cy != 0 || side.getAxis() == front.getAxis() || side.getAxis().isVertical()) return false;
        // the cell has to be on that side of the 2x2 footprint
        if (side.getStepX() != 0) return cx == (side.getStepX() > 0 ? 1 : 0);
        return cz == (side.getStepZ() > 0 ? 1 : 0);
    }

    /** The outline of the whole machine, seen from the cell at (cx, cy, cz): one box round all of it. */
    public static VoxelShape outline(int cx, int cy, int cz) {
        int i = (cx * 3 + cy) * 2 + cz;
        VoxelShape s = OUTLINES[i];
        if (s == null) {
            s = Block.box(-16 * cx, -16 * cy, -16 * cz, 32 - 16 * cx, 48 - 16 * cy, 32 - 16 * cz);
            OUTLINES[i] = s;
        }
        return s;
    }

    /** Fills the machine's other 11 cells with parts, wherever they're free (missing ones too, for old 1×1 extractors). */
    public static void placeParts(Level level, BlockPos mainPos, BlockState main) {
        BlockPos o = origin(mainPos, main.getValue(CX), 0, main.getValue(CZ));
        for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 3; dy++) for (int dz = 0; dz < 2; dz++) {
            BlockPos c = o.offset(dx, dy, dz);
            if (c.equals(mainPos) || level.isOutsideBuildHeight(c)) continue;
            BlockState part = ModBlocks.SEAM_EXTRACTOR_PART.get().defaultBlockState()
                    .setValue(SeamExtractorPartBlock.CX, dx).setValue(SeamExtractorPartBlock.CY, dy).setValue(SeamExtractorPartBlock.CZ, dz);
            BlockState here = level.getBlockState(c);
            if (here.equals(part) || !here.canBeReplaced()) continue;
            level.setBlock(c, part, Block.UPDATE_ALL);
        }
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return outline(state.getValue(CX), 0, state.getValue(CZ));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return Shapes.block();
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return false;
    }

    // Block entity

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SeamExtractorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.SEAM_EXTRACTOR.get(), SeamExtractorBlockEntity::serverTick);
    }

    // Placing

    /**
     * Refuses the placement when the 2×2×3 space isn't free, or (server side) when the player has no faction or the
     * seam already has enough of theirs. The machine reaches away from the player, from the clicked block.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        Level level = ctx.getLevel();
        Player player = ctx.getPlayer();
        int cx = player != null && player.getX() > pos.getX() + 0.5 ? 1 : 0;
        int cz = player != null && player.getZ() > pos.getZ() + 0.5 ? 1 : 0;
        BlockPos o = origin(pos, cx, 0, cz);
        String problem = null;
        for (int dx = 0; dx < 2 && problem == null; dx++) for (int dy = 0; dy < 3 && problem == null; dy++) for (int dz = 0; dz < 2; dz++) {
            BlockPos c = o.offset(dx, dy, dz);
            if (c.equals(pos)) continue;
            if (level.isOutsideBuildHeight(c) || !level.getBlockState(c).canBeReplaced()) {
                problem = "A Seam Extractor needs a clear space 2 wide, 2 deep and 3 tall.";
                break;
            }
        }
        if (problem == null && !level.getEntitiesOfClass(LivingEntity.class, new AABB(o).expandTowards(1, 2, 1)).isEmpty()) {
            problem = "Something is standing where the Seam Extractor would go.";
        }
        if (problem == null && level instanceof ServerLevel server && player instanceof ServerPlayer sp) {
            problem = placementProblem(server, pos, sp);
        }
        if (problem != null) {
            if (player instanceof ServerPlayer sp) sp.displayClientMessage(Component.literal("§c[Seams] " + problem), true);
            return null;
        }
        return defaultBlockState().setValue(CX, cx).setValue(CZ, cz).setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Nullable
    private static String placementProblem(ServerLevel level, BlockPos pos, ServerPlayer player) {
        Faction faction = FactionManager.get(level.getServer()).getFactionForPlayer(player.getUUID());
        if (faction == null) return "You need to be in a faction to place a Seam Extractor.";
        ChunkPos chunk = new ChunkPos(pos);
        if (!faction.hasClaim(chunk.x, chunk.z, level.dimension().location().toString())) {
            return "Seam Extractors go on your faction's land: a claimed chunk or an outpost's land.";
        }
        int limit = FactionUpgrades.get(level.getServer()).seamMiners(faction.getId());
        int here = SeamData.get(level.getServer()).extractorsOn(level, new ChunkPos(pos), faction.getId());
        if (here >= limit) {
            return "Your faction already has " + here + " Seam Extractor" + (here == 1 ? "" : "s")
                    + " on this seam (the limit is " + limit + "). Buy Extractor Quantity in Faction Upgrades for more.";
        }
        return null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel server)) return;
        placeParts(level, pos, state);
        if (!(placer instanceof ServerPlayer player)) return;
        Faction faction = FactionManager.get(server.getServer()).getFactionForPlayer(player.getUUID());
        if (faction == null || !(level.getBlockEntity(pos) instanceof SeamExtractorBlockEntity be)) return;
        be.faction = faction.getId();
        be.setChanged();
        SeamData.get(server.getServer()).addExtractor(server, pos, faction.getId());
    }

    // Using

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || !(level.getBlockEntity(pos) instanceof SeamExtractorBlockEntity be)) return InteractionResult.PASS;
        if (be.faction == null || FactionManager.get(sp.server).getFaction(be.faction) == null) {
            // Unowned (its faction was disbanded): the first faction member to use it takes it over
            Faction mine = FactionManager.get(sp.server).getFactionForPlayer(sp.getUUID());
            if (mine != null) {
                if (be.adopt((ServerLevel) level, mine.getId())) {
                    sp.displayClientMessage(Component.literal("§a[Seams] This Seam Extractor now belongs to " + mine.getName() + "."), false);
                } else {
                    sp.displayClientMessage(Component.literal("§c[Seams] Your faction already has as many Seam Extractors on this seam as it may."), true);
                    return InteractionResult.FAIL;
                }
            }
        }
        if (!SeamExtractorMenu.canUse(sp, be)) {
            sp.displayClientMessage(Component.literal("§cThis Seam Extractor belongs to another faction."), true);
            return InteractionResult.FAIL;
        }
        sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new SeamExtractorMenu(id, inv, be), Component.literal("Seam Extractor")),
                buf -> {
                    buf.writeBlockPos(pos);
                    buf.writeVarInt(0); // LDLib2 UISyncManager initial pack
                });
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, be.snapshot((ServerLevel) level));
        return InteractionResult.CONSUME;
    }

    /** While it mines: sparks and smoke where the beam hits the ground, exhaust from the stack, and the hum. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ACTIVE)) return;
        BlockPos o = origin(pos, state.getValue(CX), 0, state.getValue(CZ));
        double x = o.getX() + 1, y = o.getY() + 0.17, z = o.getZ() + 1;
        for (int i = 0; i < 3; i++) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, x + random.nextGaussian() * 0.15, y, z + random.nextGaussian() * 0.15,
                    random.nextGaussian() * 0.08, 0.05 + random.nextDouble() * 0.1, random.nextGaussian() * 0.08);
        }
        if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.SMOKE, x + random.nextGaussian() * 0.2, y, z + random.nextGaussian() * 0.2, 0, 0.04, 0);
        if (random.nextInt(4) == 0) level.addParticle(ParticleTypes.LAVA, x, y, z, 0, 0, 0);
        // exhaust from the stack on the roof
        double sx = o.getX() + 24.5 / 16.0, sy = o.getY() + 58.5 / 16.0, sz = o.getZ() + 7.5 / 16.0;
        if (random.nextInt(3) == 0) {
            level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, sx + random.nextGaussian() * 0.05, sy, sz + random.nextGaussian() * 0.05,
                    0, 0.04 + random.nextDouble() * 0.02, 0);
        }
        if (random.nextInt(2) == 0) level.addParticle(ParticleTypes.LARGE_SMOKE, sx, sy, sz, 0, 0.03, 0);
        if (level.getBlockEntity(pos) instanceof SeamExtractorBlockEntity be) {
            com.admin82.factions.client.SeamBeamSound.ensurePlaying(be);
        }
    }

    // Removing

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof SeamExtractorBlockEntity be) Containers.dropContents(level, pos, be.output);
            if (level instanceof ServerLevel server) SeamData.get(server.getServer()).removeExtractor(server, pos);
            BlockPos o = origin(pos, state.getValue(CX), 0, state.getValue(CZ));
            for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 3; dy++) for (int dz = 0; dz < 2; dz++) {
                BlockPos c = o.offset(dx, dy, dz);
                BlockState here = level.getBlockState(c);
                if (here.is(ModBlocks.SEAM_EXTRACTOR_PART.get()) && SeamExtractorPartBlock.origin(c, here).equals(o)) {
                    level.removeBlock(c, false);
                }
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    // Placement preview

    @Override
    public BlockState previewState(BlockPlaceContext ctx) {
        BlockPos pos = ctx.getClickedPos();
        Player player = ctx.getPlayer();
        int cx = player != null && player.getX() > pos.getX() + 0.5 ? 1 : 0;
        int cz = player != null && player.getZ() > pos.getZ() + 0.5 ? 1 : 0;
        return defaultBlockState().setValue(CX, cx).setValue(CZ, cz).setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public java.util.List<BlockPos> previewCells(BlockPos pos, BlockState state) {
        BlockPos o = origin(pos, state.getValue(CX), 0, state.getValue(CZ));
        var cells = new java.util.ArrayList<BlockPos>();
        for (int dx = 0; dx < 2; dx++) for (int dy = 0; dy < 3; dy++) for (int dz = 0; dz < 2; dz++) cells.add(o.offset(dx, dy, dz));
        return cells;
    }

    @Override
    public java.util.Map<BlockPos, BlockState> previewExtras(BlockPos pos, BlockState state) {
        BlockPos o = origin(pos, state.getValue(CX), 0, state.getValue(CZ));
        return java.util.Map.of(o.above(2), ModBlocks.SEAM_EXTRACTOR_PART.get().defaultBlockState().setValue(SeamExtractorPartBlock.CY, 2));
    }
}
