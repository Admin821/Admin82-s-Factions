package com.admin82.factions.client.renderer;

import com.admin82.factions.block.OutpostStorageBlock;
import com.admin82.factions.blockentity.OutpostStorageBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static com.admin82.factions.client.renderer.PcScreen.W;
import static com.admin82.factions.client.renderer.PcScreen.H;

/**
 * Draws the storage box's computer screen: kinds of item stored out of the max, a fill bar, and the biggest stacks
 * with their counts. See {@link PcScreen} for the canvas.
 */
public class OutpostStorageRenderer implements BlockEntityRenderer<OutpostStorageBlockEntity> {

    /** Top edge of the screen in block pixels (the PC sits on the table, 1px higher than on a meter). */
    private static final float SCREEN_TOP = 29;

    private final Font font;
    private final ItemRenderer items;

    public OutpostStorageRenderer(BlockEntityRendererProvider.Context context) {
        this.font = context.getFont();
        this.items = context.getItemRenderer();
    }

    @Override
    public void render(OutpostStorageBlockEntity box, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        Direction facing = box.getBlockState().hasProperty(OutpostStorageBlock.FACING)
                ? box.getBlockState().getValue(OutpostStorageBlock.FACING) : Direction.NORTH;
        PcScreen.begin(pose, buffers, facing, SCREEN_TOP);
        if (!box.linked()) {
            PcScreen.centered(font, pose, buffers, "§cOFFLINE", W / 2f, H / 2f - 4);
        } else {
            drawContents(box, pose, buffers);
        }
        pose.popPose();
    }

    private void drawContents(OutpostStorageBlockEntity box, PoseStack pose, MultiBufferSource buffers) {
        int kinds = box.kinds(), max = Math.max(1, box.maxKinds());

        // Header: title and kinds used
        PcScreen.text(font, pose, buffers, "§aSTORAGE", 5, 5);
        PcScreen.right(font, pose, buffers, (kinds >= max ? "§c" : "§f") + kinds + "§7/" + max, W - 5, 5);

        // Fill bar
        float fraction = kinds / (float) max;
        PcScreen.bar(buffers, pose, 5, 15, W - 5, 18, fraction,
                fraction >= 1f ? 0xFFE04848 : fraction >= 0.7f ? 0xFFE0B840 : 0xFF48D068);

        // The biggest stacks
        List<ItemStack> top = box.top();
        if (top.isEmpty()) {
            PcScreen.centered(font, pose, buffers, "§8EMPTY", W / 2f, 27);
            return;
        }
        float slot = (W - 10) / (float) OutpostStorageBlockEntity.SHOWN;
        for (int i = 0; i < top.size(); i++) {
            float cx = 5 + slot * i + slot / 2;
            item(pose, buffers, box, top.get(i), cx, 29);
            pose.pushPose();
            pose.translate(cx, 38.5f, 0.5f);
            pose.scale(0.5f, 0.5f, 1f);
            PcScreen.centered(font, pose, buffers, "§f" + PcScreen.shortCount(box.counts().get(i)), 0, 0);
            pose.popPose();
        }
    }

    /** An item icon centred on (cx, cy), flattened onto the screen. */
    private void item(PoseStack pose, MultiBufferSource buffers, OutpostStorageBlockEntity box, ItemStack stack,
                      float cx, float cy) {
        pose.pushPose();
        pose.translate(cx, cy, 2);
        pose.scale(15, -15, 1.5f);
        items.renderStatic(stack, ItemDisplayContext.GUI, PcScreen.FULL, OverlayTexture.NO_OVERLAY, pose, buffers, box.getLevel(), 0);
        pose.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(OutpostStorageBlockEntity box) {
        return new AABB(box.getBlockPos()).expandTowards(0, 1, 0); // the computer pokes up into the block above
    }

    @Override
    public int getViewDistance() {
        return 32;
    }

    @Override
    public boolean shouldRender(OutpostStorageBlockEntity box, Vec3 camera) {
        return Vec3.atCenterOf(box.getBlockPos()).closerThan(camera, getViewDistance());
    }
}
