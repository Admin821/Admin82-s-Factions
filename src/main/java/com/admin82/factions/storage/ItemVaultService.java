package com.admin82.factions.storage;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.Faction;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionMember;
import com.admin82.factions.faction.FactionPermission;
import com.admin82.factions.faction.UpgradeService;
import com.admin82.factions.menu.ItemVaultMenu;
import com.admin82.factions.network.packet.SyncItemVaultPacket;
import com.admin82.factions.outpost.OutpostData;
import com.admin82.factions.outpost.OutpostEntry;
import com.admin82.factions.outpost.OutpostService;
import com.admin82.factions.util.BypassManager;
import com.admin82.factions.war.WarSpoils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Opening, filling and emptying item vaults (the faction item vault and outpost storage boxes),
 * with live updates to everyone who has the same vault open.
 */
public final class ItemVaultService {

    public enum Kind { FACTION, OUTPOST, SPOILS }

    public enum Action { DEPOSIT_SLOT, DEPOSIT_ALL_OF, WITHDRAW, UPGRADE, TAKE_COINS }

    /** What a player can do with one vault right now. */
    private record Access(ItemVault vault, String title, int maxKinds, long maxPerKind, int maxLevel,
                          boolean canWithdraw, boolean canUpgrade, long upgradeCost, String upgradeNote, Runnable changed,
                          boolean canDeposit, long coins) {}

    private ItemVaultService() {}

    @Nullable
    private static Access access(ServerPlayer sp, Kind kind, UUID id, boolean quiet) {
        MinecraftServer server = sp.server;
        FactionManager fmgr = FactionManager.get(server);
        if (kind == Kind.FACTION) return null; // the faction vault has its own screen (FactionVaultService)
        if (kind == Kind.SPOILS) {
            var spoils = WarSpoils.get(server).get(id);
            if (spoils == null) { if (!quiet) fail(sp, "Someone already took everything."); return null; }
            Faction mine = fmgr.getFactionForPlayer(sp.getUUID());
            boolean winner = mine != null && mine.getId().equals(spoils.winnerFactionId);
            if (!winner && !BypassManager.isBypassing(sp.getUUID())) {
                if (!quiet) fail(sp, "Only the faction that took this core can loot it.");
                return null;
            }
            return new Access(spoils.items, "Spoils of " + spoils.loserName, Integer.MAX_VALUE, Long.MAX_VALUE, 1,
                    true, false, 0, "", () -> WarSpoils.get(server).changed(), false, spoils.coins);
        }
        OutpostEntry entry = OutpostData.get(server).getOutpost(id);
        if (entry == null) { if (!quiet) fail(sp, "That outpost is gone."); return null; }
        Faction owner = fmgr.getFaction(entry.ownerFactionId);
        FactionMember member = owner == null ? null : owner.getMember(sp.getUUID());
        if (member == null) {
            if (!quiet) fail(sp, "This storage belongs to " + (owner != null ? owner.getName() : "another faction") + ".");
            return null;
        }
        // Putting items in and taking them out both need the "use storage" permission.
        if (!owner.getRolePermission(member.getRole(), FactionPermission.MEMBER_USE_STORAGE)) {
            if (!quiet) fail(sp, "Your rank needs the \"Use storage\" permission to use the storage box.");
            return null;
        }
        if (entry.storageBoxPos != null && sp.distanceToSqr(entry.storageBoxPos.getCenter()) > 64) {
            if (!quiet) fail(sp, "You're too far from the storage box.");
            return null;
        }
        return new Access(entry.storage, "Outpost Storage Box", entry.storageKinds(), entry.storagePerKind(),
                OutpostEntry.MAX_LEVEL, owner.getRolePermission(member.getRole(), FactionPermission.MEMBER_USE_STORAGE),
                false, 0, "Upgrade the outpost in Faction Upgrades.", () -> OutpostData.get(server).setDirty(), true, -1);
    }

    /** Opens a vault screen for the player. */
    public static void open(ServerPlayer sp, Kind kind, UUID id) {
        if (kind == Kind.FACTION) { FactionVaultService.open(sp); return; }
        Access a = access(sp, kind, id, false);
        if (a == null) return;
        sp.openMenu(new SimpleMenuProvider((containerId, inv, p) -> new ItemVaultMenu(containerId, inv, kind, id),
                Component.literal(a.title)), buf -> {
            buf.writeVarInt(kind.ordinal());
            buf.writeUUID(id);
            buf.writeVarInt(0); // LDLib2 UISyncManager initial pack
        });
        sendSync(sp, kind, id);
    }

    public static void handle(ServerPlayer sp, Kind kind, UUID id, Action action, int index, int amount) {
        if (!(sp.containerMenu instanceof ItemVaultMenu menu) || !menu.matches(kind, id)) return;
        Access a = access(sp, kind, id, false);
        if (a == null) { sp.closeContainer(); return; }
        var items = sp.getInventory().items;
        switch (action) {
            case DEPOSIT_SLOT, DEPOSIT_ALL_OF -> {
                if (!a.canDeposit) { fail(sp, "You can only take things out of here."); return; }
                if (index < 0 || index >= items.size()) return;
                ItemStack clicked = items.get(index);
                if (clicked.isEmpty()) return;
                if (!StorageRules.allowed(clicked)) { fail(sp, StorageRules.MESSAGE); return; }
                ItemStack template = clicked.copyWithCount(1);
                int moved = 0;
                for (int i = 0; i < items.size(); i++) {
                    if (action == Action.DEPOSIT_SLOT && i != index) continue;
                    ItemStack s = items.get(i);
                    if (!ItemStack.isSameItemSameComponents(s, template)) continue;
                    moved += a.vault.deposit(s, a.maxKinds, a.maxPerKind);
                }
                if (moved == 0) {
                    fail(sp, a.vault.entries().size() >= a.maxKinds
                            ? "It's full: it holds " + a.maxKinds + " different items."
                            : "There's no more room for that item (" + a.maxPerKind + " each).");
                    return;
                }
            }
            case WITHDRAW -> {
                if (!a.canWithdraw) { fail(sp, "Your rank isn't allowed to take items out."); return; }
                if (index < 0 || index >= a.vault.entries().size()) return;
                ItemStack template = a.vault.entries().get(index).item.copy();
                int remaining = Math.max(1, amount);
                // One stack at a time until the amount is reached, the item runs out, or the inventory is full.
                while (remaining > 0) {
                    int at = -1;
                    for (int i = 0; i < a.vault.entries().size(); i++) {
                        if (ItemStack.isSameItemSameComponents(a.vault.entries().get(i).item, template)) { at = i; break; }
                    }
                    if (at < 0) break;
                    ItemStack taken = a.vault.withdraw(at, remaining);
                    if (taken.isEmpty()) break;
                    int got = taken.getCount();
                    sp.getInventory().add(taken);
                    if (!taken.isEmpty()) { a.vault.putBack(taken); break; } // inventory full
                    remaining -= got;
                }
            }
            case TAKE_COINS -> {
                if (kind != Kind.SPOILS) return;
                var spoils = WarSpoils.get(sp.server).get(id);
                Faction mine = FactionManager.get(sp.server).getFactionForPlayer(sp.getUUID());
                if (spoils == null || spoils.coins <= 0 || mine == null) return;
                EconomyManager.get(sp.server).addVault(mine.getId(), spoils.coins, "Spoils from " + spoils.loserName);
                sp.displayClientMessage(Component.literal("§a[Spoils] " + Currency.format(spoils.coins).replaceAll("§.", "")
                        + " went to your faction vault."), false);
                spoils.coins = 0;
            }
            case UPGRADE -> {
                if (kind != Kind.FACTION) return;
                UpgradeService.upgrade(sp, UpgradeService.Track.ITEM_VAULT, null);
            }
        }
        a.changed.run();
        sp.getInventory().setChanged();
        com.admin82.factions.network.InventorySync.push(sp);
        push(sp.server, kind, id);
        if (kind == Kind.SPOILS) crumbleIfEmpty(sp.server, id);
    }

    /** Once everything is taken, the old faction table crumbles and everyone looting it is closed out. */
    private static void crumbleIfEmpty(MinecraftServer server, UUID id) {
        var all = WarSpoils.get(server);
        var spoils = all.get(id);
        if (spoils == null || !spoils.isEmpty()) return;
        all.remove(id);
        var level = OutpostService.level(server, spoils.dimension);
        if (level != null) level.setBlock(spoils.pos, Blocks.AIR.defaultBlockState(), 3);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof ItemVaultMenu m && m.matches(Kind.SPOILS, id)) {
                p.closeContainer();
                p.displayClientMessage(Component.literal("§6[Spoils] Looted clean. The old faction table crumbles."), true);
            }
        }
    }

    /** Sends the vault's contents to everyone who has it open. */
    public static void push(MinecraftServer server, Kind kind, UUID id) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.containerMenu instanceof ItemVaultMenu m && m.matches(kind, id)) sendSync(p, kind, id);
        }
    }

    /** The faction vault is a shared container, so open screens update on their own. */
    public static void pushFaction(MinecraftServer server, UUID faction) { }

    private static void sendSync(ServerPlayer sp, Kind kind, UUID id) {
        Access a = access(sp, kind, id, true);
        if (a == null) return;
        List<SyncItemVaultPacket.Line> lines = new ArrayList<>();
        for (ItemVault.Entry e : a.vault.entries()) lines.add(new SyncItemVaultPacket.Line(e.item, e.count));
        PacketDistributor.sendToPlayer(sp, new SyncItemVaultPacket(kind.ordinal(), id, a.title, a.vault.level(), a.maxLevel,
                a.maxKinds, a.maxPerKind, lines, a.canWithdraw, a.canUpgrade, a.upgradeCost, a.upgradeNote, a.coins));
    }

    private static void fail(ServerPlayer sp, String message) {
        sp.displayClientMessage(Component.literal("§c" + message), true);
    }
}
