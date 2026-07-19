package com.admin82.factions.item;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class TooltipHelper {

    private TooltipHelper() {}

    public static void addOptional(List<Component> tooltip, String key) {
        if (Language.getInstance().has(key)) {
            tooltip.add(Component.translatable(key));
        }
    }
}