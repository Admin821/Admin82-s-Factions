package com.admin82.factions.map;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
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

            root.addChild(square);
            hudUi = ModularUI.of(UI.of(root));
            hudUi.init(1, 1); // Initialized before first resize
        }
        return hudUi;
    }
}
