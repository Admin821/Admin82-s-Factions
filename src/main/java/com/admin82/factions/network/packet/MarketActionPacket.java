package com.admin82.factions.network.packet;

import com.admin82.factions.Config;
import com.admin82.factions.economy.*;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.menu.market.MarketMenu;
import com.admin82.factions.network.LiveSync;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;
import java.util.UUID;

import static com.admin82.factions.AdminsFactions.MODID;

/**
 * Client → Server: create, buy, bid, or cancel a market listing.
 */
public record MarketActionPacket(
        Action action,
        UUID   listingId,       // used for BUY / BID / CANCEL
        int    inventorySlot,   // used for CREATE (slot in player inventory)
        long   price,           // BIN price or auction starting bid
        int    durationHours,   // auction duration
        boolean isAuction,
        int listingKind,
        String itemId,          // used for buy orders that do not come from inventory
        int itemCount
) implements CustomPacketPayload {

    public enum Action { CREATE, BUY, BID, CANCEL, REFRESH, CLAIM_SOLD, FULFILL_BUY_ORDER, CLAIM_DELIVERY }

    public MarketActionPacket(Action action, UUID listingId, int inventorySlot, long price, int durationHours, boolean isAuction) {
        this(action, listingId, inventorySlot, price, durationHours, isAuction, MarketListing.ListingKind.PLAYER_SELL.ordinal());
    }

    public MarketActionPacket(Action action, UUID listingId, int inventorySlot, long price, int durationHours, boolean isAuction, int listingKind) {
        this(action, listingId, inventorySlot, price, durationHours, isAuction, listingKind, "", 0);
    }

    public static final Type<MarketActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "market_action"));

    public static final StreamCodec<FriendlyByteBuf, MarketActionPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pkt) -> {
                        buf.writeVarInt(pkt.action.ordinal());
                        buf.writeUUID(pkt.listingId);
                        buf.writeVarInt(pkt.inventorySlot);
                        buf.writeLong(pkt.price);
                        buf.writeVarInt(pkt.durationHours);
                        buf.writeBoolean(pkt.isAuction);
                        buf.writeVarInt(pkt.listingKind);
                        buf.writeUtf(pkt.itemId == null ? "" : pkt.itemId);
                        buf.writeVarInt(pkt.itemCount);
                    },
                    buf -> new MarketActionPacket(
                            Action.values()[buf.readVarInt()],
                            buf.readUUID(),
                            buf.readVarInt(),
                            buf.readLong(),
                            buf.readVarInt(),
                            buf.readBoolean(),
                                buf.readVarInt(),
                                buf.readUtf(),
                                buf.readVarInt()
                    )
            );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(MarketActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            var server  = sp.getServer(); if (server == null) return;
            var eco     = EconomyManager.get(server);
            var market  = MarketManager.get(server);
            var faction = FactionManager.get(server.overworld()).getFactionForPlayer(sp.getUUID());
            UUID factionId = faction != null ? faction.getId() : null;
            // Market actions only work from an open market screen.
            if (!(sp.containerMenu instanceof MarketMenu)) return;

            switch (pkt.action()) {
                case CREATE -> {
                    MarketListing.ListingKind kind = listingKind(pkt.listingKind());
                    boolean buyOrder = kind == MarketListing.ListingKind.PLAYER_BUY_ORDER || kind == MarketListing.ListingKind.ADMIN_BUY_ORDER;
                    boolean adminListing = kind == MarketListing.ListingKind.ADMIN_SELL || kind == MarketListing.ListingKind.ADMIN_BUY_ORDER;
                    if (adminListing && !sp.hasPermissions(2)) { sp.displayClientMessage(Component.literal("§cOperator permission required."), true); break; }
                    if (!adminListing && market.countPlayerListings(sp.getUUID()) >= MarketManager.listingLimit()) {
                        sp.displayClientMessage(Component.literal("§cYou already have " + MarketManager.listingLimit()
                                + " listings up. Cancel or wait for one to finish first."), true);
                        break;
                    }
                    if (pkt.isAuction() && !buyOrder && !List.of(4, 8, 12, 16, 24).contains(pkt.durationHours())) {
                        sp.displayClientMessage(Component.literal("§cAuctions last 4, 8, 12, 16 or 24 hours."), true);
                        break;
                    }
                    int slot = pkt.inventorySlot();
                    ItemStack item;
                    int orderQuantity = 0;
                    long escrow = 0L;
                    if (buyOrder) {
                        item = resolveRequestedItem(pkt.itemId(), 1);
                        if (item.isEmpty()) { sp.displayClientMessage(Component.literal("§cChoose a valid item for the buy order."), true); break; }
                        if (kind == MarketListing.ListingKind.PLAYER_BUY_ORDER) {
                            orderQuantity = Math.max(1, Math.min(MarketListing.MAX_ORDER_QUANTITY, pkt.itemCount()));
                            try {
                                escrow = Math.multiplyExact(pkt.price(), (long) orderQuantity);
                            } catch (ArithmeticException overflow) {
                                sp.displayClientMessage(Component.literal("§cThat order is too expensive."), true);
                                break;
                            }
                        }
                    } else {
                        if (slot < 0 || slot >= sp.getInventory().getContainerSize()) break;
                        item = sp.getInventory().getItem(slot);
                        if (item.isEmpty()) { sp.displayClientMessage(Component.literal("§cNo item in that slot."), true); break; }
                    }

                    // Validate price
                    if (pkt.price() <= 0) { sp.displayClientMessage(Component.literal("§cPrice must be > 0."), true); break; }
                    if (buyOrder && kind == MarketListing.ListingKind.PLAYER_BUY_ORDER && !eco.deductWallet(sp.getUUID(), escrow,
                            "Buy order placed: " + orderQuantity + "× " + item.getHoverName().getString() + " (held)")) {
                        sp.displayClientMessage(Component.literal("§cYou need §e" + Currency.format(escrow)
                                + " §cin your wallet to place this order."), true);
                        break;
                    }

                    // Build listing
                    var listing = new MarketListing();
                    listing.listingId     = UUID.randomUUID();
                    listing.sellerUUID    = sp.getUUID();
                    listing.sellerFactionId = factionId;
                    listing.item          = item.copy();
                    listing.isAuction     = pkt.isAuction();
                    listing.price         = pkt.price();
                    listing.highestBid    = 0;
                    listing.highestBidder = null;
                    listing.durationHours = pkt.durationHours();
                        listing.expiresAt     = adminListing
                            ? Long.MAX_VALUE
                            : System.currentTimeMillis() + (pkt.isAuction() ? (long) pkt.durationHours() * 3_600_000L : 7L * 86_400_000L); // BIN/buy order = 7 days
                    listing.unpaidUpkeep  = false;
                    listing.kind          = kind;
                    listing.orderQuantity = orderQuantity;
                    listing.orderFilled   = 0;
                    listing.escrow        = escrow;
                    market.addListing(listing);
                    String createdName = item.getHoverName().getString();
                    if (kind == MarketListing.ListingKind.PLAYER_BUY_ORDER) {
                        market.logMarket(sp.getUUID(), -escrow, "Placed buy order: " + orderQuantity + "× " + createdName
                                + " at " + Currency.format(pkt.price()) + " ea", item);
                    } else if (kind == MarketListing.ListingKind.PLAYER_SELL) {
                        market.logMarket(sp.getUUID(), 0, (pkt.isAuction() ? "Started auction: " : "Listed: ")
                                + item.getCount() + "× " + createdName + " for " + Currency.format(pkt.price()), item);
                    }
                    if (!buyOrder && kind != MarketListing.ListingKind.ADMIN_SELL) {
                        sp.getInventory().setItem(slot, ItemStack.EMPTY);
                    }
                    sp.displayClientMessage(Component.literal(!buyOrder ? "§aListing created!"
                            : kind == MarketListing.ListingKind.ADMIN_BUY_ORDER ? "§aServer buy order created!"
                            : "§aBuy order placed for §f" + orderQuantity + " " + item.getHoverName().getString()
                              + " §aat §e" + Currency.format(pkt.price()) + " §aeach. §7(" + Currency.format(escrow) + " held)"), true);
                }
                case BUY -> {
                    var listing = market.getListing(pkt.listingId());
                    if (listing == null || listing.isAuction || listing.isBuyOrder()) { sp.displayClientMessage(Component.literal("§cListing not found."), true); break; }
                    if (listing.sellerUUID.equals(sp.getUUID())) {
                        sp.displayClientMessage(Component.literal(listing.kind == MarketListing.ListingKind.ADMIN_SELL
                                ? "§cYou cannot buy a server shop listing you created."
                                : "§cYou cannot buy your own listing."), true);
                        break;
                    }
                    String boughtName = listing.item.getHoverName().getString();
                    if (!eco.deductWallet(sp.getUUID(), listing.price, "Bought " + listing.item.getCount() + "× " + boughtName)) {
                        sp.displayClientMessage(Component.literal("§cInsufficient funds."), true);
                        break;
                    }
                    market.logMarket(sp.getUUID(), -listing.price, (listing.kind == MarketListing.ListingKind.ADMIN_SELL
                            ? "Bought from server shop: " : "Bought: ") + listing.item.getCount() + "× " + boughtName, listing.item);

                    if (listing.kind == MarketListing.ListingKind.ADMIN_SELL) {
                        Treasury.record(server, Treasury.Source.SERVER_SHOP, listing.price,
                                sp.getGameProfile().getName(), listing.item.getCount() + "× " + boughtName);
                        market.deliverItem(server, sp.getUUID(), listing.item);
                        sp.displayClientMessage(Component.literal("§aPurchased from server shop!"), true);
                        break;
                    }

                    long proceeds = listing.netProceeds(listing.price);
                    Treasury.record(server, Treasury.Source.MARKET_TAX_BUY_NOW, listing.price - proceeds,
                            MarketManager.resolvePlayerName(server, listing.sellerUUID), listing.item.getCount() + "× " + boughtName
                            + " sold for " + Currency.format(listing.price).replaceAll("§.", ""));
                    proceeds = MarketManager.afterVassalTax(server, listing.sellerUUID, proceeds);

                    // Give item to buyer
                    market.deliverItem(server, sp.getUUID(), listing.item);

                    // Create a SoldListing so the seller must claim proceeds
                    String buyerName = sp.getGameProfile().getName();
                    var sale = new SoldListing();
                    sale.saleId     = UUID.randomUUID();
                    sale.sellerUUID = listing.sellerUUID;
                    sale.item       = listing.item.copy();
                    sale.itemName   = listing.item.getHoverName().getString();
                    sale.buyerName  = buyerName;
                    sale.proceeds   = proceeds;
                    sale.soldAt     = System.currentTimeMillis();
                    market.addSoldListing(sale);
                    market.removeListing(listing.listingId);
                    int soldCount = Math.max(1, listing.item.getCount());
                    market.recordSale(listing.item, Math.max(1, listing.price / soldCount), soldCount, PricePoint.BUY_NOW);
                    market.logMarket(listing.sellerUUID, proceeds, "Sold to " + buyerName + ": " + soldCount + "× "
                            + sale.itemName + " (after tax)", listing.item);

                    sp.displayClientMessage(Component.literal("§aPurchased!"), true);

                    // Notify seller if online
                    final long fProceeds = proceeds;
                    final long fPrice    = listing.price;
                    ServerPlayer seller = server.getPlayerList().getPlayer(listing.sellerUUID);
                    if (seller != null) {
                        seller.displayClientMessage(Component.literal(
                                "§a[Market] §e" + buyerName + " §cpurchased §f" + sale.itemName
                                + " §cfor §e" + Currency.format(fPrice)
                                + " §c(§a" + Currency.format(fProceeds) + " §cafter tax)§c."
                                + " §eOpen My Listings at the market to claim your earnings!"), false);
                        PacketDistributor.sendToPlayer(seller, new SyncSoldListingsPacket(
                                market.getSoldListingsForPlayer(listing.sellerUUID)));
                    }
                }
                case BID -> {
                    var listing = market.getListing(pkt.listingId());
                    if (listing == null || !listing.isAuction || listing.isBuyOrder() || listing.isAdminListing()) { sp.displayClientMessage(Component.literal("§cAuction not found."), true); break; }
                    if (listing.expiresAt <= System.currentTimeMillis()) { sp.displayClientMessage(Component.literal("§cAuction has ended."), true); break; }
                    if (listing.sellerUUID.equals(sp.getUUID())) { sp.displayClientMessage(Component.literal("§cYou can't bid on your own auction."), true); break; }
                    if (pkt.price() <= 0) { sp.displayClientMessage(Component.literal("§cBid must be more than 0."), true); break; }
                    String bidName = listing.item.getHoverName().getString();
                    UUID previousBidder = listing.highestBidder;
                    long previousBid = listing.highestBid;
                    if (!eco.deductWallet(sp.getUUID(), pkt.price(), "Bid on " + bidName + " (held)")) { sp.displayClientMessage(Component.literal("§cInsufficient funds."), true); break; }
                    // Refund previous bidder
                    if (!listing.placeBid(sp.getUUID(), pkt.price())) {
                        eco.addWallet(sp.getUUID(), pkt.price(), "Bid rejected on " + bidName + " (refund)");
                        sp.displayClientMessage(Component.literal("§cYour bid must beat the current bid."), true);
                        break;
                    }
                    market.logMarket(sp.getUUID(), -pkt.price(), "Bid on: " + bidName, listing.item);
                    if (previousBidder != null) {
                        eco.addWallet(previousBidder, previousBid, "Outbid on " + bidName + " (refund)");
                        market.logMarket(previousBidder, previousBid, "Outbid on: " + bidName + " (refund)", listing.item);
                        market.notifyPlayer(server, previousBidder, "§e[Market] You were outbid on §f" + bidName
                                + "§e. Your §a" + Currency.format(previousBid) + "§e was refunded.");
                    }
                    market.setDirty();
                    sp.displayClientMessage(Component.literal("§aBid placed!"), true);
                }
                case CANCEL -> {
                    var listing = market.getListing(pkt.listingId());
                    if (listing == null || !listing.sellerUUID.equals(sp.getUUID())) { sp.displayClientMessage(Component.literal("§cCannot cancel that listing."), true); break; }
                    // Refund top bidder on auction cancel
                    String cancelledName = listing.item.getHoverName().getString();
                    if (listing.isAuction && listing.highestBidder != null) {
                        eco.addWallet(listing.highestBidder, listing.highestBid, "Auction cancelled: " + cancelledName + " (refund)");
                        market.logMarket(listing.highestBidder, listing.highestBid, "Auction cancelled: " + cancelledName + " (refund)", listing.item);
                    }
                    if (listing.kind == MarketListing.ListingKind.PLAYER_BUY_ORDER) {
                        if (listing.escrow > 0) eco.addWallet(sp.getUUID(), listing.escrow, "Buy order cancelled (refund)");
                        market.logMarket(sp.getUUID(), listing.escrow, "Cancelled buy order: " + listing.orderFilled + "/"
                                + listing.orderQuantity + " " + cancelledName + " (refund)", listing.item);
                        sp.displayClientMessage(Component.literal("§aBuy order cancelled. Refunded §e"
                                + Currency.format(listing.escrow) + "§a."), false);
                    } else if (listing.kind == MarketListing.ListingKind.PLAYER_SELL) {
                        market.deliverItem(server, sp.getUUID(), listing.item);
                        market.logMarket(sp.getUUID(), 0, "Cancelled listing: " + cancelledName + " (returned)", listing.item);
                    }
                    market.removeListing(pkt.listingId());
                    sp.displayClientMessage(Component.literal("§aListing cancelled."), true);
                }
                case CLAIM_SOLD -> {
                    var sale = market.getSoldListingsForPlayer(sp.getUUID()).stream()
                            .filter(s -> s.saleId.equals(pkt.listingId())).findFirst().orElse(null);
                    if (sale == null) { sp.displayClientMessage(Component.literal("§cSale not found or already claimed."), true); break; }
                    eco.addWallet(sp.getUUID(), sale.proceeds, "Market sale: " + sale.itemName);
                    market.claimSoldListing(pkt.listingId());
                    sp.displayClientMessage(Component.literal("§aClaimed §e" + Currency.format(sale.proceeds)
                            + " §afrom the sale of §f" + sale.itemName + "§a!"), true);
                    // Sync updated sold listings back
                    PacketDistributor.sendToPlayer(sp, new SyncSoldListingsPacket(
                            market.getSoldListingsForPlayer(sp.getUUID())));
                }
                case FULFILL_BUY_ORDER -> {
                    var listing = market.getListing(pkt.listingId());
                    if (listing == null || !listing.isBuyOrder()) { sp.displayClientMessage(Component.literal("§cBuy order not found."), true); break; }
                    if (listing.sellerUUID.equals(sp.getUUID())) { sp.displayClientMessage(Component.literal("§cYou cannot fill your own buy order."), true); break; }
                    String itemName = listing.item.getHoverName().getString();
                    int remaining = listing.remainingQuantity();
                    int have = countMatching(sp, listing.item);
                    if (have <= 0) { sp.displayClientMessage(Component.literal("§cYou don't have any §f" + itemName + "§c."), true); break; }
                    if (remaining <= 0) { sp.displayClientMessage(Component.literal("§cThat order is already full."), true); break; }
                    int amount = Math.min(Math.min(Math.max(1, pkt.itemCount()), remaining), have);

                    long payment;
                    try {
                        payment = Math.multiplyExact(listing.price, (long) amount);
                    } catch (ArithmeticException overflow) {
                        payment = Long.MAX_VALUE;
                    }
                    boolean playerOrder = listing.kind == MarketListing.ListingKind.PLAYER_BUY_ORDER;
                    if (playerOrder) payment = Math.min(payment, listing.escrow);

                    removeMatching(sp, listing.item, amount);
                    String sellerName = sp.getGameProfile().getName();
                    // Selling into a player's order is taxed like a Buy Now sale; server orders pay out in full.
                    long received = payment;
                    if (playerOrder) {
                        long tax = payment * Config.MARKET_TAX_BIN_PERCENT.get() / 100;
                        Treasury.record(server, Treasury.Source.MARKET_TAX_BUY_ORDER, tax, sellerName,
                                amount + "× " + itemName + " sold into an order for " + Currency.format(payment).replaceAll("§.", ""));
                        received = MarketManager.afterVassalTax(server, sp.getUUID(), payment - tax);
                    }
                    eco.addWallet(sp.getUUID(), received, "Sold " + amount + "× " + itemName + " to a buy order");
                    listing.orderFilled += amount;
                    market.logMarket(sp.getUUID(), received, "Sold to buy order: " + amount + "× " + itemName
                            + (playerOrder ? " (after tax)" : ""), listing.item);
                    if (playerOrder) market.recordSale(listing.item, listing.price, amount, PricePoint.BUY_ORDER);
                    else Treasury.record(server, Treasury.Source.SERVER_BUY_ORDERS, payment, sellerName, amount + "× " + itemName);

                    if (playerOrder) {
                        listing.escrow -= payment;
                        market.queueDelivery(listing.sellerUUID, listing.item.copyWithCount(amount),
                                "Buy order · from " + sellerName);
                        market.logMarket(listing.sellerUUID, 0, "Order filled by " + sellerName + ": " + amount + "× "
                                + itemName + " (" + listing.orderFilled + "/" + listing.orderQuantity + ", paid from held money)",
                                listing.item);
                        market.notifyPlayer(server, listing.sellerUUID, "§a[Market] §e" + sellerName + " §afilled §f"
                                + amount + "§a of your order for §f" + listing.orderQuantity + " " + itemName
                                + " §7(" + listing.orderFilled + "/" + listing.orderQuantity + ")§a for §e"
                                + Currency.format(payment) + "§a. Claim the items in My Market.");
                        if (listing.remainingQuantity() <= 0) {
                            // Complete: return any rounding leftover and close the order.
                            if (listing.escrow > 0) eco.addWallet(listing.sellerUUID, listing.escrow, "Buy order complete (leftover refund)");
                            market.notifyPlayer(server, listing.sellerUUID, "§a[Market] Your buy order for §f"
                                    + listing.orderQuantity + " " + itemName + " §ais complete!");
                            market.removeListing(listing.listingId);
                        }
                    }
                    market.setDirty();
                    sp.displayClientMessage(Component.literal("§aSold §f" + amount + " " + itemName + " §afor §e"
                            + Currency.format(received) + (received != payment ? " §7(after tax)" : "") + "§a."), true);
                }
                case CLAIM_DELIVERY -> {
                    if (!market.claimDelivery(sp, pkt.listingId())) {
                        sp.displayClientMessage(Component.literal("§cDelivery not found or already claimed."), true);
                        break;
                    }
                    sp.displayClientMessage(Component.literal("§aMarket delivery claimed."), true);
                }
                case REFRESH -> { /* no-op: re-sync below handles it */ }
            }

            // Push any inventory slot changes (item added/removed) to the client
            com.admin82.factions.network.InventorySync.push(sp);
            if (sp.containerMenu != sp.inventoryMenu) sp.containerMenu.broadcastChanges();

            // Re-sync the market: a refresh only goes back to whoever asked, anything else to every open market.
            var allListings = market.getListings().stream().toList();
            var viewers = pkt.action() == Action.REFRESH ? List.of(sp) : server.getPlayerList().getPlayers();
            for (ServerPlayer online : viewers) {
                if (!(online.containerMenu instanceof MarketMenu)) continue;
                int onlineMaxSlots = MarketManager.listingLimit();
                PacketDistributor.sendToPlayer(online, new SyncMarketPacket(
                        allListings, eco.getWallet(online.getUUID()),
                        market.countPlayerListings(online.getUUID()), onlineMaxSlots));
                // Also push this player's sold listings
                PacketDistributor.sendToPlayer(online, new SyncSoldListingsPacket(
                        market.getSoldListingsForPlayer(online.getUUID())));
                PacketDistributor.sendToPlayer(online, new SyncMarketDeliveriesPacket(
                    market.getDeliveriesForPlayer(online.getUUID())));
                LiveSync.noteMarketSent(online, market.getVersion(), eco.getWallet(online.getUUID()));
            }
        });
    }

    private static MarketListing.ListingKind listingKind(int ordinal) {
        MarketListing.ListingKind[] values = MarketListing.ListingKind.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MarketListing.ListingKind.PLAYER_SELL;
    }

    private static ItemStack resolveRequestedItem(String itemId, int count) {
        if (itemId == null || itemId.isBlank()) return ItemStack.EMPTY;
        try {
            Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(itemId)).orElse(null);
            if (item == null) return ItemStack.EMPTY;
            return new ItemStack(item, Math.max(1, Math.min(64, count)));
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }

    /** Total count of items in the player's inventory matching the order's template. */
    private static int countMatching(ServerPlayer player, ItemStack template) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template)) total += stack.getCount();
        }
        return total;
    }

    /** Removes {@code amount} matching items, spread over as many stacks as needed. */
    private static void removeMatching(ServerPlayer player, ItemStack template, int amount) {
        int left = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && left > 0; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, template)) continue;
            int take = Math.min(left, stack.getCount());
            stack.shrink(take);
            if (stack.isEmpty()) player.getInventory().setItem(i, ItemStack.EMPTY);
            left -= take;
        }
    }
}
