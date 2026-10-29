package com.admin82.factions.menu;

import com.admin82.factions.entity.monument.AiCombatProfile;
import com.admin82.factions.entity.monument.AiCombatProfileData;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.network.packet.AiCombatProfilePacket;
import com.admin82.factions.network.packet.SyncAiCombatProfilePacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.IObserver;
import org.appliedenergistics.yoga.YogaFlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import org.appliedenergistics.yoga.YogaAlign;

import org.appliedenergistics.yoga.YogaFlexDirection;

public class AiCombatProfileMenu extends AbstractContainerMenu {
    private static final int PROFILE_PAYLOAD_VERSION = 1;
    private final AiCombatProfile[][] profiles = new AiCombatProfile[RoguePmcRole.values().length][AiCombatProfileData.DIFFICULTY_COUNT];
    private int selectedRole;
    private int selectedDifficulty = 3;

    public AiCombatProfileMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        super(ModMenuTypes.AI_COMBAT_PROFILES.get(), id);
        int version = buffer.readVarInt();
        if (version != PROFILE_PAYLOAD_VERSION) {
            throw new IllegalArgumentException("Unsupported AI profile payload version " + version);
        }
        for (int role = 0; role < profiles.length; role++) {
            for (int difficulty = 0; difficulty < AiCombatProfileData.DIFFICULTY_COUNT; difficulty++) {
                profiles[role][difficulty] = readProfile(buffer).validated();
            }
        }
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu holder) {
            holder.setModularUI(buildUi(inventory.player));
        }
    }

    public AiCombatProfileMenu(int id, Inventory inventory, Player player) {
        super(ModMenuTypes.AI_COMBAT_PROFILES.get(), id);
        AiCombatProfileData data = AiCombatProfileData.get(((net.minecraft.server.level.ServerPlayer) player).server);
        for (RoguePmcRole role : RoguePmcRole.values()) {
            for (int difficulty = 1; difficulty <= AiCombatProfileData.DIFFICULTY_COUNT; difficulty++) {
                profiles[role.ordinal()][difficulty - 1] = data.getProfile(role, difficulty);
            }
        }
    }

    public static void writeOpenData(FriendlyByteBuf buffer, MinecraftServer server) {
        buffer.writeVarInt(PROFILE_PAYLOAD_VERSION);
        AiCombatProfileData data = AiCombatProfileData.get(server);
        for (RoguePmcRole role : RoguePmcRole.values()) {
            for (int difficulty = 1; difficulty <= AiCombatProfileData.DIFFICULTY_COUNT; difficulty++) {
                AiCombatProfile profile = data.getProfile(role, difficulty);
                writeProfile(buffer, profile);
            }
        }
    }

    private static void writeProfile(FriendlyByteBuf buffer, AiCombatProfile profile) {
        buffer.writeDouble(profile.maximumConeDegrees());
        buffer.writeVarInt(profile.lockOnTicks());
        buffer.writeDouble(profile.combatRange());
        buffer.writeDouble(profile.minimumRange());
        buffer.writeDouble(profile.preferredRange());
        buffer.writeDouble(profile.rushRange());
        buffer.writeDouble(profile.coverRange());
        buffer.writeDouble(profile.fireThreshold());
        buffer.writeVarInt(profile.shotCooldownTicks());
    }

    private static AiCombatProfile readProfile(FriendlyByteBuf buffer) {
        return new AiCombatProfile(buffer.readDouble(), buffer.readVarInt(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readVarInt());
    }

    private ModularUI buildUi(Player player) {
        UIElement frame = new UIElement();
        frame.layout(l -> l.width(700).height(430).paddingAll(2));
        frame.addClass("preview_bg");
        UIElement root = new UIElement();
        root.layout(l -> l.width(696).height(426).paddingAll(8).gapAll(6).flexDirection(YogaFlexDirection.COLUMN));
        root.addClass("panel_bg");
        root.addChildren(new Label().setText("§6§lAI Combat Profiles"),
                new Label().setText("§7Edit role behavior independently for every difficulty."));

        UIElement roleTabs = new UIElement();
        roleTabs.layout(l -> l.height(23).flexDirection(YogaFlexDirection.ROW).gapAll(3));
        for (RoguePmcRole role : RoguePmcRole.values()) {
            int index = role.ordinal();
            roleTabs.addChildren(new Button().setText((selectedRole == index ? "§a> " : "§7")
                    + role.name().replace("_GUNNER", "").replace('_', ' '))
                    .setOnClick(event -> { selectedRole = index; rebuild(player); })
                    .layout(l -> l.flex(1).height(21)));
        }

        UIElement difficultyTabs = new UIElement();
        difficultyTabs.layout(l -> l.height(23).flexDirection(YogaFlexDirection.ROW).gapAll(3));
        for (int difficulty = 1; difficulty <= AiCombatProfileData.DIFFICULTY_COUNT; difficulty++) {
            int selected = difficulty;
            difficultyTabs.addChildren(new Button().setText((selectedDifficulty == selected ? "§e" : "§7")
                    + AiCombatProfileData.DIFFICULTY_NAMES[difficulty - 1])
                    .setOnClick(event -> { selectedDifficulty = selected; rebuild(player); })
                    .layout(l -> l.flex(1).height(21)));
        }

        UIElement content = buildProfileFields(player);
        root.addChildren(roleTabs, difficultyTabs, content);
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    private UIElement buildProfileFields(Player player) {
        AiCombatProfile profile = profiles[selectedRole][selectedDifficulty - 1];
        double[] cone = {profile.maximumConeDegrees()};
        int[] lock = {profile.lockOnTicks()};
        double[] combat = {profile.combatRange()};
        double[] minimum = {profile.minimumRange()};
        double[] preferred = {profile.preferredRange()};
        double[] rush = {profile.rushRange()};
        double[] cover = {profile.coverRange()};
        double[] threshold = {profile.fireThreshold()};
        int[] cooldown = {profile.shotCooldownTicks()};
        UIElement content = new UIElement();
        content.layout(l -> l.flex(1).flexDirection(YogaFlexDirection.COLUMN).gapAll(5));
        content.addChildren(fieldRow("Aim cone (degrees)", field(format(cone[0]), value -> cone[0] = parseDouble(value, cone[0]))),
                fieldRow("Lock-on time (ticks)", field(Integer.toString(lock[0]), value -> lock[0] = parseInt(value, lock[0]))),
                fieldRow("Combat range", field(format(combat[0]), value -> combat[0] = parseDouble(value, combat[0]))),
                fieldRow("Minimum range", field(format(minimum[0]), value -> minimum[0] = parseDouble(value, minimum[0]))),
                fieldRow("Preferred range", field(format(preferred[0]), value -> preferred[0] = parseDouble(value, preferred[0]))),
                fieldRow("Range to rush", field(format(rush[0]), value -> rush[0] = parseDouble(value, rush[0]))),
                fieldRow("Range to get cover", field(format(cover[0]), value -> cover[0] = parseDouble(value, cover[0]))),
                fieldRow("Fire threshold", field(format(threshold[0]), value -> threshold[0] = parseDouble(value, threshold[0]))),
                fieldRow("Shot cooldown (ticks)", field(Integer.toString(cooldown[0]), value -> cooldown[0] = parseInt(value, cooldown[0]))),
                new Button().setText("§aSave Profile").setOnClick(event -> {
                    AiCombatProfile updated = new AiCombatProfile(cone[0], lock[0], combat[0], minimum[0], preferred[0],
                            rush[0], cover[0], threshold[0], cooldown[0]).validated();
                    profiles[selectedRole][selectedDifficulty - 1] = updated;
                    PacketDistributor.sendToServer(new AiCombatProfilePacket(selectedRole, selectedDifficulty, updated));
                }).layout(l -> l.width(110).height(21)));
        return content;
    }

    private UIElement fieldRow(String label, TextField field) {
        UIElement row = new UIElement();
        row.layout(l -> l.height(22).flexDirection(YogaFlexDirection.ROW).alignItems(YogaAlign.CENTER).gapAll(5));
        row.addChildren(new Label().setText("§7" + label).layout(l -> l.width(180)), field);
        return row;
    }

    private TextField field(String value, java.util.function.Consumer<String> observer) {
        TextField field = new TextField();
        field.setValue(value);
        field.bindObserver((IObserver<String>) observer::accept);
        field.layout(l -> l.width(90).height(19));
        return field;
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static double parseDouble(String value, double fallback) {
        try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private void rebuild(Player player) {
        if (player instanceof net.minecraft.client.player.LocalPlayer
                && Minecraft.getInstance().screen instanceof com.admin82.factions.screen.AiCombatProfileScreen screen) {
            screen.refreshContent();
        }
    }

    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu holder) holder.setModularUI(buildUi(player));
    }

    public void updateProfile(int role, int difficulty, AiCombatProfile profile) {
        if (role >= 0 && role < profiles.length && difficulty >= 1 && difficulty <= AiCombatProfileData.DIFFICULTY_COUNT) {
            profiles[role][difficulty - 1] = profile.validated();
        }
    }

    @Override public boolean stillValid(Player player) { return player.hasPermissions(2); }
    @Override public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int slot) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
}