package com.admin82.factions.client;

import com.admin82.factions.block.MultiblockPreview;
import com.admin82.factions.item.FactionTableItem;
import com.admin82.factions.registry.ModBlocks;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * While you hold one of this mod's blocks that's bigger than one block space, shows where it would go: a see-through
 * copy of its model and an outline of every space it takes, green when it fits and red when something's in the way.
 */
@OnlyIn(Dist.CLIENT)
public final class PlacementPreview {

    private PlacementPreview() {}

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            Block block = blockOf(stack);
            if (!(block instanceof MultiblockPreview preview)) continue;
            BlockPlaceContext ctx = new BlockPlaceContext(mc.player, hand, stack, hit);
            // The Faction Table item always goes on the clicked face; block items replace grass and the like
            BlockPos pos = stack.getItem() instanceof FactionTableItem ? hit.getBlockPos().relative(hit.getDirection()) : ctx.getClickedPos();
            BlockState state = preview.previewState(ctx);
            List<BlockPos> cells = preview.previewCells(pos, state);
            boolean fits = (stack.getItem() instanceof FactionTableItem || ctx.canPlace()) && fits(mc.level, cells);
            Map<BlockPos, BlockState> models = new LinkedHashMap<>();
            models.put(pos, state);
            models.putAll(preview.previewExtras(pos, state));
            draw(event, mc, cells, models, fits);
            return; // one preview at a time (main hand first)
        }
    }

    private static Block blockOf(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem bi) return bi.getBlock();
        if (stack.getItem() instanceof FactionTableItem) return ModBlocks.FACTION_TABLE.get();
        return null;
    }

    private static boolean fits(Level level, List<BlockPos> cells) {
        for (BlockPos c : cells) {
            if (level.isOutsideBuildHeight(c) || !level.getBlockState(c).canBeReplaced()) return false;
        }
        return true;
    }

    private static void draw(RenderLevelStageEvent event, Minecraft mc, List<BlockPos> cells, Map<BlockPos, BlockState> models, boolean fits) {
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float pulse = 0.85f + 0.15f * (float) Math.sin((mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false)) * 0.15);
        int tint = fits ? 0x9CFFA8 : 0xFF8C8C;

        // See-through model
        var dispatcher = mc.getBlockRenderer();
        VertexConsumer ghost = new Tinted(buffers.getBuffer(RenderType.translucent()), tint, 0.45f * pulse);
        for (var e : models.entrySet()) {
            BlockState s = e.getValue();
            if (s.getRenderShape() != RenderShape.MODEL) continue;
            BlockPos p = e.getKey();
            pose.pushPose();
            pose.translate(p.getX() - cam.x, p.getY() - cam.y, p.getZ() - cam.z);
            dispatcher.getModelRenderer().renderModel(pose.last(), ghost, s, dispatcher.getBlockModel(s), 1, 1, 1,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        buffers.endBatch(RenderType.translucent());

        // Outline of every space, red where it's blocked
        RenderSystem.lineWidth(2f);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (BlockPos c : cells) {
            boolean free = !mc.level.isOutsideBuildHeight(c) && mc.level.getBlockState(c).canBeReplaced();
            float r = free && fits ? 0.35f : 1f, g = free && fits ? 1f : free ? 0.75f : 0.25f, b = free && fits ? 0.45f : 0.25f;
            AABB box = new AABB(c).move(-cam.x, -cam.y, -cam.z).deflate(0.003);
            LevelRenderer.renderLineBox(pose, lines, box, r, g, b, (free ? 0.55f : 0.95f) * pulse);
        }
        buffers.endBatch(RenderType.lines());
        RenderSystem.lineWidth(1f);
    }

    /** Passes vertices through, tinting them and making them see-through. */
    private record Tinted(VertexConsumer to, int rgb, float alpha) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float x, float y, float z) { to.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            to.setColor(r * ((rgb >> 16) & 0xFF) / 255, g * ((rgb >> 8) & 0xFF) / 255, b * (rgb & 0xFF) / 255, (int) (a * alpha));
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) { to.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { to.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { to.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { to.setNormal(x, y, z); return this; }
    }
}
