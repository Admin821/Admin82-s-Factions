package com.admin82.factions.menu;

import com.admin82.factions.entity.monument.AiBehaviorData;
import com.admin82.factions.entity.monument.AiBehaviorProfile;
import com.admin82.factions.entity.monument.AiTuning;
import com.admin82.factions.entity.monument.RoguePmcRole;
import com.admin82.factions.network.packet.AiCombatProfilePacket;
import com.admin82.factions.registry.ModMenuTypes;
import com.admin82.factions.screen.AiCombatProfileScreen;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;


import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;

/** Admin editor for the per-class AI behavior sliders (opened with /faction ai edit). */
public class AiCombatProfileMenu extends AbstractContainerMenu {
    private static final int PAYLOAD_VERSION = 2;
    private static final int ROLE_COUNT = RoguePmcRole.values().length;
    private static final String ACTIVE_BG = "built-in(ui-mc:RECT_BORDER) color(#cc7700ff)";
    private static final int BAR_WIDTH = 110;
    private static final int DESIGN_WIDTH = 700;
    private static final int DESIGN_HEIGHT = 430;

    private final AiBehaviorProfile[] saved = new AiBehaviorProfile[ROLE_COUNT];
    private final AiBehaviorProfile[] editing = new AiBehaviorProfile[ROLE_COUNT];
    private int selectedRole;
    private int previewDifficulty = 3;

    public AiCombatProfileMenu(int id, Inventory inventory, FriendlyByteBuf buffer) {
        super(ModMenuTypes.AI_COMBAT_PROFILES.get(), id);
        int version = buffer.readVarInt();
        if (version != PAYLOAD_VERSION) throw new IllegalArgumentException("Unsupported AI profile payload version " + version);
        for (int role = 0; role < ROLE_COUNT; role++) {
            saved[role] = AiBehaviorProfile.read(buffer);
            editing[role] = saved[role];
        }
        if (FMLEnvironment.dist == Dist.CLIENT && this instanceof IModularUIHolderMenu holder) {
            holder.setModularUI(buildUi(inventory.player));
        }
    }

    public AiCombatProfileMenu(int id, Inventory inventory, Player player) {
        super(ModMenuTypes.AI_COMBAT_PROFILES.get(), id);
        AiBehaviorData data = AiBehaviorData.get(((ServerPlayer) player).server);
        for (RoguePmcRole role : RoguePmcRole.values()) {
            saved[role.ordinal()] = data.getProfile(role);
            editing[role.ordinal()] = saved[role.ordinal()];
        }
    }

    public static void writeOpenData(FriendlyByteBuf buffer, MinecraftServer server) {
        buffer.writeVarInt(PAYLOAD_VERSION);
        AiBehaviorData data = AiBehaviorData.get(server);
        for (RoguePmcRole role : RoguePmcRole.values()) data.getProfile(role).write(buffer);
    }

    // UI

    private ModularUI buildUi(Player player) {
        int[] size = UiKit.panelSize(DESIGN_WIDTH, DESIGN_HEIGHT, 560, 300);
        int panelWidth = size[0];
        int panelHeight = size[1];
        int contentWidth = panelWidth - 20;
        UIElement frame = new UIElement();
        frame.layout(l -> l.width(panelWidth).height(panelHeight).paddingAll(2));
        frame.addClass("preview_bg");
        UIElement root = column(6);
        root.layout(l -> l.width(panelWidth - 4).height(panelHeight - 4).paddingAll(8));
        root.addClass("panel_bg");
        root.addChildren(new Label().setText("§6§lAI Behavior"),
                new Label().setText("§7Tune how each guard class sees, hears, aims and fights."));

        UIElement classTabs = row(3);
        classTabs.layout(l -> l.height(22));
        for (RoguePmcRole role : RoguePmcRole.values()) {
            int index = role.ordinal();
            boolean active = index == selectedRole;
            boolean dirty = !editing[index].equals(saved[index]);
            Button button = new Button().setText((active ? "§f§l" : "§7") + shortName(role) + (dirty ? " §e*" : ""))
                    .setOnClick(event -> {
                        selectedRole = index;
                        rebuild();
                    });
            button.layout(l -> l.flex(1).height(20));
            if (active) button.lss("base-background", ACTIVE_BG);
            classTabs.addChildren(button);
        }

        UIElement body = row(8);
        body.layout(l -> l.alignItems(AlignItems.FLEX_START));
        body.addChildren(buildEditor(), buildPreview());
        if (panelHeight < DESIGN_HEIGHT) {
            // Small window / large GUI scale: scroll instead of spilling out of the panel.
            var scroller = new ScrollerView();
            scroller.layout(l -> l.flex(1).width(contentWidth));
            body.layout(l -> l.width(contentWidth - 10));
            scroller.addScrollViewChild(body);
            root.addChildren(classTabs, scroller);
        } else {
            body.layout(l -> l.width(contentWidth));
            root.addChildren(classTabs, body);
        }
        frame.addChildren(root);
        return ModularUI.of(UI.of(frame, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.GDP)), player);
    }

    private UIElement buildEditor() {
        AiBehaviorProfile profile = editing[selectedRole];
        UIElement card = card();
        card.layout(l -> l.width(320));
        card.addChildren(title(RoguePmcRole.values()[selectedRole].displayName()),
                new Label().setText("§8Click - / + to change by 5. Hold Shift for 1."));
        card.addChildren(
                statRow("Accuracy", profile.accuracy(), value -> update(p -> new AiBehaviorProfile(value,
                        p.reaction(), p.awareness(), p.aggression(), p.engagementRange(), p.preferredRange())),
                        "§eAccuracy", "§7How tight their shots are and how", "§7settled their aim must be to fire."),
                statRow("Reaction", profile.reaction(), value -> update(p -> new AiBehaviorProfile(p.accuracy(),
                        value, p.awareness(), p.aggression(), p.engagementRange(), p.preferredRange())),
                        "§eReaction", "§7How fast they notice you, turn", "§7toward you and get their aim on you."),
                statRow("Awareness", profile.awareness(), value -> update(p -> new AiBehaviorProfile(p.accuracy(),
                        p.reaction(), value, p.aggression(), p.engagementRange(), p.preferredRange())),
                        "§eAwareness", "§7How far they see, how wide their", "§7field of view is and how well they hear."),
                statRow("Aggression", profile.aggression(), value -> update(p -> new AiBehaviorProfile(p.accuracy(),
                        p.reaction(), p.awareness(), value, p.engagementRange(), p.preferredRange())),
                        "§eAggression", "§7High: push, flank and search for longer.", "§7Low: hold angles and stay in cover."),
                spacer(4),
                rangeRow("Shoots from", profile.engagementRange(), 4, value -> update(p -> new AiBehaviorProfile(
                        p.accuracy(), p.reaction(), p.awareness(), p.aggression(), value, p.preferredRange())),
                        "§eEngagement range", "§7Furthest distance (blocks) they shoot at."),
                rangeRow("Fights from", profile.preferredRange(), 2, value -> update(p -> new AiBehaviorProfile(
                        p.accuracy(), p.reaction(), p.awareness(), p.aggression(), p.engagementRange(), value)),
                        "§ePreferred distance", "§7They move to about this distance", "§7from you during a fight."),
                spacer(4));

        boolean dirty = !profile.equals(saved[selectedRole]);
        Button save = new Button().setText(dirty ? "§a§lSave" : "§aSave").setOnClick(event ->
                PacketDistributor.sendToServer(new AiCombatProfilePacket(selectedRole, editing[selectedRole])));
        save.layout(l -> l.width(80).height(20));
        Button revert = new Button().setText(dirty ? "Undo changes" : "§8Undo changes").setOnClick(event -> {
            editing[selectedRole] = saved[selectedRole];
            rebuild();
        });
        revert.layout(l -> l.width(100).height(20));
        Button defaults = new Button().setText("§7Class defaults").setOnClick(event -> {
            editing[selectedRole] = AiBehaviorProfile.defaults(RoguePmcRole.values()[selectedRole]);
            rebuild();
        });
        defaults.layout(l -> l.width(100).height(20));
        tooltip(defaults, "§7Load the built-in values for this class.", "§8Press Save to keep them.");
        UIElement buttons = row(6);
        buttons.layout(l -> l.height(22));
        buttons.addChildren(save, revert, defaults);
        card.addChildren(buttons, new Label().setText(dirty ? "§eUnsaved changes" : "§8All changes saved"));
        return card;
    }

    private UIElement buildPreview() {
        RoguePmcRole role = RoguePmcRole.values()[selectedRole];
        AiTuning tuning = editing[selectedRole].tuning(role, previewDifficulty);
        UIElement card = card();
        card.layout(l -> l.flex(1));
        card.addChildren(title("What this means"), new Label().setText("§8Preview at monument difficulty:"));
        UIElement difficultyRow = row(2);
        difficultyRow.layout(l -> l.height(20));
        for (int level = 1; level <= AiBehaviorData.DIFFICULTY_COUNT; level++) {
            int selected = level;
            boolean active = level == previewDifficulty;
            Button button = new Button().setText((active ? "§f§l" : "§7") + AiBehaviorData.DIFFICULTY_NAMES[level - 1])
                    .setOnClick(event -> {
                        previewDifficulty = selected;
                        rebuild();
                    });
            button.layout(l -> l.flex(1).height(18));
            if (active) button.lss("base-background", ACTIVE_BG);
            difficultyRow.addChildren(button);
        }
        card.addChildren(difficultyRow, new Label().setText("§8Each level shifts every slider by "
                + AiBehaviorProfile.DIFFICULTY_STEP + "."));
        String group = "";
        for (AiTuning.Stat stat : tuning.describe()) {
            if (!stat.group().equals(group)) {
                group = stat.group();
                card.addChildren(spacer(2), new Label().setText("§e" + group));
            }
            UIElement line = row(6);
            line.layout(l -> l.height(11).paddingHorizontal(4));
            line.addChildren(new Label().setText("§7" + stat.label()).layout(l -> l.width(130)),
                    new Label().setText("§f" + stat.value()));
            card.addChildren(line);
        }
        Label behaviour = new Label();
        behaviour.setText("§8Hover for how guards see and hear");
        tooltip(behaviour, "§eHow guards behave",
                "§7Only see you inside their field of view.",
                "§7Crouching, going prone and darkness hide you.",
                "§7Footsteps, doors, crates and gunfire make noise;",
                "§7sneaking is silent.",
                "§7Squads call out your last known position.");
        card.addChildren(spacer(4), behaviour);
        return card;
    }

    private UIElement statRow(String label, int value, IntConsumerUpdate setter, String... help) {
        UIElement bar = new UIElement();
        bar.layout(l -> l.width(BAR_WIDTH).height(8).flexDirection(FlexDirection.ROW));
        UiKit.background(bar, UiKit.BAR_TRACK);
        int fillWidth = Math.round(BAR_WIDTH * value / 100.0F);
        if (fillWidth > 0) {
            UIElement fill = new UIElement();
            fill.layout(l -> l.width(fillWidth).height(8));
            UiKit.background(fill, barColor(value));
            bar.addChildren(fill);
        }
        return controlRow(label, bar, "§f" + value, current -> Math.clamp(current, 0, 100), value, 5, setter, help);
    }

    private UIElement rangeRow(String label, int value, int step, IntConsumerUpdate setter, String... help) {
        Label amount = new Label();
        amount.setText("§f" + value + " §7blocks");
        amount.layout(l -> l.width(BAR_WIDTH));
        return controlRow(label, amount, "", current -> Math.clamp(current, 2, 256), value, step, setter, help);
    }

    private UIElement controlRow(String label, UIElement display, String valueText, IntUnaryOperator clamp,
                                 int value, int step, IntConsumerUpdate setter, String... help) {
        Button minus = new Button().setText("-").setOnClick(event ->
                setter.apply(clamp.applyAsInt(value - (Screen.hasShiftDown() ? 1 : step))));
        minus.layout(l -> l.width(18).height(18));
        Button plus = new Button().setText("+").setOnClick(event ->
                setter.apply(clamp.applyAsInt(value + (Screen.hasShiftDown() ? 1 : step))));
        plus.layout(l -> l.width(18).height(18));
        UIElement row = row(6);
        row.layout(l -> l.height(22));
        row.addChildren(new Label().setText("§7" + label).layout(l -> l.width(80)), minus, display, plus,
                new Label().setText(valueText).layout(l -> l.width(30)));
        tooltip(row, help);
        return row;
    }

    @FunctionalInterface
    private interface IntConsumerUpdate {
        void apply(int value);
    }

    private void update(UnaryOperator<AiBehaviorProfile> change) {
        editing[selectedRole] = change.apply(editing[selectedRole]).validated();
        rebuild();
    }

    private static int barColor(int value) {
        if (value >= 75) return 0xFFDD4444;
        if (value >= 50) return 0xFFDD9922;
        if (value >= 25) return 0xFFCCCC33;
        return 0xFF44AA44;
    }

    private static String shortName(RoguePmcRole role) {
        return role.displayName().replace("Rogue ", "").replace(" PMC", "");
    }

    private static UIElement row(int gap) {
        UIElement element = new UIElement();
        element.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(gap));
        return element;
    }

    private static UIElement column(int gap) {
        UIElement element = new UIElement();
        element.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(gap));
        return element;
    }

    private static UIElement card() {
        UIElement element = column(4);
        element.layout(l -> l.paddingAll(6));
        UiKit.background(element, UiKit.CARD);
        return element;
    }

    private static UIElement spacer(int height) {
        UIElement element = new UIElement();
        element.layout(l -> l.height(height));
        return element;
    }

    private static UIElement title(String text) {
        return new Label().setText("§6§l" + text);
    }

    private static void tooltip(UIElement element, String... lines) {
        Component[] components = Arrays.stream(lines).map(Component::literal).toArray(Component[]::new);
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, event ->
                event.hoverTooltips = HoverTooltips.empty().append(components));
    }

    private void rebuild() {
        if (Minecraft.getInstance().screen instanceof AiCombatProfileScreen screen) {
            screen.refreshContent();
        }
    }

    public void rebuildClientUi(Player player) {
        if (this instanceof IModularUIHolderMenu holder) holder.setModularUI(buildUi(player));
    }

    /** Server confirmed a save for this class. */
    public void updateProfile(int role, AiBehaviorProfile profile) {
        if (role < 0 || role >= ROLE_COUNT) return;
        saved[role] = profile.validated();
        editing[role] = saved[role];
    }

    @Override public boolean stillValid(Player player) { return player.hasPermissions(2); }

    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
