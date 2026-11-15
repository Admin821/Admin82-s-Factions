package com.admin82.factions.seam;

import com.admin82.factions.AdminsFactions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** Keeps the cached ore lists in step with tag reloads (/reload, datapacks) and server restarts. */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public final class SeamEvents {

    private SeamEvents() {}

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        SeamData.clearOreCache();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SeamData.clearOreCache();
    }
}
