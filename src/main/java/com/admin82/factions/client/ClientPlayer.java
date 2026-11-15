package com.admin82.factions.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/**
 * The local player, typed as a plain {@link Player}. Menu classes are also loaded on dedicated servers;
 * if one passes {@code Minecraft.getInstance().player} (a client-only LocalPlayer) to a method that takes a
 * Player, Java checks that type while loading the menu and the server crashes. Going through here avoids that.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientPlayer {

    private ClientPlayer() {}

    @Nullable
    public static Player get() {
        return Minecraft.getInstance().player;
    }
}
