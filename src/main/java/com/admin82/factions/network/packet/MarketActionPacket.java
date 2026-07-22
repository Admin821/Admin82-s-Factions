package com.admin82.factions.network.packet;

import com.admin82.factions.economy.*;
import com.admin82.factions.faction.FactionManager;
import com.admin82.factions.menu.MarketMenu;
import com.admin82.factions.Config;
import com.admin82.factions.war.VassalManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
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

    public enum Action { CREATE, BUY, BID, CANCEL, REFRESH, CLAIM_SOLD, FULFILL_BUY_ORDER }

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

            switch (pkt.action()) {
                case CREATE -> {
                    MarketListing.ListingKind kind = listingKind(pkt.listingKind());
                    boolean buyOrder = kind == MarketListing.ListingKind.PLAYER_BUY_ORDER || kind == MarketListing.ListingKind.ADMIN_BUY_ORDER;
                    boolean adminListing = kind == MarketListing.ListingKind.ADMIN_SELL || kind == MarketListing.ListingKind.ADMIN_BUY_ORDER;
                    if (adminListing && !sp.hasPermissions(2)) { sp.displayClientMessage(Component.literal("§cOperator permission required."), true); break; }
                    int slot = pkt.inventorySlot();
                    ItemStack item;
                    if (buyOrder) {
                        item = resolveRequestedItem(pkt.itemId(), pkt.itemCount());
                        if (item.isEmpty()) { sp.displayClientMessage(Component.literal("§cChoose a valid item for the buy order."), true); break; }
                    } else {
                        if (slot < 0 || slot >= sp.getInventory().getContainerSize()) break;
                        item = sp.getInventory().getItem(slot);
                        if (item.isEmpty()) { sp.displayClientMessage(net.minecraft.network.chat.Component.literal("§cNo item in that slot."), true); break; }
                    }

                    // Validate price
                    if (pkt.price() <= 0) { sp.displayClientMessage(net.minecraft.network.chat.Component.literal("§cPrice must be > 0."), true); break; }
                    if (buyOrder && kind == MarketListing.ListingKind.PLAYER_BUY_ORDER && !eco.deductWallet(sp.getUUID(), pkt.price())) {
                        sp.displayClientMessage(Component.literal("§cInsufficient funds for buy order escrow."), true);
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
                    market.addListing(listing);
                    if (!buyOrder && kind != MarketListing.ListingKind.ADMIN_SELL) {
                        sp.getInventory().setItem(slot, ItemStack.EMPTY);
                    }
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal(buyOrder ? "§aBuy order created!" : "§aListing created!"), true);
                }
                case BUY -> {
                    var listing = market.getListing(pkt.listingId());
                    if (listing == null || listing.isAuction || listing.isBuyOrder()) { sp.displayClientMessage(Component.literal("§cListing not found."), true); break; }
                    if (listing.sellerUUID.equals(sp.getUUID())) { sp.displayClientMessage(Component.literal("§cYou cannot buy your own listing."), true); break; }
                    if (!eco.deductWallet(sp.getUUID(), listing.price)) { sp.displayClientMessage(Component.literal("§cInsufficient funds."), true); break; }

                    if (listing.kind == MarketListing.ListingKind.ADMIN_SELL) {
                        if (!sp.getInventory().add(listing.item.copy())) sp.drop(listing.item.copy(), false);
                        sp.displayClientMessage(Component.literal("§aPurchased from server shop!"), true);
                        break;
                    }

                    long proceeds = listing.netProceeds(listing.price);
                    // Vassal tax
                    FactionManager fmgr = FactionManager.get(server);
                    var sellerFaction = fmgr.getFactionForPlayer(listing.sellerUUID);
                    VassalManager vmgr = VassalManager.get(server);
                    if (sellerFaction != null && vmgr.isVassal(sellerFaction.getId())) {
                        long tax = Math.max(1, proceeds * Config.VASSAL_TAX_PERCENT.get() / 100);
                        vmgr.accumulateTax(sellerFaction.getId(), tax);
                        proceeds -= tax;
                    }

                    // Give item to buyer
                    if (!sp.getInventory().add(listing.item.copy())) sp.drop(listing.item.copy(), false);

                    // Create a SoldListing so the seller must claim proceeds
                    String buyerName = sp.getGameProfile().getName();
                    var sale = new com.admin82.factions.economy.SoldListing();
                    sale.saleId     = java.util.UUID.randomUUID();
                    sale.sellerUUID = listing.sellerUUID;
                    sale.item       = listing.item.copy();
                    sale.itemName   = listing.item.getHoverName().getString();
                    sale.buyerName  = buyerName;
                    sale.proceeds   = proceeds;
                    sale.soldAt     = System.currentTimeMillis();
                    market.addSoldListing(sale);
                    market.removeListing(listing.listingId);

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
                                + " §eGo to §eManage Listings§e to claim your earnings!"), false);
                        PacketDistributor.sendToPlayer(seller, new SyncSoldListingsPacket(
                                market.getSoldListingsForPlayer(listing.sellerUUID)));
                    }
                }
                case BID -> {
                    var listing = market.getListing(pkt.listingId());
                    if (listing == null || !listing.isAuction || listing.isBuyOrder() || listing.isAdminListing()) { sp.displayClientMessage(net.minecraft.network.chat.Component.literal("§cAuction not found."), true); break; }
                    if (listing.expiresAt <= System.currentTimeMillis()) { sp.displayClientMessage(net.minecraft.network.chat.Component.literal("§cAuction has ended."), true); break; }
                    if (!eco.deductWallet(sp.getUUID(), pkt.price())) { sp.displayClientMessage(net.minecraft.network.chat.Component.literal("§cInsufficient funds."), true); break; }
                    // Refund previous bidder
                    if (listing.highestBidder != null) eco.addWallet(listing.highestBidder, listing.highestBid);
                    if (!listing.placeBid(sp.getUUID(), pkt.price())) { eco.addWallet(sp.getUUID(), pkt.price()); break; }
                    market.setDirty();
                    sp.displayClientMessage(net.minecraft.network.chat.Component.literal("§aBid placed!"), true);
                }
                case CANCEL -> {
                    var listing = market.getListing(pkt.listingId());
                    if (listing == null || !listing.sellerUUID.equals(sp.getUUID())) { sp.displayClientMessage(Component.literal("§cCannot cancel that listing."), true); break; }
                    // Refund top bidder on auction cancel
                    if (listing.isAuction && listing.highestBidder != null) eco.addWallet(listing.highestBidder, listing.highestBid);
                    if (listing.kind == MarketListing.ListingKind.PLAYER_BUY_ORDER) {
                        eco.addWallet(sp.getUUID(), listing.price);
                    } else if (listing.kind == MarketListing.ListingKind.PLAYER_SELL) {
                        if (!sp.getInventory().add(listing.item.copy())) sp.drop(listing.item.copy(), false);
                    }
                    market.removeListing(pkt.listingId());
                    sp.displayClientMessage(Component.literal("§aListing cancelled."), true);
                }
                case CLAIM_SOLD -> {
                    var sale = market.getSoldListingsForPlayer(sp.getUUID()).stream()
                            .filter(s -> s.saleId.equals(pkt.listingId())).findFirst().orElse(null);
                    if (sale == null) { sp.displayClientMessage(Component.literal("§cSale not found or already claimed."), true); break; }
                    eco.addWallet(sp.getUUID(), sale.proceeds);
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
                    int matchingSlot = findMatchingStack(sp, listing.item);
                    if (matchingSlot < 0) { sp.displayClientMessage(Component.literal("§cYou do not have the requested item and count."), true); break; }

                    ItemStack sellerStack = sp.getInventory().getItem(matchingSlot);
                    ItemStack delivered = sellerStack.copyWithCount(listing.item.getCount());
                    sellerStack.shrink(listing.item.getCount());
                    if (sellerStack.isEmpty()) sp.getInventory().setItem(matchingSlot, ItemStack.EMPTY);

                    eco.addWallet(sp.getUUID(), listing.price);
                    if (listing.kind == MarketListing.ListingKind.PLAYER_BUY_ORDER) {
                        ServerPlayer buyer = server.getPlayerList().getPlayer(listing.sellerUUID);
                        if (buyer != null) {
                            if (!buyer.getInventory().add(delivered.copy())) buyer.drop(delivered.copy(), false);
                            buyer.displayClientMessage(Component.literal("§a[Market] Your buy order was filled for §f"
                                    + listing.item.getHoverName().getString() + "§a."), false);
                        }
                        market.removeListing(listing.listingId);
                    }
                    sp.displayClientMessage(Component.literal("§aBuy order filled for §e" + Currency.format(listing.price) + "§a."), true);
                }
                case REFRESH -> { /* no-op: re-sync below handles it */ }
            }

            // Push any inventory slot changes (item added/removed) to the client
            sp.inventoryMenu.broadcastChanges();

            // Re-sync market to every player who currently has a market screen open
            var allListings = market.getListings().stream().toList();
            for (ServerPlayer online : server.getPlayerList().getPlayers()) {
                if (!(online.containerMenu instanceof MarketMenu)) continue;
                var onlineFaction = FactionManager.get(server.overworld()).getFactionForPlayer(online.getUUID());
                int onlineMaxSlots = onlineFaction != null ? onlineFaction.getMembers().size() : 1;
                PacketDistributor.sendToPlayer(online, new SyncMarketPacket(
                        allListings, eco.getWallet(online.getUUID()),
                        market.countPlayerListings(online.getUUID()), onlineMaxSlots));
                // Also push this player's sold listings
                PacketDistributor.sendToPlayer(online, new SyncSoldListingsPacket(
                        market.getSoldListingsForPlayer(online.getUUID())));
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

    private static int findMatchingStack(ServerPlayer player, ItemStack requested) {
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getCount() >= requested.getCount() && ItemStack.isSameItemSameComponents(stack, requested)) return i;
        }
        return -1;
    }
}
