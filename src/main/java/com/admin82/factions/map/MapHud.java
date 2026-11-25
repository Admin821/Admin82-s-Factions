package com.admin82.factions.map;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.appliedenergistics.yoga.YogaPositionType; // or Taffy PositionType depending on your 2.2.x build

public class MapHud {
    // Semi-transparent red square (ARGB)
    public static TerrainMapTexture SQUARE_TEXTURE = new TerrainMapTexture(512);
    private static ModularUI hudUi;

    public static ModularUI getOrCreate() {
        if (hudUi == null) {
            var root = new UIElement()
                    .layout(layout -> {
                        layout.widthPercent(100);
                        layout.heightPercent(100);
                    });

            var square = new UIElement()
                    .layout(layout -> {
                        layout.positionType(YogaPositionType.ABSOLUTE); // Use TaffyPositionType / PositionType in newer 2.2+
                        layout.top(0);
                        layout.right(0);
                        layout.width(128);
                        layout.height(128);
                    })
                    .style(style -> style.backgroundTexture(SQUARE_TEXTURE));

            var playerMarkerOverlay = new UIElement()
                    .layout(layout -> {
                                layout.widthPercent(100);
                                layout.heightPercent(100);
                            }).style(style -> style.backgroundTexture(new IGuiTexture() {
                        @Override public void draw(GuiGraphics gfx, float mouseX, float mouseY, float x, float y, float width, float height, float partialTicks) {
                            Player player = Minecraft.getInstance().player;
                            if(player == null) return;

                            // 2. Draw rotated player arrow in the exact center of the minimap
                            float centerX = x + width / 2f;
                            float centerY = y + height / 2f;
                            // Minecraft yaw: 0 = South (+Z / down on map), 90 = West (-X / left), 180 = North (-Z / up)
                            float yaw = player.getViewYRot(partialTicks);

                            var pose = gfx.pose();
                            pose.pushPose();
                            pose.translate(centerX, centerY, 10f);
                            pose.mulPose(Axis.ZP.rotationDegrees(yaw));

                            // Draw a crisp white arrow with dark outline pointing South (+Y) at yaw=0
                            gfx.fill(-2, -3, 2, 2, 0xFF000000);   // dark border
                            gfx.fill(-1, -4, 1, 3, 0xFF000000);
                            gfx.fill(-1, -2, 1, 2, 0xFFFFFFFF);   // white body
                            gfx.fill(0, 2, 1, 3, 0xFFFF3333);     // red tip pointing forward (+Y at yaw=0)

                            pose.popPose();

                        }
                    }));
            square.addChild(playerMarkerOverlay);
            root.addChild(square);
            hudUi = ModularUI.of(UI.of(root));
            hudUi.init(1, 1); // Initialized before first resize
        }
        return hudUi;
    }
}
