package com.admin82.factions.network.packet;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.faction.FactionMember;
import com.admin82.factions.faction.FactionPermission;
import com.admin82.factions.faction.FactionRole;
import com.admin82.factions.util.BypassManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.admin82.factions.AdminsFactions.MODID;

/**
 * Client → Server: deposit or withdraw coins between personal wallet / faction vault.
 *
 * DEPOSIT_WALLET  — physical coins → personal wallet balance
 * WITHDRAW_WALLET — personal wallet balance → physical coins
 * DEPOSIT_FACTION — personal wallet balance → faction vault
 * WITHDRAW_FACTION — faction vault → personal wallet balance (requires VAULT_WITHDRAW perm or owner)
 */
public record VaultActionPacket(Action action, long amount) implements CustomPacketPayload {

    public enum Action { DEPOSIT_WALLET, WITHDRAW_WALLET, DEPOSIT_FACTION, WITHDRAW_FACTION }

    public static final Type<VaultActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "vault_action"));

    public static final StreamCodec<FriendlyByteBuf, VaultActionPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> { buf.writeVarInt(pkt.action.ordinal()); buf.writeLong(pkt.amount); },
                    buf -> new VaultActionPacket(Action.values()[buf.readVarInt()], buf.readLong())
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(VaultActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            var server = sp.getServer(); if (server == null) return;
            var eco    = EconomyManager.get(server);
            long amount = pkt.amount();
            if (amount <= 0) { sp.displayClientMessage(Component.literal("§cAmount must be > 0."), true); return; }

            var factionMgr = FactionManager.get(server.overworld());
            var faction    = factionMgr.getFactionForPlayer(sp.getUUID());

            switch (pkt.action()) {
                case DEPOSIT_WALLET -> {
                    if (!EconomyManager.removeCoinsFromInventory(sp, amount)) {
                        sp.displayClientMessage(Component.literal("§cNot enough coins in your inventory."), true);
                        return;
                    }
                    eco.addWallet(sp.getUUID(), amount);
                    sp.displayClientMessage(Component.literal("§aDeposited §e"
                            + Currency.format(amount) + "§a into your wallet."), true);
                    sp.inventoryMenu.broadcastChanges();
                }
                case WITHDRAW_WALLET -> {
                    if (!eco.deductWallet(sp.getUUID(), amount)) {
                        sp.displayClientMessage(Component.literal("§cInsufficient wallet balance."), true);
                        return;
                    }
                    EconomyManager.giveCopperToInventory(sp, amount);
                    sp.displayClientMessage(Component.literal("§aWithdrew §e"
                            + Currency.format(amount) + "§a from your wallet."), true);
                    sp.inventoryMenu.broadcastChanges();
                }
                case DEPOSIT_FACTION -> {
                    if (faction == null) { sp.displayClientMessage(Component.literal("§cNot in a faction."), true); return; }
                    if (!eco.deductWallet(sp.getUUID(), amount)) {
                        sp.displayClientMessage(Component.literal("§cInsufficient wallet balance."), true);
                        return;
                    }
                    eco.addVault(faction.getId(), amount);
                    sp.displayClientMessage(Component.literal("§aDeposited §e"
                            + Currency.format(amount) + "§a into the faction vault."), true);
                }
                case WITHDRAW_FACTION -> {
                    if (faction == null) { sp.displayClientMessage(Component.literal("§cNot in a faction."), true); return; }
                    // Check permission
                    FactionMember member = faction.getMember(sp.getUUID());
                    boolean canWithdraw = faction.getOwnerId().equals(sp.getUUID())
                            || BypassManager.isBypassing(sp.getUUID())
                            || (member != null && faction.getRolePermission(member.getRole(), FactionPermission.VAULT_WITHDRAW));
                    if (!canWithdraw) {
                        sp.displayClientMessage(Component.literal("§cYou don't have permission to withdraw from the vault."), true);
                        return;
                    }
                    if (!eco.deductVault(faction.getId(), amount)) {
                        sp.displayClientMessage(Component.literal("§cInsufficient vault balance."), true);
                        return;
                    }
                    eco.addWallet(sp.getUUID(), amount);
                    sp.displayClientMessage(Component.literal("§aWithdrew §e"
                            + Currency.format(amount) + "§a from the faction vault."), true);
                }
            }

            // Sync updated balances back to client
            long vaultBal = faction != null ? eco.getVault(faction.getId()) : 0L;
            PacketDistributor.sendToPlayer(sp, new SyncEconomyPacket(eco.getWallet(sp.getUUID()), vaultBal));
        });
    }
}
