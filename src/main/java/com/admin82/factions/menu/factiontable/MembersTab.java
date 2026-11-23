package com.admin82.factions.menu.factiontable;

import com.admin82.factions.faction.*;
import com.admin82.factions.menu.UiKit;
import com.admin82.factions.menu.factiontable.FactionTableMenu.Tab;
import com.admin82.factions.network.packet.*;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SupplierDataSource;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import dev.vfyjxf.taffy.style.AlignItems;


import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/** The Members and Permissions tabs. */
final class MembersTab {

    private final FactionTableMenu menu;

    MembersTab(FactionTableMenu menu) {
        this.menu = menu;
    }

    private FactionRole selectedPermRole = FactionRole.MEMBER;

    @Nullable private UIElement permArea;
    @Nullable private UIElement memberDetailArea;

    private final AtomicReference<String> memberActionStatus = new AtomicReference<>("");

    @Nullable @SuppressWarnings("rawtypes") Selector inviteSelectorRef;

    @Nullable private String selectedMemberName;
    @Nullable UUID kickArmedFor;
    private int memberListPage;

    // Members

    private static final int MEMBER_ROWS = 9;

    @SuppressWarnings("unchecked")
    UIElement buildMembersPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).gapAll(6).flexDirection(FlexDirection.COLUMN));
        Faction faction = menu.factionRef.get();
        if (faction == null) return panel;

        List<FactionMember> members = faction.getMembers().stream()
                .sorted(Comparator.comparingInt((FactionMember m) -> -m.getRole().getLevel())
                        .thenComparing(FactionMember::getPlayerName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (selectedMemberName == null || members.stream().noneMatch(m -> m.getPlayerName().equalsIgnoreCase(selectedMemberName))) {
            selectedMemberName = members.isEmpty() ? null : members.get(0).getPlayerName();
        }

        var columns = new UIElement();
        columns.layout(l -> l.flex(1).flexDirection(FlexDirection.ROW).gapAll(6).alignItems(AlignItems.FLEX_START));

        // Member list
        var list = FactionTableMenu.card();
        list.layout(l -> l.width(150));
        list.addChildren(new Label().setText("§6Members §7(" + members.size() + ")"));
        int pages = Math.max(1, (members.size() + MEMBER_ROWS - 1) / MEMBER_ROWS);
        memberListPage = Math.clamp(memberListPage, 0, pages - 1);
        for (FactionMember member : members.stream().skip((long) memberListPage * MEMBER_ROWS).limit(MEMBER_ROWS).toList()) {
            boolean active = member.getPlayerName().equalsIgnoreCase(selectedMemberName);
            boolean online = availableOnline(member);
            var btn = new Button().setText((active ? "§f§l" : FactionTableMenu.roleColor(member.getRole()))
                    + (online ? "● " : "○ ") + member.getPlayerName())
                    .setOnClick(e -> {
                        selectedMemberName = member.getPlayerName();
                        kickArmedFor = null;
                        menu.reloadTab(Tab.MEMBERS, player);
                    });
            btn.layout(l -> l.height(18));
            if (active) btn.lss("base-background", FactionTableMenu.ACTIVE_BG);
            list.addChildren(btn);
        }
        if (pages > 1) {
            var pager = new UIElement();
            pager.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(4).height(16));
            pager.addChildren(
                    new Button().setText("‹").setOnClick(e -> { memberListPage--; menu.reloadTab(Tab.MEMBERS, player); })
                            .layout(l -> l.width(18).height(14)),
                    new Label().setText("§7" + (memberListPage + 1) + "/" + pages).layout(l -> l.flex(1)),
                    new Button().setText("›").setOnClick(e -> { memberListPage++; menu.reloadTab(Tab.MEMBERS, player); })
                            .layout(l -> l.width(18).height(14)));
            list.addChildren(pager);
        }

        // Detail for the selected member
        var detail = FactionTableMenu.card();
        detail.layout(l -> l.flex(1));
        FactionMember target = members.stream()
                .filter(m -> m.getPlayerName().equalsIgnoreCase(selectedMemberName)).findFirst().orElse(null);
        if (target != null) fillMemberDetail(detail, target, faction, player);
        columns.addChildren(list, detail);
        panel.addChildren(columns);

        if (menu.hasPermission(player, FactionPermission.OFFICER_INVITE)) {
            var inviteNameVal = new String[]{""};
            var inviteSel = new Selector<String>();
            List<String> inviteCandidates = menu.availablePlayers.get();
            inviteSel.setCandidates(inviteCandidates);
            if (!inviteCandidates.isEmpty()) {
                inviteNameVal[0] = inviteCandidates.get(0);
                inviteSel.setValue(inviteNameVal[0]);
            }
            inviteSel.setOnValueChanged(n -> inviteNameVal[0] = n);
            inviteSel.layout(l -> l.flex(1).height(18));
            inviteSelectorRef = inviteSel;
            var inviteBtn = new Button().setText("§aInvite").setOnClick(e -> {
                String n = inviteNameVal[0] == null ? "" : inviteNameVal[0].trim();
                if (!n.isEmpty()) {
                    memberActionStatus.set("§7Invite sent to §e" + n + "§7.");
                    PacketDistributor.sendToServer(new MemberActionPacket(MemberActionPacket.Action.INVITE,
                            new UUID(0, 0), n, FactionRole.MEMBER));
                }
            });
            inviteBtn.layout(l -> l.width(60).height(18));
            var inviteRow = UiKit.background(new UIElement(), UiKit.CARD);
            inviteRow.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(4)
                    .height(26).paddingHorizontal(6));
            inviteRow.addChildren(new Label().setText("§7Invite player").layout(l -> l.width(72)), inviteSel, inviteBtn);
            panel.addChildren(inviteRow);
        }
        return panel;
    }

    /** Online players are the ones offered in the invite list plus current members seen online. */
    private boolean availableOnline(FactionMember member) {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.getPlayerInfo(member.getUuid()) != null;
    }

    private void fillMemberDetail(UIElement area, FactionMember target, Faction faction, Player self) {
        FactionMember selfMember = faction.getMember(self.getUUID());
        boolean isOwner = faction.getOwnerId().equals(self.getUUID());
        boolean selfOfficer = selfMember != null && selfMember.getRole().getLevel() >= FactionRole.OFFICER.getLevel();
        UUID uid = target.getUuid();

        area.addChildren(
                new Label().setText("§f§l" + target.getPlayerName() + (uid.equals(self.getUUID()) ? " §7(you)" : "")),
                new Label().setText("§7Role: " + FactionTableMenu.roleColor(target.getRole()) + FactionTableMenu.capitalize(target.getRole().getId())),
                new Label().setText(availableOnline(target) ? "§a● Online" : "§8○ Offline"));

        if (isOwner && target.getRole() != FactionRole.OWNER) {
            area.addChildren(new Label().setText("§7Set role"));
            var roleRow = new UIElement();
            roleRow.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).height(18));
            for (FactionRole role : new FactionRole[]{FactionRole.ADMIN, FactionRole.OFFICER, FactionRole.MEMBER}) {
                boolean current = role == target.getRole();
                var btn = new Button().setText((current ? "§f§l" : FactionTableMenu.roleColor(role)) + FactionTableMenu.capitalize(role.getId()))
                        .setOnClick(e -> {
                            if (current) return;
                            memberActionStatus.set("§7Changing §e" + target.getPlayerName() + "§7 to §e" + role.getId() + "§7.");
                            PacketDistributor.sendToServer(new MemberActionPacket(MemberActionPacket.Action.SET_ROLE, uid, "", role));
                        });
                btn.layout(l -> l.flex(1).height(18));
                if (current) btn.lss("base-background", FactionTableMenu.ACTIVE_BG);
                roleRow.addChildren(btn);
            }
            area.addChildren(roleRow);
        }

        boolean canKick = !uid.equals(self.getUUID())
                && faction.getRolePermission(selfMember.getRole(), FactionPermission.OFFICER_KICK)
                && target.getRole().getLevel() < selfMember.getRole().getLevel();
        if (canKick) {
            boolean armed = uid.equals(kickArmedFor);
            var kick = new Button().setText(armed ? "§f§lClick again to kick" : "§cKick from faction")
                    .setOnClick(e -> {
                        if (!uid.equals(kickArmedFor)) {
                            kickArmedFor = uid;
                            menu.reloadTab(Tab.MEMBERS, self);
                            return;
                        }
                        kickArmedFor = null;
                        memberActionStatus.set("§7Kick request sent for §e" + target.getPlayerName() + "§7.");
                        PacketDistributor.sendToServer(new MemberActionPacket(MemberActionPacket.Action.KICK, uid, "", FactionRole.MEMBER));
                    });
            kick.layout(l -> l.height(18));
            if (armed) kick.lss("base-background", FactionTableMenu.DANGER_BG);
            area.addChildren(new Label().setText(""), kick);
        }
        area.addChildren(new Label().bindDataSource(SupplierDataSource.of(() -> Component.literal(memberActionStatus.get()))));
    }

    // Permissions

    UIElement buildPermissionsPanel(Player player) {
        var panel = new UIElement();
        panel.layout(l -> l.flex(1).paddingAll(8).gapAll(6).flexDirection(FlexDirection.COLUMN));
        boolean isOwner = menu.factionRef.get() != null && menu.factionRef.get().getOwnerId().equals(player.getUUID());

        permArea = new UIElement();
        permArea.layout(l -> l.width(404));
        fillPermArea(permArea, isOwner);

        var roleBar = new UIElement();
        roleBar.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4).width(404));
        for (FactionRole role : new FactionRole[]{FactionRole.ADMIN, FactionRole.OFFICER, FactionRole.MEMBER}) {
            final FactionRole fr = role;
            var roleBtn = new Button()
                    .setText(FactionTableMenu.roleColor(role) + FactionTableMenu.capitalize(role.getId()))
                    .setOnClick(e -> { selectedPermRole = fr; permArea.clearAllChildren(); fillPermArea(permArea, isOwner); });
            roleBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, ev -> ev.hoverTooltips = HoverTooltips.empty().append(
                    Component.literal(FactionTableMenu.roleColor(fr) + "§l" + FactionTableMenu.capitalize(fr.getId()) + " permissions"),
                    Component.literal("§7Configure what " + fr.getId() + "s are allowed to do"),
                    Component.literal("§8Toggles below apply to this role only.")));
            roleBtn.layout(l -> l.flex(1).height(20));
            roleBar.addChildren(roleBtn);
        }
        panel.addChildren(
                new Label().setText("§7Permissions §8(owner only) — toggle what each role is allowed to do"),
                roleBar, permArea);
        return panel;
    }

    private void fillPermArea(UIElement area, boolean isOwner) {
        area.clearAllChildren();
        Faction faction = menu.factionRef.get();
        FactionRole role = selectedPermRole;
        for (FactionPermission perm : FactionPermission.values()) {
            boolean current = faction != null && faction.getRolePermission(role, perm);
            var row = new UIElement();
            row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6).height(28));
            var labelCol = new UIElement();
            labelCol.layout(l -> l.flex(1).flexDirection(FlexDirection.COLUMN).gapAll(1));
            labelCol.addChildren(
                    new Label().setText(FactionTableMenu.permLabel(perm)),
                    new Label().setText("§8" + FactionTableMenu.permDescription(perm))
            );
            row.addChildren(
                    labelCol,
                    UiKit.toggle().setOn(current).setOnToggleChanged(on -> {
                        if (!isOwner) return;
                        PacketDistributor.sendToServer(new UpdateRolePermissionPacket(role.getId(), perm.getKey(), on));
                    }).layout(l -> l.width(50))
            );
            area.addChildren(row);
        }
    }
}
