package com.admin82.factions.network.packet;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.ExchangeManager;
import com.admin82.factions.economy.Treasury;
import com.admin82.factions.menu.CurrencyExchangeMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

import static com.admin82.factions.AdminsFactions.MODID;

/**
 * Client → Server: a Currency Exchange action. The server answers every action with a
 * {@link SyncExchangePacket} so the open screen shows fresh rates and balances.
 *
 * <ul>
 *   <li>SELL, sell {@code amount} of {@code itemId} from the inventory; paid as coins or to the wallet.</li>
 *   <li>SELL_ALL, sell every exchangeable item in the inventory.</li>
 *   <li>BUY, buy {@code amount} of {@code itemId} (two-way exchange only), paid with coins or from the wallet.</li>
 *   <li>DEPOSIT_COINS / WITHDRAW_COINS, move coins between the inventory and the wallet.</li>
 *   <li>SET_RATE / REMOVE_RATE / SET_TWO_WAY, operator rate management.</li>
 *   <li>REFRESH, just re-sync.</li>
 * </ul>
 */
public record ExchangeActionPacket(
        Action  action,
        String  itemId,
        int     amount,
        long    copper,
        boolean flag
) implements CustomPacketPayload {

    public enum Action { SELL, SELL_ALL, BUY, DEPOSIT_COINS, WITHDRAW_COINS, SET_RATE, REMOVE_RATE, SET_TWO_WAY, REFRESH }

    /** Most items one buy can hand out. */
    public static final int MAX_BUY = 64 * 36;

    public static ExchangeActionPacket sell(String itemId, int amount, boolean toWallet) {
        return new ExchangeActionPacket(Action.SELL, itemId, amount, 0L, toWallet);
    }
    public static ExchangeActionPacket sellAll(boolean toWallet) {
        return new ExchangeActionPacket(Action.SELL_ALL, "", 0, 0L, toWallet);
    }
    public static ExchangeActionPacket buy(String itemId, int amount, boolean fromWallet) {
        return new ExchangeActionPacket(Action.BUY, itemId, amount, 0L, fromWallet);
    }
    public static ExchangeActionPacket depositCoins() {
        return new ExchangeActionPacket(Action.DEPOSIT_COINS, "", 0, 0L, false);
    }
    public static ExchangeActionPacket withdrawCoins(long copper) {
        return new ExchangeActionPacket(Action.WITHDRAW_COINS, "", 0, copper, false);
    }
    /** {@code count} coins of one type ("Copper", "Silver", "Gold" or "Platinum"). */
    public static ExchangeActionPacket withdrawCoins(String coin, int count) {
        return new ExchangeActionPacket(Action.WITHDRAW_COINS, coin, count, 0L, false);
    }
    public static ExchangeActionPacket setRate(String itemId, long rateCopper) {
        return new ExchangeActionPacket(Action.SET_RATE, itemId, 0, rateCopper, false);
    }
    public static ExchangeActionPacket removeRate(String itemId) {
        return new ExchangeActionPacket(Action.REMOVE_RATE, itemId, 0, 0L, false);
    }
    public static ExchangeActionPacket setTwoWay(boolean enabled) {
        return new ExchangeActionPacket(Action.SET_TWO_WAY, "", 0, 0L, enabled);
    }
    public static ExchangeActionPacket refresh() {
        return new ExchangeActionPacket(Action.REFRESH, "", 0, 0L, false);
    }

    public static final Type<ExchangeActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "exchange_action"));

    public static final StreamCodec<FriendlyByteBuf, ExchangeActionPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.action().ordinal());
                        buf.writeUtf(pkt.itemId(), 256);
                        buf.writeVarInt(pkt.amount());
                        buf.writeLong(pkt.copper());
                        buf.writeBoolean(pkt.flag());
                    },
                    buf -> {
                        int ordinal = buf.readVarInt();
                        Action action = ordinal >= 0 && ordinal < Action.values().length ? Action.values()[ordinal] : Action.REFRESH;
                        return new ExchangeActionPacket(action, buf.readUtf(256), buf.readVarInt(), buf.readLong(), buf.readBoolean());
                    });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // Server-side handler

    public static void handle(ExchangeActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // Exchange actions only work from an open exchange screen.
            if (!(sp.containerMenu instanceof CurrencyExchangeMenu)) return;
            switch (pkt.action()) {
                case SELL           -> handleSell(sp, pkt.itemId(), pkt.amount(), pkt.flag());
                case SELL_ALL       -> handleSellAll(sp, pkt.flag());
                case BUY            -> handleBuy(sp, pkt.itemId(), pkt.amount(), pkt.flag());
                case DEPOSIT_COINS  -> handleDeposit(sp);
                case WITHDRAW_COINS -> {
                    if (Currency.COINS.contains(pkt.itemId())) handleWithdrawCoins(sp, pkt.itemId(), pkt.amount());
                    else handleWithdraw(sp, pkt.copper());
                }
                case SET_RATE       -> handleSetRate(sp, pkt.itemId(), pkt.copper());
                case REMOVE_RATE    -> handleRemoveRate(sp, pkt.itemId());
                case SET_TWO_WAY    -> handleTwoWay(sp, pkt.flag());
                case REFRESH        -> { }
            }
            syncInventory(sp);
            sendSync(sp);
        });
    }

    /** Sends the current rates, two-way flag and wallet to a player with the exchange open. */
    public static void sendSync(ServerPlayer sp) {
        ExchangeManager exchange = ExchangeManager.get(sp.server);
        PacketDistributor.sendToPlayer(sp, new SyncExchangePacket(
                new LinkedHashMap<>(exchange.getRates()), exchange.isBothWaysExchange(),
                EconomyManager.get(sp.server).getWallet(sp.getUUID())));
    }

    private static void handleSell(ServerPlayer sp, String itemId, int amount, boolean toWallet) {
        long rate = ExchangeManager.get(sp.server).getRate(itemId);
        if (rate <= 0) { fail(sp, "That item has no exchange rate."); return; }
        int have = countSellable(sp, itemId);
        int sell = Math.min(have, Math.max(1, amount));
        if (sell <= 0) { fail(sp, "You don't have any of that item."); return; }
        long total;
        try { total = Math.multiplyExact(rate, (long) sell); }
        catch (ArithmeticException ex) { fail(sp, "That is too much to exchange at once."); return; }

        removeSellable(sp, itemId, sell);
        String name = itemName(itemId);
        Treasury.record(sp.server, Treasury.Source.EXCHANGE_PAYOUTS,
                total, sp.getGameProfile().getName(), sell + "× " + name);
        pay(sp, total, toWallet, "Exchanged " + sell + "× " + name);
        sp.displayClientMessage(Component.literal("§aSold §e" + sell + "× §f" + name + " §afor §e"
                + Currency.format(total) + (toWallet ? " §7(to wallet)" : "")), true);
    }

    private static void handleSellAll(ServerPlayer sp, boolean toWallet) {
        ExchangeManager exchange = ExchangeManager.get(sp.server);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
            ItemStack stack = sp.getInventory().getItem(i);
            if (!ExchangeManager.isSellable(stack)) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (exchange.getRate(id) > 0) counts.merge(id, stack.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) { fail(sp, "Nothing in your inventory can be exchanged."); return; }
        long total = 0;
        int items = 0;
        // Work out the total before taking anything, so an overflow can't take items without paying.
        try {
            for (var e : counts.entrySet()) {
                total = Math.addExact(total, Math.multiplyExact(exchange.getRate(e.getKey()), (long) e.getValue()));
            }
        } catch (ArithmeticException overflow) {
            fail(sp, "That's too much to exchange at once.");
            return;
        }
        for (var e : counts.entrySet()) {
            items += e.getValue();
            removeSellable(sp, e.getKey(), e.getValue());
        }
        pay(sp, total, toWallet, "Exchanged " + items + " items (" + counts.size() + " kinds)");
        Treasury.record(sp.server, Treasury.Source.EXCHANGE_PAYOUTS,
                total, sp.getGameProfile().getName(), "Sold everything: " + items + " items");
        sp.displayClientMessage(Component.literal("§aSold §e" + items + " §aitems for §e" + Currency.format(total)
                + (toWallet ? " §7(to wallet)" : "")), true);
    }

    private static void handleBuy(ServerPlayer sp, String itemId, int amount, boolean fromWallet) {
        ExchangeManager exchange = ExchangeManager.get(sp.server);
        if (!exchange.isBothWaysExchange()) { fail(sp, "Buying items from the exchange is disabled."); return; }
        long rate = exchange.getRate(itemId);
        Item item = resolve(itemId);
        if (rate <= 0 || item == null) { fail(sp, "That item has no exchange rate."); return; }
        int count = Math.max(1, Math.min(MAX_BUY, amount));
        long cost;
        try {
            cost = Math.multiplyExact(rate, (long) count);
        } catch (ArithmeticException overflow) {
            fail(sp, "That's too many to buy at once.");
            return;
        }

        if (fromWallet) {
            if (!EconomyManager.get(sp.server).deductWallet(sp.getUUID(), cost, "Bought " + count + "× " + itemName(itemId) + " at the exchange")) {
                fail(sp, "Your wallet needs " + Currency.format(cost).replaceAll("§.", "") + ".");
                return;
            }
        } else if (!EconomyManager.removeCoinsFromInventory(sp, cost)) {
            fail(sp, "Not enough coins. Need " + Currency.format(cost).replaceAll("§.", "") + ".");
            return;
        }
        Treasury.record(sp.server, Treasury.Source.EXCHANGE_PURCHASES,
                cost, sp.getGameProfile().getName(), count + "× " + itemName(itemId));
        int remaining = count;
        while (remaining > 0) {
            int size = Math.min(remaining, item.getDefaultMaxStackSize());
            ItemStack stack = new ItemStack(item, size);
            if (!sp.getInventory().add(stack) && !stack.isEmpty()) sp.drop(stack, false);
            remaining -= size;
        }
        sp.displayClientMessage(Component.literal("§aBought §e" + count + "× §f" + itemName(itemId) + " §afor §e"
                + Currency.format(cost)), true);
    }

    private static void handleDeposit(ServerPlayer sp) {
        long coins = EconomyManager.countCoinsInInventory(sp);
        if (coins <= 0) { fail(sp, "You have no coins to deposit."); return; }
        EconomyManager.removeCoinsFromInventory(sp, coins);
        EconomyManager.get(sp.server).addWallet(sp.getUUID(), coins, "Deposited coins at the exchange");
        sp.displayClientMessage(Component.literal("§aDeposited §e" + Currency.format(coins) + " §ato your wallet."), true);
    }

    /** {@code count} coins of exactly the type picked; any that don't fit in the inventory go back to the wallet. */
    private static void handleWithdrawCoins(ServerPlayer sp, String coin, int count) {
        if (count <= 0) { fail(sp, "Enter how many coins to withdraw."); return; }
        long each = Currency.perCoin(coin);
        long copper = each * count;
        EconomyManager eco = EconomyManager.get(sp.server);
        if (!eco.deductWallet(sp.getUUID(), copper, "Withdrew coins at the exchange")) {
            fail(sp, "Your wallet doesn't have that much.");
            return;
        }
        long left = EconomyManager.giveCoinsOfType(sp, coin, count);
        if (left > 0) eco.addWallet(sp.getUUID(), left * each, "Coins that didn't fit in your inventory");
        long given = count - left;
        sp.displayClientMessage(Component.literal("§aWithdrew §e" + given + " " + coin + " §acoin" + (given == 1 ? "" : "s")
                + (left > 0 ? " §7(" + left + " didn't fit and stayed in your wallet)" : "") + "."), true);
    }

    private static void handleWithdraw(ServerPlayer sp, long copper) {
        if (copper <= 0) { fail(sp, "Enter an amount to withdraw."); return; }
        if (!EconomyManager.get(sp.server).deductWallet(sp.getUUID(), copper, "Withdrew coins at the exchange")) {
            fail(sp, "Your wallet doesn't have that much.");
            return;
        }
        EconomyManager.giveCopperToInventory(sp, copper);
        sp.displayClientMessage(Component.literal("§aWithdrew §e" + Currency.format(copper) + " §aas coins."), true);
    }

    private static void handleSetRate(ServerPlayer sp, String itemId, long rateCopper) {
        if (!sp.hasPermissions(2)) { fail(sp, "Operator permission required."); return; }
        Item item = resolve(itemId);
        if (item == null || rateCopper <= 0) { fail(sp, "Invalid item or rate."); return; }
        if (EconomyManager.isCoin(item)) { fail(sp, "Coins can't have an exchange rate."); return; }
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        ExchangeManager.get(sp.server).setRate(id, rateCopper);
        sp.displayClientMessage(Component.literal("§aRate set: §f" + itemName(id) + " §a→ §e"
                + Currency.format(rateCopper) + " §aeach."), true);
    }

    private static void handleRemoveRate(ServerPlayer sp, String itemId) {
        if (!sp.hasPermissions(2)) { fail(sp, "Operator permission required."); return; }
        ExchangeManager exchange = ExchangeManager.get(sp.server);
        if (!exchange.hasRate(itemId)) { fail(sp, "No rate found for that item."); return; }
        exchange.removeRate(itemId);
        sp.displayClientMessage(Component.literal("§aRemoved the rate for §f" + itemName(itemId) + "§a."), true);
    }

    private static void handleTwoWay(ServerPlayer sp, boolean enabled) {
        if (!sp.hasPermissions(2)) { fail(sp, "Operator permission required."); return; }
        ExchangeManager.get(sp.server).setBothWaysExchange(enabled);
        sp.displayClientMessage(Component.literal(enabled
                ? "§aPlayers can now buy items from the exchange."
                : "§ePlayers can no longer buy items from the exchange."), true);
    }

    // Helpers

    private static void pay(ServerPlayer sp, long copper, boolean toWallet, String reason) {
        if (toWallet) EconomyManager.get(sp.server).addWallet(sp.getUUID(), copper, reason);
        else EconomyManager.giveCopperToInventory(sp, copper);
    }

    private static int countSellable(ServerPlayer sp, String itemId) {
        int total = 0;
        for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
            ItemStack stack = sp.getInventory().getItem(i);
            if (ExchangeManager.isSellable(stack) && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static void removeSellable(ServerPlayer sp, String itemId, int amount) {
        int remaining = amount;
        for (int i = 0; i < sp.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack stack = sp.getInventory().getItem(i);
            if (ExchangeManager.isSellable(stack) && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
    }

    @Nullable
    private static Item resolve(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId == null ? "" : itemId);
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }

    private static String itemName(String itemId) {
        Item item = resolve(itemId);
        return item != null ? item.getDescription().getString() : itemId;
    }

    private static void fail(ServerPlayer sp, String message) {
        sp.displayClientMessage(Component.literal("§c" + message), true);
    }

    private static void syncInventory(ServerPlayer player) {
        com.admin82.factions.network.InventorySync.push(player);
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }
}
