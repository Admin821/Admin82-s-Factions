package com.admin82.factions.network.packet;

import com.admin82.factions.AdminsFactions;
import com.admin82.factions.Config;
import com.admin82.factions.economy.BountyManager;
import com.admin82.factions.economy.Currency;
import com.admin82.factions.economy.EconomyManager;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.economy.Treasury;
import com.admin82.factions.faction.AllianceManager;
import com.admin82.factions.faction.AllianceRules;
import com.admin82.factions.faction.FactionManager;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.nio.file.Files;
import java.util.UUID;

/** Client → server: place, cancel or refresh bounties on the market's bounty board. */
public record BountyActionPacket(Action action, String targetName, long reward, int days, UUID bountyId, String shownAs)
        implements CustomPacketPayload {

    public enum Action { PLACE, CANCEL, REFRESH }

    private static final UUID NONE = new UUID(0, 0);

    /** Longest name a placer can show instead of their own. */
    public static final int MAX_SHOWN_NAME = 24;
    public static final String ANONYMOUS = "Anonymous";

    /** @param shownAs name shown as the placer; blank means "Anonymous" */
    public static BountyActionPacket place(String target, long reward, int days, String shownAs) {
        return new BountyActionPacket(Action.PLACE, target, reward, days, NONE, shownAs);
    }
    public static BountyActionPacket cancel(UUID id) {
        return new BountyActionPacket(Action.CANCEL, "", 0, 0, id, "");
    }
    public static BountyActionPacket refresh() {
        return new BountyActionPacket(Action.REFRESH, "", 0, 0, NONE, "");
    }

    /** Strips colour codes and odd characters; blank becomes "Anonymous". */
    public static String cleanShownName(String raw) {
        String name = raw == null ? "" : raw.replaceAll("§.", "").replaceAll("[^\\p{L}\\p{N} _.'!?-]", "").trim();
        if (name.length() > MAX_SHOWN_NAME) name = name.substring(0, MAX_SHOWN_NAME).trim();
        return name.isEmpty() ? ANONYMOUS : name;
    }

    public static final Type<BountyActionPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AdminsFactions.MODID, "bounty_action"));

    public static final StreamCodec<FriendlyByteBuf, BountyActionPacket> STREAM_CODEC = StreamCodec.of(
            (buf, pkt) -> {
                buf.writeVarInt(pkt.action.ordinal());
                buf.writeUtf(pkt.targetName, 64);
                buf.writeLong(pkt.reward);
                buf.writeVarInt(pkt.days);
                buf.writeUUID(pkt.bountyId);
                buf.writeUtf(pkt.shownAs, 64);
            },
            buf -> {
                int ordinal = buf.readVarInt();
                Action action = ordinal >= 0 && ordinal < Action.values().length ? Action.values()[ordinal] : Action.REFRESH;
                return new BountyActionPacket(action, buf.readUtf(64), buf.readLong(), buf.readVarInt(), buf.readUUID(), buf.readUtf(64));
            });

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(BountyActionPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            switch (pkt.action) {
                case PLACE -> place(sp, pkt.targetName.trim(), pkt.reward, pkt.days, cleanShownName(pkt.shownAs));
                case CANCEL -> cancel(sp, pkt.bountyId);
                case REFRESH -> { }
            }
            PacketDistributor.sendToPlayer(sp, SyncBountiesPacket.forViewer(sp.server, sp));
        });
    }

    /** The name cache also knows people who never joined (whitelist, ops, skins); only count players with saved data. */
    private static boolean hasPlayedHere(MinecraftServer server, UUID id) {
        return Files.exists(server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve(id + ".dat"));
    }

    private static void place(ServerPlayer sp, String targetName, long reward, int days, String shownAs) {
        MinecraftServer server = sp.server;
        if (targetName.isEmpty()) { fail(sp, "Choose a player to put a bounty on."); return; }

        // Resolve the target: online first, then anyone who has played on the server.
        ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
        GameProfile profile = online != null ? online.getGameProfile()
                : server.getProfileCache() != null ? server.getProfileCache().get(targetName).orElse(null) : null;
        if (profile == null || profile.getId() == null || online == null && !hasPlayedHere(server, profile.getId())) {
            fail(sp, "No player called " + targetName + " has played here.");
            return;
        }
        if (profile.getId().equals(sp.getUUID())) { fail(sp, "You can't put a bounty on yourself."); return; }
        var fmgr = FactionManager.get(server);
        UUID myFaction = fmgr.getPlayerFactionId(sp.getUUID());
        if (myFaction != null && myFaction.equals(fmgr.getPlayerFactionId(profile.getId()))) {
            fail(sp, profile.getName() + " is on your team. You can't put a bounty on a teammate.");
            return;
        }
        String leftBlock = AllianceRules.leftRecentlyReason(server, sp.getUUID(), fmgr.getPlayerFactionId(profile.getId()));
        if (leftBlock != null) {
            fail(sp, leftBlock);
            return;
        }
        if (AllianceRules.playersProtected(server, sp.getUUID(), profile.getId())) {
            var am = AllianceManager.get(server);
            UUID theirFaction = fmgr.getPlayerFactionId(profile.getId());
            fail(sp, am.areAllied(myFaction, theirFaction)
                    ? profile.getName() + "'s faction is allied with yours. You can't put a bounty on an ally."
                    : "Your alliance with " + profile.getName() + "'s faction ended recently. You can't put a bounty on them for another "
                      + AllianceManager.formatDuration(am.cooldownRemainingMs(myFaction, theirFaction)) + ".");
            return;
        }

        long min = Config.BOUNTY_MIN_COPPER.get();
        if (reward < min) { fail(sp, "The smallest bounty is " + plain(min) + "."); return; }
        int maxDays = Config.BOUNTY_MAX_DAYS.get();
        int duration = Math.max(1, Math.min(maxDays, days));
        BountyManager bounties = BountyManager.get(server);
        int maxActive = Config.BOUNTY_MAX_ACTIVE_PER_PLAYER.get();
        if (bounties.countByPlacer(sp.getUUID()) >= maxActive) {
            fail(sp, "You already have " + maxActive + " bounties up. Wait for one to finish or cancel one.");
            return;
        }
        String name = profile.getName();
        // The tax is taken now and never refunded: only the rest goes into the bounty.
        long tax = taxOn(reward);
        long pool = reward - tax;
        if (!EconomyManager.get(server).deductWallet(sp.getUUID(), reward, "Placed a bounty on " + name
                + (tax > 0 ? " (incl. " + plain(tax) + " tax)" : ""))) {
            fail(sp, "Your wallet needs " + plain(reward) + " to place that bounty.");
            return;
        }
        Treasury.record(server, Treasury.Source.BOUNTY_TAX, tax,
                sp.getGameProfile().getName(), "Bounty on " + name);
        long now = System.currentTimeMillis();
        bounties.add(new BountyManager.Bounty(UUID.randomUUID(), profile.getId(), name, sp.getUUID(),
                shownAs, pool, now, now + duration * 86_400_000L));
        MarketManager.get(server).logMarket(sp.getUUID(), -reward, "Placed a bounty on " + name
                + (tax > 0 ? " (" + plain(pool) + " reward + " + plain(tax) + " tax)" : ""), new ItemStack(Items.SKELETON_SKULL));

        long total = bounties.getOnTarget(profile.getId()).stream().mapToLong(b -> b.reward).sum();
        sp.displayClientMessage(Component.literal("§6[Bounty] §7You put §e" + Currency.format(pool) + " §7on §c" + name
                + "§7 for " + duration + " day" + (duration == 1 ? "" : "s") + ", shown as §f" + shownAs + "§7."
                + (tax > 0 ? " §8(" + plain(tax) + " tax kept by the market)" : "")), false);
        MarketManager.get(server).notifyPlayer(server, profile.getId(), "§6[Bounty] §f" + shownAs
                + " §7put a bounty of §e" + Currency.format(pool) + " §7on your head. Total on you: §e" + Currency.format(total) + "§7.");
        server.getPlayerList().broadcastSystemMessage(Component.literal("§6[Bounty] §7A bounty of §e" + Currency.format(pool)
                + " §7was placed on §c" + name + "§7. Total: §e" + Currency.format(total) + "§7."), false);
    }

    /** Tax kept when a bounty is placed (rounded down). */
    public static long taxOn(long reward) {
        // Split so huge rewards can't overflow: (100q + r) * p / 100 == q * p + r * p / 100.
        long percent = Config.BOUNTY_TAX_PERCENT.get();
        return reward / 100L * percent + reward % 100L * percent / 100L;
    }

    private static void cancel(ServerPlayer sp, UUID id) {
        BountyManager bounties = BountyManager.get(sp.server);
        BountyManager.Bounty b = bounties.get(id);
        if (b == null) { fail(sp, "That bounty is no longer on the board."); return; }
        if (!b.placerId.equals(sp.getUUID()) && !sp.hasPermissions(2)) { fail(sp, "Only the player who placed it can cancel it."); return; }
        bounties.remove(id);
        EconomyManager.get(sp.server).addWallet(b.placerId, b.reward, "Cancelled bounty on " + b.targetName + " (refund)");
        MarketManager.get(sp.server).logMarket(b.placerId, b.reward, "Cancelled bounty on " + b.targetName, new ItemStack(Items.SKELETON_SKULL));
        sp.displayClientMessage(Component.literal("§6[Bounty] §7Bounty on §c" + b.targetName + " §7cancelled. §e"
                + Currency.format(b.reward) + " §7was refunded" + (b.placerId.equals(sp.getUUID()) ? " to your wallet." : " to its owner.")
                + (Config.BOUNTY_TAX_PERCENT.get() > 0 ? " §8(the placement tax is not refunded)" : "")), false);
    }

    private static String plain(long copper) {
        return Currency.format(copper).replaceAll("§.", "");
    }

    private static void fail(ServerPlayer sp, String message) {
        sp.displayClientMessage(Component.literal("§c" + message), false);
    }
}
