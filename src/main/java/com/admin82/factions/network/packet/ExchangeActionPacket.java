package com.admin82.factions.network.packet;

import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.ExchangeManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.admin82.factions.AdminsFactions.MODID;

/**
 * Client → Server: perform a currency-exchange related action.
 *
 * Actions:
 *   EXCHANGE     — exchange the item in {@code slot} for coins (all players).
 *   SET_RATE     — set/update an item exchange rate (op only, uses {@code itemId} + {@code rateCopper}).
 *   REMOVE_RATE  — remove an item exchange rate (op only, uses {@code itemId}).
 */
public record ExchangeActionPacket(
        Action action,
        int    slot,
        String itemId,
        long   rateCopper
) implements CustomPacketPayload {

    public enum Action { EXCHANGE, SET_RATE, REMOVE_RATE }

    // ── Convenience factories ─────────────────────────────────────────────────

    public static ExchangeActionPacket exchange(int slot) {
        return new ExchangeActionPacket(Action.EXCHANGE, slot, "", 0L);
    }

    public static ExchangeActionPacket setRate(String itemId, long rateCopper) {
        return new ExchangeActionPacket(Action.SET_RATE, -1, itemId, rateCopper);
    }

    public static ExchangeActionPacket removeRate(String itemId) {
        return new ExchangeActionPacket(Action.REMOVE_RATE, -1, itemId, 0L);
    }

    // ── Network plumbing ──────────────────────────────────────────────────────

    public static final Type<ExchangeActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "exchange_action"));

    public static final StreamCodec<FriendlyByteBuf, ExchangeActionPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.action().ordinal());
                        buf.writeVarInt(pkt.slot());
                        buf.writeUtf(pkt.itemId(), 256);
                        buf.writeLong(pkt.rateCopper());
                    },
                    buf -> new ExchangeActionPacket(
                            Action.values()[buf.readVarInt()],
                            buf.readVarInt(),
                            buf.readUtf(256),
                            buf.readLong()
                    )
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ── Server-side handler ───────────────────────────────────────────────────

    public static void handle(ExchangeActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            switch (pkt.action()) {
                case EXCHANGE    -> handleExchange(sp, pkt.slot());
                case SET_RATE    -> handleSetRate(sp, pkt.itemId(), pkt.rateCopper());
                case REMOVE_RATE -> handleRemoveRate(sp, pkt.itemId());
            }
        });
    }

    private static void handleExchange(ServerPlayer sp, int slot) {
        if (slot < 0 || slot >= sp.getInventory().getContainerSize()) return;
        ItemStack stack = sp.getInventory().getItem(slot);
        if (stack.isEmpty()) { sp.displayClientMessage(Component.literal("§cNo item in that slot."), true); return; }

        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(stack.getItem()).toString();
        long ratePerItem = ExchangeManager.get(sp.getServer()).getRate(itemId);
        if (ratePerItem <= 0) {
            sp.displayClientMessage(Component.literal("§cThat item has no exchange rate."), true);
            return;
        }
        long totalCopper = ratePerItem * stack.getCount();
        sp.getInventory().setItem(slot, ItemStack.EMPTY);
        EconomyManager.giveCopperToInventory(sp, totalCopper);
        sp.displayClientMessage(
                Component.literal("§aExchanged §e" + stack.getCount() + "x §f"
                        + stack.getHoverName().getString()
                        + " §afor §e" + Currency.format(totalCopper)), true);
    }

    private static void handleSetRate(ServerPlayer sp, String itemId, long rateCopper) {
        if (!sp.hasPermissions(2)) {
            sp.displayClientMessage(Component.literal("§cOperator permission required."), true);
            return;
        }
        if (itemId.isBlank() || rateCopper <= 0) {
            sp.displayClientMessage(Component.literal("§cInvalid item or rate."), true);
            return;
        }
        ExchangeManager.get(sp.getServer()).setRate(itemId, rateCopper);
        sp.displayClientMessage(
                Component.literal("§aSet: §e" + itemId + " §a→ §e" + Currency.format(rateCopper) + " §aper item."), true);
    }

    private static void handleRemoveRate(ServerPlayer sp, String itemId) {
        if (!sp.hasPermissions(2)) {
            sp.displayClientMessage(Component.literal("§cOperator permission required."), true);
            return;
        }
        boolean removed = ExchangeManager.get(sp.getServer()).hasRate(itemId);
        ExchangeManager.get(sp.getServer()).removeRate(itemId);
        sp.displayClientMessage(removed
                ? Component.literal("§aRemoved rate for §e" + itemId + "§a.")
                : Component.literal("§cNo rate found for §e" + itemId + "§c."), true);
    }
}
