package com.admin82.factions.meter;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.network.packet.SyncMeterPacket;
import com.admin82.factions.util.BypassManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/** Everything the meter GUI can do: list and sell, set a buy price, coin storage, sides and dumping. */
public final class MeterService {

    public enum Action { REFRESH, CREATE, SUPPLY, BUY, LEAVE, OPEN_COINS, OPEN_DIAL, EDIT, DELETE, DUMP, SET_SIDE }

    private MeterService() {}

    @Nullable
    private static MeterBlockEntity meter(ServerPlayer sp, BlockPos pos) {
        return sp.level().getBlockEntity(pos) instanceof MeterBlockEntity be ? be : null;
    }

    /** Opens a meter: {@code storage} is the battery/tank screen (bottom block), otherwise the network dial (top). */
    public static void open(ServerPlayer sp, BlockPos pos, boolean storage) {
        MeterBlockEntity be = meter(sp, pos);
        if (be == null) return;
        if (be.owner == null) { be.owner = sp.getUUID(); be.ownerName = sp.getGameProfile().getName(); be.setChanged(); }
        sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new com.admin82.factions.menu.MeterMenu(id, inv, pos, be.kind, storage),
                Component.literal(be.kind.label + " Meter")), buf -> {
            buf.writeBlockPos(pos);
            buf.writeVarInt(be.kind.ordinal());
            buf.writeBoolean(storage);
            buf.writeVarInt(0); // LDLib2 UISyncManager initial pack
        });
        sendSync(sp, pos);
    }

    /** The meter's coin slots, as a chest-style screen with the player's inventory. */
    private static void openCoins(ServerPlayer sp, BlockPos pos, MeterBlockEntity be) {
        com.admin82.factions.menu.CoinStorageMenu.open(sp, com.admin82.factions.menu.CoinStorageMenu.Source.METER, pos,
                new UUID(0, 0), be.kind.label + " Meter coins", be.coinSlots, () -> be.coinCap, () -> be.coins, true,
                p -> !be.isRemoved() && p.level().getBlockEntity(pos) == be && p.distanceToSqr(pos.getCenter()) <= 64
                        && (p.getUUID().equals(be.owner) || BypassManager.isBypassing(p.getUUID())));
    }

    public static void handle(ServerPlayer sp, BlockPos pos, Action action, @Nullable UUID network, String text, String password,
                              long x, long y, int access) {
        MeterBlockEntity be = meter(sp, pos);
        if (be == null || sp.distanceToSqr(pos.getCenter()) > 64) return;
        MinecraftServer server = sp.server;
        MeterNetworks all = MeterNetworks.get(server);
        boolean isOwner = sp.getUUID().equals(be.owner) || BypassManager.isBypassing(sp.getUUID());
        if (action != Action.REFRESH && !isOwner) { fail(sp, "Only " + be.ownerName + " can change this meter."); return; }

        switch (action) {
            case REFRESH -> { }
            case CREATE -> {
                String name = text.replace("§", "").trim();
                if (name.isEmpty() || name.length() > 32) { fail(sp, "Give the network a name (up to 32 letters)."); return; }
                MeterNetworks.Access acc = access == 0 ? MeterNetworks.Access.PUBLIC : MeterNetworks.Access.FACTION;
                Faction faction = FactionManager.get(server).getFactionForPlayer(sp.getUUID());
                if (acc == MeterNetworks.Access.FACTION && faction == null) { fail(sp, "Join a faction to sell to your faction only."); return; }
                detach(server, be, pos);
                MeterNetworks.Network n = new MeterNetworks.Network(UUID.randomUUID(), be.kind, name, sp.getUUID(), sp.getGameProfile().getName());
                n.faction = faction == null ? null : faction.getId();
                n.access = acc;
                n.priceCopper = Math.max(0, x);
                n.perUnits = Math.max(1, y);
                all.add(n);
                becomeSupplier(server, be, pos, n);
                sp.displayClientMessage(Component.literal("§a[Meter] Now selling as §f" + name + "§a. Buyers at or above your price connect by themselves."), false);
            }
            case SUPPLY -> {
                MeterNetworks.Network n = all.get(network);
                if (n == null || n.kind != be.kind || !n.owner.equals(sp.getUUID())) { fail(sp, "You can only supply your own networks."); return; }
                detach(server, be, pos);
                becomeSupplier(server, be, pos, n);
                sp.displayClientMessage(Component.literal("§a[Meter] This meter now supplies §f" + n.name + "§a."), false);
            }
            case BUY -> {
                if (x < 0 || y <= 0) { fail(sp, "Enter a price of 0 or more, per 1 or more " + be.kind.unit + "."); return; }
                String fluid = "";
                if (be.kind == MeterKind.FLUID) {
                    var id = net.minecraft.resources.ResourceLocation.tryParse(text);
                    var f = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.FLUID.getOptional(id).orElse(null);
                    if (f == null || f == net.minecraft.world.level.material.Fluids.EMPTY) { fail(sp, "Pick the fluid to buy."); return; }
                    fluid = id.toString();
                }
                if (be.role == MeterBlockEntity.Role.SUPPLIER) detach(server, be, pos);
                be.role = MeterBlockEntity.Role.CONSUMER;
                be.maxPriceCopper = x;
                be.maxPerUnits = y;
                if (!fluid.equals(be.wantFluid)) be.connect(null); // a different fluid needs a different seller
                be.wantFluid = fluid;
                be.setChanged();
                sp.displayClientMessage(Component.literal("§a[Meter] Buying at up to §e"
                        + MeterNetworks.per1000(MeterNetworks.micro(x, y)) + "§a per 1000 " + be.kind.unit
                        + ". It connects to a seller by itself."), false);
            }
            case LEAVE -> {
                detach(server, be, pos);
                sp.displayClientMessage(Component.literal("§7[Meter] This meter stopped buying and selling."), true);
            }
            case OPEN_COINS -> { openCoins(sp, pos, be); return; }
            case OPEN_DIAL -> { open(sp, pos, false); return; }
            case EDIT -> {
                MeterNetworks.Network n = all.get(network);
                if (n == null || !n.owner.equals(sp.getUUID())) { fail(sp, "That isn't your network."); return; }
                String name = text.replace("§", "").trim();
                if (!name.isEmpty() && name.length() <= 32) n.name = name;
                n.priceCopper = Math.max(0, x);
                n.perUnits = Math.max(1, y);
                MeterNetworks.Access acc = access == 0 ? MeterNetworks.Access.PUBLIC : MeterNetworks.Access.FACTION;
                if (acc == MeterNetworks.Access.FACTION && n.faction == null) {
                    Faction f = FactionManager.get(server).getFactionForPlayer(sp.getUUID());
                    if (f == null) { fail(sp, "Join a faction to sell to your faction only."); return; }
                    n.faction = f.getId();
                }
                n.access = acc;
                all.changed();
                sp.displayClientMessage(Component.literal("§a[Meter] Listing updated."), true);
            }
            case DUMP -> {
                if (be.stored() <= 0) { fail(sp, "It's already empty."); return; }
                long amount = be.stored();
                be.dump();
                sp.displayClientMessage(Component.literal("§7[Meter] Dumped " + amount + " " + be.kind.unit + "."), true);
            }
            case SET_SIDE -> {
                if (x < 0 || x > 3) return;
                be.setSide((int) x, SideMode.of((int) y));
            }
            case DELETE -> {
                MeterNetworks.Network n = all.get(network);
                if (n == null || !n.owner.equals(sp.getUUID())) { fail(sp, "That isn't your network."); return; }
                all.remove(n.id);
                detach(server, be, pos);
                sp.displayClientMessage(Component.literal("§7[Meter] Listing §f" + n.name + " §7closed. Its buyers look for another seller."), false);
            }
        }
        sendSync(sp, pos);
    }

    private static void becomeSupplier(MinecraftServer server, MeterBlockEntity be, BlockPos pos, MeterNetworks.Network n) {
        be.role = MeterBlockEntity.Role.SUPPLIER;
        be.networkId = n.id;
        GlobalPos here = GlobalPos.of(be.getLevel().dimension(), pos);
        if (!n.suppliers.contains(here)) n.suppliers.add(here);
        MeterNetworks.get(server).changed();
        be.setChanged();
    }

    /** Takes the meter off whatever network it's on (credit is lost with the network; coins stay). */
    private static void detach(MinecraftServer server, MeterBlockEntity be, BlockPos pos) {
        be.payOwner(server);
        MeterNetworks.Network n = MeterNetworks.get(server).get(be.networkId);
        if (n != null && n.suppliers.remove(GlobalPos.of(be.getLevel().dimension(), pos))) MeterNetworks.get(server).changed();
        be.role = MeterBlockEntity.Role.NONE;
        be.networkId = null;
        be.connectedSince = 0;
        be.status = "";
        be.setChanged();
    }

    /** Faction-only listings are only shown to their faction (and their owner). */
    private static boolean canSee(MinecraftServer server, ServerPlayer sp, MeterNetworks.Network n) {
        return MeterNetworks.allowed(server, n, sp.getUUID());
    }

    /** What the suppliers of a network hold right now (loaded meters only), and what it is for fluids. */
    private static long[] stock(MinecraftServer server, MeterNetworks.Network n, String[] contentOut) {
        long total = 0;
        int online = 0;
        for (GlobalPos g : n.suppliers) {
            ServerLevel lvl = server.getLevel(g.dimension());
            if (lvl == null || !lvl.isLoaded(g.pos())) continue;
            if (lvl.getBlockEntity(g.pos()) instanceof MeterBlockEntity s && s.role == MeterBlockEntity.Role.SUPPLIER && n.id.equals(s.networkId)) {
                total += s.stored();
                online++;
                if (contentOut[0].isEmpty()) contentOut[0] = s.contentName();
            }
        }
        return new long[]{total, online};
    }

    public static void sendSync(ServerPlayer sp, BlockPos pos) {
        MeterBlockEntity be = meter(sp, pos);
        if (be == null) return;
        MinecraftServer server = sp.server;
        MeterNetworks all = MeterNetworks.get(server);
        if (be.networkId != null && all.get(be.networkId) == null && be.role == MeterBlockEntity.Role.SUPPLIER) {
            be.role = MeterBlockEntity.Role.NONE; // its listing was closed
            be.networkId = null;
            be.setChanged();
        }
        List<SyncMeterPacket.NetworkView> views = new ArrayList<>();
        for (MeterNetworks.Network n : all.all()) {
            if (n.kind != be.kind || !canSee(server, sp, n)) continue;
            String[] content = {""};
            long[] st = stock(server, n, content);
            views.add(new SyncMeterPacket.NetworkView(n.id, n.name, n.ownerName, n.access == MeterNetworks.Access.PUBLIC ? 0 : 2, n.priceCopper, n.perUnits,
                    st[0], (int) st[1], n.suppliers.size(), content[0], n.unitsSold, n.owner.equals(sp.getUUID()),
                    n.owner.equals(sp.getUUID()) ? n.earned : -1));
        }
        views.sort(java.util.Comparator.comparingLong(v -> MeterNetworks.micro(v.priceCopper(), v.perUnits())));
        long perTick = be.ratePerTick(server);
        boolean isOwner = sp.getUUID().equals(be.owner) || BypassManager.isBypassing(sp.getUUID());
        List<SyncMeterPacket.PriceHistory> history = new ArrayList<>();
        all.histories(be.kind).forEach((fluid, h) -> history.add(new SyncMeterPacket.PriceHistory(fluid, h[0], h[1])));
        PacketDistributor.sendToPlayer(sp, new SyncMeterPacket(pos, be.kind.ordinal(),
                isOwner, be.ownerName, be.role.ordinal(),
                be.networkId, be.coinBalance(), be.coinCap, be.coinEta(),
                isOwner ? be.maxPriceCopper : -1, isOwner ? be.maxPerUnits : 0, // a buyer's limit is secret
                be.stored(), be.capacity(), be.contentName(),
                be.kind == MeterKind.POWER ? perTick : perTick * 20, be.efficiency(server), be.status,
                new int[]{be.side(0).ordinal(), be.side(1).ordinal(), be.side(2).ordinal(), be.side(3).ordinal()}, views,
                isOwner ? be.wantFluid : "", MeterBlockEntity.fluidId(be.tank.getFluid()), history));
    }

    private static void fail(ServerPlayer sp, String message) {
        sp.displayClientMessage(Component.literal("§c" + message), true);
    }

    private static String plain(long copper) {
        return Currency.format(copper).replaceAll("§.", "");
    }
}
