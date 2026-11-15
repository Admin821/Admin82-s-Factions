package com.admin82.factions.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * A block bigger than one block space. While a player holds its item, the client shows a see-through preview of where
 * it would go (green when it fits, red when it doesn't); see {@code PlacementPreview}.
 */
public interface MultiblockPreview {

    /** The state it would be placed as, worked out without any of the placement checks. */
    BlockState previewState(BlockPlaceContext ctx);

    /** Every block space it would take, {@code pos} included. */
    List<BlockPos> previewCells(BlockPos pos, BlockState state);

    /** Other parts with a model of their own to show in the preview (besides {@code state} at {@code pos}). */
    default Map<BlockPos, BlockState> previewExtras(BlockPos pos, BlockState state) {
        return Map.of();
    }
}
