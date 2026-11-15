package com.admin82.factions.menu;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Small shared helpers for the LDLib admin screens (sizing and real backgrounds). */
@OnlyIn(Dist.CLIENT)
public final class UiKit {
    /** Translucent dark card background (ARGB). */
    /**
     * Container id for screens that only exist on the client (outpost manager, placement, seam viewer). The server
     * numbers its containers 1 to 100, so slot updates meant for one of those never land on these.
     */
    public static final int CLIENT_MENU_ID = 127;

    public static final int CARD = 0x50000000;
    /** Slightly lighter row background for list rows. */
    public static final int ROW = 0x38000000;
    public static final int BAR_TRACK = 0xFF16161E;

    /** Space kept free around the panel, in GUI-scaled pixels. */
    private static final int SCREEN_MARGIN = 12;

    private UiKit() {}

    /**
     * Panel size that fits the current window at the current GUI scale: as large as the design size,
     * shrinking toward the minimum when the screen is smaller.
     */
    public static int[] panelSize(int designWidth, int designHeight, int minWidth, int minHeight) {
        var window = Minecraft.getInstance().getWindow();
        int width = Math.max(minWidth, Math.min(designWidth, window.getGuiScaledWidth() - SCREEN_MARGIN));
        int height = Math.max(minHeight, Math.min(designHeight, window.getGuiScaledHeight() - SCREEN_MARGIN));
        return new int[]{width, height};
    }

    /** Gives a plain element a solid/translucent background (lss "base-background" only works on buttons). */
    public static <T extends UIElement> T background(T element, int argb) {
        element.style(style -> style.background(new ColorRectTexture(argb)));
        return element;
    }

    /** A label with a fixed width and right-aligned text, so it never spills outside its row. */
    public static UIElement rightLabel(String text) {
        int width = Minecraft.getInstance().font.width(Component.literal(text)) + 4;
        return new Label().setText(text).lss("horizontal-align", "right").layout(l -> l.width(width));
    }

    /**
     * A text field with a real hint instead of LDLib's default "Empty" placeholder. Pass "" for no hint.
     */
    public static com.lowdragmc.lowdraglib2.gui.ui.elements.TextField textField(String placeholder) {
        var field = new com.lowdragmc.lowdraglib2.gui.ui.elements.TextField();
        field.getTextFieldStyle().placeholder(Component.literal(placeholder.isEmpty() ? "" : "§8" + placeholder));
        return field;
    }

    /** A checkbox without LDLib's built-in "Toggle" label (callers put their own label next to it). */
    public static com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle toggle() {
        return new com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle().noText();
    }

    /** The element the mouse was last pressed on (see {@link #onClick}). */
    private static UIElement pressed;

    /**
     * A click that only counts when the mouse went down and up on the same element. A plain mouse-up
     * listener also fires when a button press swaps the page and the release lands on whatever is now
     * under the cursor (clicking Back would pick an item in the newly shown inventory).
     */
    public static <T extends UIElement> T onClick(T element, com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener action) {
        element.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.MOUSE_DOWN, e -> pressed = element);
        element.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.MOUSE_UP, e -> {
            if (pressed != element) return;
            pressed = null;
            action.handleEvent(e);
        });
        return element;
    }
}
