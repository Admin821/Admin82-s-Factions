package com.admin82.factions.map;

import com.admin82.factions.AdminsFactions;
import com.mojang.blaze3d.platform.InputConstants;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = AdminsFactions.MODID, value = Dist.CLIENT)
public class ClientHudEvents {
    public static final ResourceLocation TOP_RIGHT_HUD_ID =
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "top_right_hud");
    public static final KeyMapping OPEN_MAP_KEY = new KeyMapping(
            "key.adminsfactions.open_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M,
            "key.categories.adminsfactions");

    private static int lastWidth = -1;
    private static int lastHeight = -1;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(TOP_RIGHT_HUD_ID, (guiGraphics, deltaTracker) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.options.hideGui || mc.player == null) {
                return;
            }

            ModularUI ui = MapHud.getOrCreate();
            int width = guiGraphics.guiWidth();
            int height = guiGraphics.guiHeight();

            // Re-layout only when the window or GUI scale changes
            if (width != lastWidth || height != lastHeight) {
                lastWidth = width;
                lastHeight = height;
                ui.init(width, height);
            }

            float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);
            ui.getWidget().render(guiGraphics, 0, 0, partialTick);
        });
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!OPEN_MAP_KEY.consumeClick()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && mc.player != null) MapMenu.open();
    }
}

@EventBusSubscriber(modid = AdminsFactions.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
final class ClientMapKeyRegistration {
    private ClientMapKeyRegistration() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(ClientHudEvents.OPEN_MAP_KEY);
    }
}
