package com.admin82.factions.meter;

import com.admin82.factions.AdminsFactions;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs all buying once a tick, after the meters have ticked. Each buyer first says how much it wants from the
 * seller it's connected to; then every seller's stock is split equally between its buyers (a buyer that wants
 * less than its share leaves the rest for the others).
 */
@EventBusSubscriber(modid = AdminsFactions.MODID)
public final class MeterMarket {

    private static final List<MeterBlockEntity> BUYERS = new ArrayList<>();

    private record Request(MeterBlockEntity buyer, long want) {}

    private MeterMarket() {}

    /** Called by a buyer meter's tick. */
    static void queue(MeterBlockEntity be) {
        BUYERS.add(be);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (BUYERS.isEmpty()) return;
        var server = event.getServer();
        List<MeterBlockEntity> buyers = new ArrayList<>(BUYERS);
        BUYERS.clear();

        // Who wants what, grouped by seller (and fluid, since one seller's meters can hold different fluids).
        Map<String, List<Request>> bySeller = new LinkedHashMap<>();
        for (MeterBlockEntity be : buyers) {
            if (be.isRemoved() || be.role != MeterBlockEntity.Role.CONSUMER) continue;
            long want = be.requestAmount(server);
            if (want > 0 && be.networkId != null) {
                bySeller.computeIfAbsent(be.networkId + "|" + be.wantFluid, k -> new ArrayList<>()).add(new Request(be, want));
            }
        }

        // Split each seller's stock equally: smallest requests are filled first, the rest share what's left.
        for (List<Request> requests : bySeller.values()) {
            long stock = requests.get(0).buyer().sellerStock(server);
            if (stock <= 0) continue;
            requests.sort(Comparator.comparingLong(Request::want));
            for (int i = 0; i < requests.size() && stock > 0; i++) {
                int left = requests.size() - i;
                long share = Math.max(1, stock / left);
                long give = Math.min(requests.get(i).want(), share);
                requests.get(i).buyer().receive(server, give);
                stock -= give;
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BUYERS.clear();
    }
}
