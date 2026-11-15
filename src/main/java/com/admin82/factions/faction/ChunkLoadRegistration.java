package com.admin82.factions.faction;

import com.admin82.factions.AdminsFactions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;

/** Mod-bus registration of the faction chunkloading ticket controller. */
@EventBusSubscriber(modid = AdminsFactions.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ChunkLoadRegistration {
    private ChunkLoadRegistration() {}

    @SubscribeEvent
    public static void onRegisterTicketControllers(RegisterTicketControllersEvent event) {
        event.register(ChunkLoadManager.TICKETS);
    }
}
