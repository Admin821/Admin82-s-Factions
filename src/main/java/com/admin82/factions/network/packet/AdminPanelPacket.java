package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.admin.AdminData;
import com.admin82.factions.admin.AdminSettings;
import com.admin82.factions.Config;
import com.admin82.factions.seam.Seam;
import com.admin82.factions.seam.SeamData;
import com.admin82.factions.seam.SeamTier;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.monument.MonumentEntry;
import com.admin82.factions.monument.MonumentMoves;
import com.admin82.factions.screen.AdminPanelScreen;
import com.admin82.factions.util.BypassManager;
import com.admin82.factions.war.VassalManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;

/** Client → server: everything the admin panel can do. Operators only. */
public record AdminPanelPacket(Action action, String a, String b, double value, int mask) implements CustomPacketPayload {

    public enum Action {
        REFRESH, SET_SETTING, RESET_SETTING, EXPORT, IMPORT, DELETE_EXPORT, OPEN_TOOL,
        DELETE_FACTION, SET_PLAYER_FACTION, FREE_VASSAL, TOGGLE_BYPASS,
        MOVE_MONUMENT, CANCEL_MONUMENT_MOVE, TELEPORT_TO_MONUMENT,
        SEAM_SET, SEAM_TIER_UP, SEAM_TIER_DOWN, SEAM_DELETE, SEAM_RESET, SEAM_BLACKLIST_ADD, SEAM_BLACKLIST_REMOVE, SEAM_MAP
    }

    /** Tools the panel can open: key → command run as the operator. */
    public static final Map<String, String> TOOLS = Map.of(
            "monuments", "faction monument edit",
            "ai", "faction ai edit",
            "supplyloot", "faction supplydrop loot",
            "supplyspawn", "faction supplydrop spawn",
            "exchange", "faction exchange set");

    public static AdminPanelPacket of(Action action) { return new AdminPanelPacket(action, "", "", 0, 0); }
    public static AdminPanelPacket of(Action action, String a) { return new AdminPanelPacket(action, a, "", 0, 0); }

    public static final Type<AdminPanelPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "admin_panel"));

    public static final StreamCodec<FriendlyByteBuf, AdminPanelPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.action.ordinal());
                buf.writeUtf(p.a, 256);
                buf.writeUtf(p.b, 256);
                buf.writeDouble(p.value);
                buf.writeVarInt(p.mask);
            },
            buf -> {
                int ordinal = buf.readVarInt();
                Action action = ordinal >= 0 && ordinal < Action.values().length ? Action.values()[ordinal] : Action.REFRESH;
                return new AdminPanelPacket(action, buf.readUtf(256), buf.readUtf(256), buf.readDouble(), buf.readVarInt());
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(AdminPanelPacket p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp) || !sp.hasPermissions(2)) return;
            MinecraftServer server = sp.server;
            try {
                switch (p.action) {
                    case REFRESH -> { }
                    case SET_SETTING -> {
                        AdminSettings.Setting s = AdminSettings.byKey(p.a);
                        if (s == null) { fail(sp, "Unknown setting."); return; }
                        double stored = s.set(server, p.value);
                        ok(sp, s.label() + " set to " + show(s, stored) + ".");
                    }
                    case RESET_SETTING -> {
                        AdminSettings.Setting s = AdminSettings.byKey(p.a);
                        if (s == null || s.defaultValue() == null) { fail(sp, "That setting has no default."); return; }
                        double stored = s.set(server, s.defaultValue().apply(server));
                        ok(sp, s.label() + " reset to " + show(s, stored) + ".");
                    }
                    case EXPORT -> {
                        if (p.mask == 0) { fail(sp, "Pick at least one thing to export."); return; }
                        String file = AdminData.export(server, p.mask, p.a, sp.getGameProfile().getName());
                        ok(sp, "Exported to config/adminsfactions/exports/" + file);
                    }
                    case IMPORT -> {
                        if (p.mask == 0) { fail(sp, "Pick at least one thing to import."); return; }
                        List<String> done = AdminData.importFile(server, p.a, p.mask);
                        if (done.isEmpty()) fail(sp, "Nothing to import: the file has none of the chosen sections.");
                        else ok(sp, "Imported from " + p.a + ": " + String.join(", ", done) + ".");
                    }
                    case DELETE_EXPORT -> {
                        AdminData.delete(p.a);
                        ok(sp, "Deleted " + p.a + ".");
                    }
                    case OPEN_TOOL -> {
                        String command = TOOLS.get(p.a);
                        if (command == null) return;
                        server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), command);
                        return; // another screen opens; no panel refresh
                    }
                    case DELETE_FACTION -> server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(), "faction delete " + p.a);
                    case SET_PLAYER_FACTION -> server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(),
                            "faction set " + p.a + " " + (p.b.isBlank() ? "none" : p.b));
                    case FREE_VASSAL -> server.getCommands().performPrefixedCommand(sp.createCommandSourceStack(),
                            "faction war vassals edit \"" + p.a.replace("\"", "") + "\" free");
                    case MOVE_MONUMENT -> {
                        MonumentEntry m = monument(server, p.a);
                        if (m == null) { fail(sp, "That monument no longer exists."); break; }
                        MonumentMoves.start(sp, m);
                        sp.closeContainer();
                        return;
                    }
                    case CANCEL_MONUMENT_MOVE -> MonumentMoves.cancel(sp);
                    case TELEPORT_TO_MONUMENT -> {
                        MonumentEntry m = monument(server, p.a);
                        var level = m == null ? null : MonumentMoves.level(server, m.dimension);
                        if (level == null) { fail(sp, "Can't find that monument's dimension."); break; }
                        var pos = m.controllerPos;
                        sp.closeContainer();
                        sp.teleportTo(level, pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5, sp.getYRot(), sp.getXRot());
                        return;
                    }
                    case SEAM_SET, SEAM_TIER_UP, SEAM_TIER_DOWN, SEAM_DELETE, SEAM_RESET -> seamAction(sp, p);
                    case SEAM_MAP -> {
                        // The map moved or zoomed: send every seam around its centre, and nothing else
                        var c = chunkOf(p.b);
                        if (c == null) c = sp.chunkPosition();
                        SEAM_MAP_CENTER.put(sp.getUUID(), c);
                        int radius = (int) Math.max(1, Math.min(AdminSeamAreaPacket.MAX_RADIUS, p.value));
                        var cells = SeamData.get(server).seamsIn(sp.serverLevel(), c.x - radius, c.z - radius,
                                c.x + radius, c.z + radius, AdminSeamAreaPacket.MAX_SEAMS);
                        PacketDistributor.sendToPlayer(sp, new AdminSeamAreaPacket(c.x, c.z, radius, cells));
                        return;
                    }
                    case SEAM_BLACKLIST_ADD -> {
                        String id = p.a.trim().toLowerCase(Locale.ROOT);
                        if (!id.matches("[a-z0-9_.-]+:([a-z0-9_./-]+|[*])")) { fail(sp, "Use an item id like minecraft:coal_ore, or modid:* for a whole mod."); break; }
                        List<String> list = new ArrayList<>(Config.SEAM_ORE_BLACKLIST.get());
                        if (!list.contains(id)) list.add(id);
                        Config.set(Config.SEAM_ORE_BLACKLIST, list);
                        ok(sp, id + " won't appear in new seams.");
                    }
                    case SEAM_BLACKLIST_REMOVE -> {
                        List<String> list = new ArrayList<>(Config.SEAM_ORE_BLACKLIST.get());
                        list.remove(p.a);
                        Config.set(Config.SEAM_ORE_BLACKLIST, list);
                        ok(sp, p.a + " can appear in seams again.");
                    }
                    case TOGGLE_BYPASS -> {
                        boolean on = BypassManager.toggleBypass(sp.getUUID()) == 1;
                        ok(sp, on ? "Protection bypass ON: you can build anywhere." : "Protection bypass OFF.");
                    }
                }
            } catch (Exception e) {
                fail(sp, e.getMessage() == null ? "Something went wrong." : e.getMessage());
            }
            sendSync(sp);
        });
    }

    /** Chunks across the square of live seams sent to the admin panel's map (it shows 9 × 9, the rest is for dragging). */
    public static final int SEAM_MAP_SIZE = 15;
    /** Where each admin's seam map is centred (missing = on them). */
    private static final Map<UUID, net.minecraft.world.level.ChunkPos> SEAM_MAP_CENTER = new java.util.concurrent.ConcurrentHashMap<>();

    /** "x,z" → chunk, or null. */
    @Nullable
    private static net.minecraft.world.level.ChunkPos chunkOf(String text) {
        String[] parts = text.split(",");
        if (parts.length != 2) return null;
        try {
            return new net.minecraft.world.level.ChunkPos(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Changes the seam of the chunk picked on the map ({@code b} = "x,z"; the admin's own chunk if missing). */
    private static void seamAction(ServerPlayer sp, AdminPanelPacket p) {
        var level = sp.serverLevel();
        var picked = chunkOf(p.b);
        var chunk = picked != null ? picked : sp.chunkPosition();
        String where = " at chunk " + chunk.x + ", " + chunk.z;
        SeamData data = SeamData.get(sp.server);
        Seam seam = data.get(level, chunk).copy();
        switch (p.action) {
            case SEAM_SET -> {
                String ore = p.a.isBlank() ? seam.ore : p.a.trim();
                var id = net.minecraft.resources.ResourceLocation.tryParse(ore);
                if (id == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(id)) {
                    fail(sp, "Unknown item: " + ore);
                    return;
                }
                SeamTier tier = SeamTier.values()[Math.max(0, Math.min(SeamTier.values().length - 1, p.mask))];
                if (tier == SeamTier.DEPLETED) { fail(sp, "Pick a richness."); return; }
                data.set(level, chunk, new Seam(id.toString(), tier, p.value >= 0.5));
                ok(sp, "Seam" + where + " is now " + (p.value >= 0.5 ? "endless (" + tier.speedLabel.toLowerCase(Locale.ROOT) + " speed) " : tier.label.toLowerCase(Locale.ROOT) + " ") + id + ".");
            }
            case SEAM_TIER_UP, SEAM_TIER_DOWN -> {
                if (seam.ore.isEmpty()) { fail(sp, "This seam has no ore. Spawn one instead."); return; }
                SeamTier to = p.action == Action.SEAM_TIER_UP ? seam.tier.previous() : seam.tier.next();
                if (to == seam.tier) { fail(sp, "It can't go any " + (p.action == Action.SEAM_TIER_UP ? "higher." : "lower.")); return; }
                Seam changed = new Seam(seam.ore, to, seam.endless && to != SeamTier.DEPLETED);
                data.set(level, chunk, changed);
                ok(sp, "Seam" + where + " is now " + changed.describe().replaceAll("§.", "") + ".");
            }
            case SEAM_DELETE -> {
                data.set(level, chunk, Seam.none());
                ok(sp, "Deleted the seam" + where + ".");
            }
            default -> {
                data.reset(level, chunk);
                ok(sp, "Seam" + where + " is back to what the world seed gives.");
            }
        }
    }

    @Nullable
    private static MonumentEntry monument(MinecraftServer server, String id) {
        try {
            return MonumentData.get(server).get(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String show(AdminSettings.Setting s, double v) {
        return switch (s.kind()) {
            case BOOL -> v >= 0.5 ? "on" : "off";
            case MONEY -> Currency.format((long) v).replaceAll("§.", "");
            case INT -> Long.toString((long) v);
            case DOUBLE -> String.format(Locale.ROOT, "%.2f", v);
        };
    }

    private static void ok(ServerPlayer sp, String message) {
        sp.displayClientMessage(Component.literal("§a[Admin] §7" + message), false);
    }

    private static void fail(ServerPlayer sp, String message) {
        sp.displayClientMessage(Component.literal("§c[Admin] " + message), false);
    }

    public static void sendSync(ServerPlayer sp) {
        MinecraftServer server = sp.server;
        List<Sync.SettingView> settings = new ArrayList<>();
        for (AdminSettings.Setting s : AdminSettings.all()) {
            settings.add(new Sync.SettingView(s.key(), s.group(), s.label(), s.help(), s.kind().ordinal(), s.min(), s.max(),
                    s.get(server), s.defaultValue() != null ? s.defaultValue().apply(server) : Double.NaN));
        }
        FactionManager fmgr = FactionManager.get(server);
        EconomyManager eco = EconomyManager.get(server);
        VassalManager vassals = VassalManager.get(server);
        List<Sync.FactionView> factions = new ArrayList<>();
        for (Faction f : fmgr.getAllFactions().values()) {
            var overlord = vassals.getSuzerain(f.getId());
            Faction o = overlord != null ? fmgr.getFaction(overlord) : null;
            factions.add(new Sync.FactionView(f.getName(), f.getMembers().size(), f.getLandClaims().size(),
                    eco.getVault(f.getId()), o != null ? o.getName() : ""));
        }
        factions.sort(Comparator.comparing(Sync.FactionView::name, String.CASE_INSENSITIVE_ORDER));
        List<String> players = server.getPlayerList().getPlayers().stream().map(x -> x.getGameProfile().getName())
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        List<Sync.MonumentView> monuments = new ArrayList<>();
        for (MonumentEntry m : MonumentData.get(server).getAll()) {
            monuments.add(new Sync.MonumentView(m.id.toString(), m.getName(), m.getTier(), m.controllerPos.asLong(),
                    m.dimension, MonumentMoves.status(server, m)));
        }
        monuments.sort(Comparator.comparing(Sync.MonumentView::name, String.CASE_INSENSITIVE_ORDER));
        var moving = MonumentMoves.pending(sp.getUUID());
        var level = sp.serverLevel();
        var here = sp.chunkPosition();
        var center = SEAM_MAP_CENTER.getOrDefault(sp.getUUID(), here);
        PacketDistributor.sendToPlayer(sp, new Sync(settings, AdminData.list(), factions, players,
                BypassManager.isBypassing(sp.getUUID()), monuments, moving == null ? "" : moving.toString(),
                center.x, center.z, here.x, here.z, com.admin82.factions.seam.SeamCell.area(level, center, SEAM_MAP_SIZE),
                SeamData.ores(level), new ArrayList<>(Config.SEAM_ORE_BLACKLIST.get())));
    }

    /** Server → client: everything the admin panel shows. */
    public record Sync(List<SettingView> settings, List<AdminData.ExportFile> exports, List<FactionView> factions,
                       List<String> players, boolean bypass, List<MonumentView> monuments,
                       String movingMonument, int seamCenterX, int seamCenterZ, int playerChunkX, int playerChunkZ,
                       List<com.admin82.factions.seam.SeamCell> seamCells, List<String> seamOres, List<String> seamBlacklist)
            implements CustomPacketPayload {

        public record SettingView(String key, String group, String label, String help, int kind, double min, double max,
                                  double value, double defaultValue) {}

        public record FactionView(String name, int members, int claims, long vault, String overlord) {}

        public record MonumentView(String id, String name, int tier, long pos, String dimension, String status) {}

        public static final Type<Sync> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "admin_panel_sync"));

        public static final StreamCodec<FriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.of(
                (buf, s) -> {
                    buf.writeVarInt(s.settings.size());
                    for (SettingView v : s.settings) {
                        buf.writeUtf(v.key, 64); buf.writeUtf(v.group, 64); buf.writeUtf(v.label, 128); buf.writeUtf(v.help, 512);
                        buf.writeVarInt(v.kind); buf.writeDouble(v.min); buf.writeDouble(v.max);
                        buf.writeDouble(v.value); buf.writeDouble(v.defaultValue);
                    }
                    buf.writeVarInt(s.exports.size());
                    for (AdminData.ExportFile f : s.exports) {
                        buf.writeUtf(f.name(), 256); buf.writeLong(f.size()); buf.writeLong(f.modified());
                        buf.writeVarInt(f.sections()); buf.writeUtf(f.exportedBy(), 64);
                    }
                    buf.writeVarInt(s.factions.size());
                    for (FactionView f : s.factions) {
                        buf.writeUtf(f.name, 64); buf.writeVarInt(f.members); buf.writeVarInt(f.claims);
                        buf.writeLong(f.vault); buf.writeUtf(f.overlord, 64);
                    }
                    buf.writeVarInt(s.players.size());
                    s.players.forEach(n -> buf.writeUtf(n, 64));
                    buf.writeBoolean(s.bypass);
                    buf.writeVarInt(s.monuments.size());
                    for (MonumentView m : s.monuments) {
                        buf.writeUtf(m.id, 64); buf.writeUtf(m.name, 128); buf.writeVarInt(m.tier); buf.writeLong(m.pos);
                        buf.writeUtf(m.dimension, 256); buf.writeUtf(m.status, 64);
                    }
                    buf.writeUtf(s.movingMonument, 64);
                    buf.writeVarInt(s.seamCenterX); buf.writeVarInt(s.seamCenterZ);
                    buf.writeVarInt(s.playerChunkX); buf.writeVarInt(s.playerChunkZ);
                    com.admin82.factions.seam.SeamCell.writeList(buf, s.seamCells);
                    buf.writeVarInt(s.seamOres.size());
                    s.seamOres.forEach(o -> buf.writeUtf(o, 256));
                    buf.writeVarInt(s.seamBlacklist.size());
                    s.seamBlacklist.forEach(o -> buf.writeUtf(o, 256));
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<SettingView> settings = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) settings.add(new SettingView(buf.readUtf(64), buf.readUtf(64), buf.readUtf(128),
                            buf.readUtf(512), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));
                    n = buf.readVarInt();
                    List<AdminData.ExportFile> exports = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) exports.add(new AdminData.ExportFile(buf.readUtf(256), buf.readLong(), buf.readLong(),
                            buf.readVarInt(), buf.readUtf(64)));
                    n = buf.readVarInt();
                    List<FactionView> factions = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) factions.add(new FactionView(buf.readUtf(64), buf.readVarInt(), buf.readVarInt(),
                            buf.readLong(), buf.readUtf(64)));
                    n = buf.readVarInt();
                    List<String> players = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) players.add(buf.readUtf(64));
                    boolean bypass = buf.readBoolean();
                    n = buf.readVarInt();
                    List<MonumentView> monuments = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) monuments.add(new MonumentView(buf.readUtf(64), buf.readUtf(128), buf.readVarInt(),
                            buf.readLong(), buf.readUtf(256), buf.readUtf(64)));
                    String moving = buf.readUtf(64);
                    int cx = buf.readVarInt(), cz = buf.readVarInt(), px = buf.readVarInt(), pz = buf.readVarInt();
                    var cells = com.admin82.factions.seam.SeamCell.readList(buf);
                    n = buf.readVarInt();
                    List<String> ores = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) ores.add(buf.readUtf(256));
                    n = buf.readVarInt();
                    List<String> blacklist = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) blacklist.add(buf.readUtf(256));
                    return new Sync(settings, exports, factions, players, bypass, monuments, moving, cx, cz, px, pz, cells, ores, blacklist);
                });

        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

        public static void handle(Sync s, IPayloadContext ctx) {
            ctx.enqueueWork(() -> {
                if (Minecraft.getInstance().screen instanceof AdminPanelScreen screen) {
                    screen.getMenu().updateData(s);
                }
            });
        }
    }
}
