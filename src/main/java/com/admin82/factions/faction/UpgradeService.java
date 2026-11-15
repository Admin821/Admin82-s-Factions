package com.admin82.factions.faction;

import com.admin82.factions.Config;
import com.admin82.factions.meter.MeterBlockEntity;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.network.packet.SyncFactionUpgradesPacket;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.outpost.OutpostService;
import com.admin82.factions.registry.ModItems;
import com.admin82.factions.seam.SeamPath;
import com.admin82.factions.storage.FactionItemVaults;
import com.admin82.factions.storage.FactionVaultService;
import com.admin82.factions.storage.ItemVaultService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Faction upgrades, all paid in warbonds: the item vault, the land limit, outpost slots and each outpost's level.
 * Warbonds are taken from the faction item vault first, then from the player buying the upgrade.
 */
public final class UpgradeService {

    public enum Track { ITEM_VAULT, LAND, OUTPOST_SLOTS, OUTPOST, METER_EFFICIENCY, SEAM_MINERS, SEAM_SPEED, SEAM_QUANTITY, SEAM_EFFICIENCY }

    private UpgradeService() {}

    // Warbonds

    private static boolean isWarbond(ItemStack stack) {
        return stack.is(ModItems.WARBOND.get());
    }

    public static int warbondsInVault(MinecraftServer server, UUID faction) {
        return FactionItemVaults.get(server).count(faction, UpgradeService::isWarbond);
    }

    public static int warbondsCarried(ServerPlayer player) {
        int n = 0;
        for (ItemStack s : player.getInventory().items) if (isWarbond(s)) n += s.getCount();
        return n;
    }

    /** Takes {@code amount} warbonds (item vault first, then the player). Returns false and takes nothing if short. */
    public static boolean spend(ServerPlayer player, UUID faction, int amount) {
        if (amount <= 0) return true;
        MinecraftServer server = player.server;
        if (warbondsInVault(server, faction) + warbondsCarried(player) < amount) return false;
        int left = amount - FactionItemVaults.get(server).remove(faction, UpgradeService::isWarbond, amount);
        for (ItemStack s : player.getInventory().items) {
            if (left <= 0) break;
            if (!isWarbond(s)) continue;
            int take = Math.min(left, s.getCount());
            s.shrink(take);
            left -= take;
        }
        player.getInventory().setChanged();
        ItemVaultService.pushFaction(server, faction);
        return true;
    }

    /** Gives a faction warbonds: into its item vault, or delivered to the owner if there's no room. */
    public static void award(MinecraftServer server, Faction faction, int amount) {
        if (amount <= 0) return;
        ItemStack bonds = new ItemStack(ModItems.WARBOND.get(), amount);
        while (!bonds.isEmpty()) {
            ItemStack rest = FactionItemVaults.get(server).insert(faction.getId(), bonds.split(64));
            if (!rest.isEmpty()) MarketManager.get(server).queueDelivery(faction.getOwnerId(), rest, "Warbonds (vault full)");
        }
    }

    // Upgrades

    /** Buys one level of a track. {@code outpost} is only used for {@link Track#OUTPOST}. */
    public static void upgrade(ServerPlayer player, Track track, @Nullable UUID outpost) {
        MinecraftServer server = player.server;
        FactionManager fmgr = FactionManager.get(server);
        Faction faction = fmgr.getFactionForPlayer(player.getUUID());
        FactionMember member = faction == null ? null : faction.getMember(player.getUUID());
        if (member == null) return;
        if (member.getRole().getLevel() < FactionRole.OFFICER.getLevel()) { fail(player, "Only officers can buy upgrades."); return; }
        FactionUpgrades upgrades = FactionUpgrades.get(server);
        UUID id = faction.getId();

        int cost;
        Runnable apply;
        String done;
        switch (track) {
            case ITEM_VAULT -> {
                int level = FactionItemVaults.get(server).vault(id).level;
                if (level >= FactionItemVaults.maxLevel()) { fail(player, "The item vault is fully upgraded."); return; }
                cost = FactionItemVaults.upgradeCost(level);
                apply = () -> {
                    FactionItemVaults.get(server).upgrade(id);
                    FactionVaultService.reopenAll(server, id);
                };
                done = "Item vault upgraded to level " + (level + 1) + ": " + FactionItemVaults.slots(level + 1) + " slots, "
                        + FactionItemVaults.secureSlots(level + 1) + " secure.";
            }
            case LAND -> {
                int level = upgrades.landLevel(id);
                if (level >= FactionUpgrades.MAX_LAND_LEVEL) { fail(player, "Land is fully upgraded."); return; }
                cost = FactionUpgrades.landUpgradeCost(level);
                apply = () -> upgrades.setLandLevel(id, level + 1);
                done = "Land upgraded: you can now claim up to " + (upgrades.landRadius(id) + 1) + " chunks from your faction table.";
            }
            case OUTPOST_SLOTS -> {
                int slots = upgrades.outpostSlots(id);
                if (slots >= FactionUpgrades.MAX_OUTPOST_SLOTS) { fail(player, "Outpost slots are fully upgraded."); return; }
                cost = Config.OUTPOST_SLOT_WARBONDS.get() * slots;
                apply = () -> upgrades.setOutpostSlots(id, slots + 1);
                done = "Your faction can now have " + (slots + 1) + " outposts.";
            }
            case METER_EFFICIENCY -> {
                int level = upgrades.meterLevel(id);
                if (level >= Config.METER_EFFICIENCY_MAX.get()) { fail(player, "Meter efficiency is fully upgraded."); return; }
                cost = Config.METER_EFFICIENCY_WARBONDS.get() * level;
                apply = () -> upgrades.setMeterLevel(id, level + 1);
                done = "Meter efficiency raised: your meters now buy faster and hold more (+"
                        + MeterBlockEntity.bonusPercent(level + 1) + "%).";
            }
            case SEAM_MINERS -> {
                int miners = upgrades.seamMiners(id);
                if (miners >= FactionUpgrades.MAX_SEAM_MINERS) { fail(player, "Extractor Quantity is fully upgraded."); return; }
                cost = Config.SEAM_MINERS_WARBONDS.get() * miners;
                apply = () -> upgrades.setSeamMiners(id, miners + 1);
                done = "You can now run " + (miners + 1) + " Seam Extractors on each seam.";
            }
            case SEAM_SPEED, SEAM_QUANTITY, SEAM_EFFICIENCY -> {
                SeamPath path = pathOf(track);
                SeamPath chosen = upgrades.seamPath(id);
                int level = chosen == path ? upgrades.seamPathLevel(id) : 0;
                if (chosen != SeamPath.NONE && chosen != path) {
                    fail(player, "Your faction is locked to the " + chosen.label + " seam upgrade.");
                    return;
                }
                if (level >= SeamPath.MAX_LEVEL) { fail(player, "Seam " + path.label + " is fully upgraded."); return; }
                cost = Config.SEAM_PATH_WARBONDS.get() * (level + 1);
                apply = () -> upgrades.setSeamPath(id, path, level + 1);
                done = "Seam " + path.label + " raised to level " + (level + 1) + " (" + path.stats(level + 1) + ").";
            }
            default -> {
                OutpostEntry entry = outpost == null ? null : OutpostData.get(server).getOutpost(outpost);
                if (entry == null || !entry.ownerFactionId.equals(id)) { fail(player, "That outpost isn't yours."); return; }
                if (entry.disintegrating) { fail(player, "That outpost is falling apart."); return; }
                if (entry.level >= OutpostEntry.MAX_LEVEL) { fail(player, entry.name + " is fully upgraded."); return; }
                cost = entry.upgradeCost();
                apply = () -> OutpostService.levelUp(server, entry);
                done = entry.name + " upgraded to level " + (entry.level + 1) + ".";
            }
        }
        if (!spend(player, id, cost)) {
            fail(player, "This needs " + cost + " warbonds (in the item vault or your inventory). You have "
                    + (warbondsInVault(server, id) + warbondsCarried(player)) + ".");
            return;
        }
        apply.run();
        com.admin82.factions.network.InventorySync.push(player);
        player.displayClientMessage(Component.literal("§a[Upgrades] " + done + " §7(" + cost + " warbonds)"), false);
        sendSync(player);
    }

    /** Sends the Faction Upgrades tab its data. */
    public static void sendSync(ServerPlayer player) {
        MinecraftServer server = player.server;
        Faction faction = FactionManager.get(server).getFactionForPlayer(player.getUUID());
        if (faction == null) return;
        UUID id = faction.getId();
        FactionMember member = faction.getMember(player.getUUID());
        boolean officer = member != null && member.getRole().getLevel() >= FactionRole.OFFICER.getLevel();
        FactionUpgrades upgrades = FactionUpgrades.get(server);
        List<SyncFactionUpgradesPacket.Row> rows = new ArrayList<>();

        int vl = FactionItemVaults.get(server).vault(id).level;
        int vMax = FactionItemVaults.maxLevel();
        rows.add(new SyncFactionUpgradesPacket.Row(Track.ITEM_VAULT.ordinal(), "", "Item vault", vl, vMax,
                vl >= vMax ? 0 : FactionItemVaults.upgradeCost(vl),
                vaultText(vl), vl >= vMax ? "" : vaultText(vl + 1)));

        // Land has no top level (maxLevel 0 tells the tab it's unlimited).
        int ll = upgrades.landLevel(id);
        int radius = upgrades.landRadius(id);
        rows.add(new SyncFactionUpgradesPacket.Row(Track.LAND.ordinal(), "", "Land", ll, 0, FactionUpgrades.landUpgradeCost(ll),
                "Claim " + radius + " chunks out", "Claim " + (radius + 1) + " chunks out"));

        int slots = upgrades.outpostSlots(id);
        rows.add(new SyncFactionUpgradesPacket.Row(Track.OUTPOST_SLOTS.ordinal(), "", "Outpost slots", slots, FactionUpgrades.MAX_OUTPOST_SLOTS,
                slots >= FactionUpgrades.MAX_OUTPOST_SLOTS ? 0 : Config.OUTPOST_SLOT_WARBONDS.get() * slots,
                slots + " outpost" + (slots == 1 ? "" : "s"), slots >= FactionUpgrades.MAX_OUTPOST_SLOTS ? "" : (slots + 1) + " outposts"));

        int ml = upgrades.meterLevel(id);
        int mMax = Config.METER_EFFICIENCY_MAX.get();
        rows.add(new SyncFactionUpgradesPacket.Row(Track.METER_EFFICIENCY.ordinal(), "", "Meter efficiency", ml, mMax,
                ml >= mMax ? 0 : Config.METER_EFFICIENCY_WARBONDS.get() * ml,
                rateText(ml), ml >= mMax ? "" : rateText(ml + 1)));

        int miners = upgrades.seamMiners(id);
        rows.add(new SyncFactionUpgradesPacket.Row(Track.SEAM_MINERS.ordinal(), "", "Extractor quantity", miners, FactionUpgrades.MAX_SEAM_MINERS,
                miners >= FactionUpgrades.MAX_SEAM_MINERS ? 0 : Config.SEAM_MINERS_WARBONDS.get() * miners,
                miners + " extractor" + (miners == 1 ? "" : "s") + " per seam",
                miners >= FactionUpgrades.MAX_SEAM_MINERS ? "" : (miners + 1) + " extractors per seam"));
        SeamPath chosen = upgrades.seamPath(id);
        for (Track t : new Track[]{Track.SEAM_SPEED, Track.SEAM_QUANTITY, Track.SEAM_EFFICIENCY}) {
            SeamPath path = pathOf(t);
            boolean locked = chosen != SeamPath.NONE && chosen != path;
            int level = chosen == path ? upgrades.seamPathLevel(id) : 0;
            boolean max = level >= SeamPath.MAX_LEVEL;
            // A path that's locked out shows as maxed at level 0 with an explanation instead of stats.
            rows.add(new SyncFactionUpgradesPacket.Row(t.ordinal(), "", "Seam " + path.label, level, locked ? -1 : SeamPath.MAX_LEVEL,
                    locked || max ? 0 : Config.SEAM_PATH_WARBONDS.get() * (level + 1),
                    locked ? "Locked: your faction chose " + chosen.label : level == 0 ? "Not chosen · Power ×1" : path.stats(level),
                    locked || max ? "" : path.stats(level + 1)));
        }

        List<OutpostEntry> outposts = new ArrayList<>(OutpostData.get(server).getOutpostsForFaction(id));
        outposts.sort(Comparator.comparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
        for (OutpostEntry e : outposts) {
            if (e.disintegrating) continue;
            int lv = e.level;
            String now = outpostText(lv, e);
            String next = "";
            if (lv < OutpostEntry.MAX_LEVEL) {
                int savedLevel = e.level;
                e.level = lv + 1;
                next = outpostText(lv + 1, e);
                e.level = savedLevel;
            }
            rows.add(new SyncFactionUpgradesPacket.Row(Track.OUTPOST.ordinal(), e.id.toString(), "Outpost: " + e.name, lv,
                    OutpostEntry.MAX_LEVEL, lv >= OutpostEntry.MAX_LEVEL ? 0 : e.upgradeCost(), now, next));
        }
        PacketDistributor.sendToPlayer(player, new SyncFactionUpgradesPacket(warbondsInVault(server, id), warbondsCarried(player),
                officer, rows));
    }

    private static SeamPath pathOf(Track track) {
        return switch (track) {
            case SEAM_SPEED -> SeamPath.SPEED;
            case SEAM_QUANTITY -> SeamPath.QUANTITY;
            case SEAM_EFFICIENCY -> SeamPath.EFFICIENCY;
            default -> SeamPath.NONE;
        };
    }

    // Stats for the Upgrades tab, separated by " · " (the tab shows one per line).

    private static String vaultText(int level) {
        int secure = FactionItemVaults.secureSlots(level);
        return FactionItemVaults.slots(level) + " slots · " + secure + " secure slot" + (secure == 1 ? "" : "s");
    }

    /** {@code e.level} is set to {@code level} just long enough to read its stats. */
    private static String outpostText(int level, OutpostEntry e) {
        int saved = e.level;
        e.level = level;
        String text = OutpostEntry.chunkOffsets(level).size() + " chunks of land · " + e.maxHp() + " flag HP · Box: "
                + e.storageKinds() + " kinds × " + e.storagePerKind();
        e.level = saved;
        return text;
    }

    private static String rateText(int level) {
        return "Bonus +" + MeterBlockEntity.bonusPercent(level) + "%"
                + " · Power: " + num(MeterBlockEntity.scaled(Config.METER_POWER_RATE.get(), level)) + " FE/t, holds "
                + num(MeterBlockEntity.scaled(Config.METER_POWER_CAPACITY.get(), level)) + " FE"
                + " · Fluid: " + num(MeterBlockEntity.scaled(Config.METER_FLUID_RATE.get(), level)) + " mB/s, holds "
                + num(MeterBlockEntity.scaled(Config.METER_FLUID_CAPACITY.get(), level)) + " mB"
                + " · Coin storage: " + com.admin82.factions.economy.Currency.format(
                        MeterBlockEntity.scaled(Config.METER_COIN_CAP.get(), level)).replaceAll("§.", "");
    }

    private static String num(long n) {
        return String.format("%,d", n);
    }

    private static void fail(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal("§c" + message), true);
    }
}
