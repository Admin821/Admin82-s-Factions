package com.admin82.factions.menu;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import dev.vfyjxf.taffy.style.AlignContent;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

/** A search box over a scrollable list: typing narrows the list (by name or id), clicking an entry picks it. */
@OnlyIn(Dist.CLIENT)
public final class SearchPicker {

    private SearchPicker() {}

    /**
     * @param ids      everything that can be picked
     * @param name     the shown name of an id
     * @param current  the id picked now (highlighted)
     * @param query    the search to start with (screens rebuild themselves, so they keep it and pass it back)
     * @param onQuery  told every time the search changes
     * @param onPick   told when an entry is clicked
     */
    public static UIElement create(List<String> ids, Function<String, String> name, String current, String query,
                                   Consumer<String> onQuery, Consumer<String> onPick, int listHeight) {
        var root = new UIElement();
        root.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(2).widthPercent(100));
        var search = UiKit.textField("Search...");
        search.setValue(query);
        search.layout(l -> l.height(14).widthPercent(100));

        var scroller = UiKit.background(new ScrollerView(), UiKit.CARD);
        scroller.layout(l -> l.widthPercent(100).height(listHeight));
        var list = new UIElement();
        list.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(1).widthPercent(100).paddingRight(10));
        scroller.addScrollViewChild(list);

        Consumer<String> fill = new Consumer<>() {
            @Override
            public void accept(String q0) {
                String q = q0.trim().toLowerCase(Locale.ROOT);
                list.clearAllChildren();
                int shown = 0;
                for (String id : ids) {
                    String n = name.apply(id);
                    if (!q.isEmpty() && !n.toLowerCase(Locale.ROOT).contains(q) && !id.toLowerCase(Locale.ROOT).contains(q)) continue;
                    boolean picked = id.equals(current);
                    var row = UiKit.background(new UIElement(), picked ? 0x704A90E0 : UiKit.ROW);
                    row.layout(l -> l.height(12).paddingLeft(3).justifyContent(AlignContent.CENTER));
                    row.addChildren(new Label().setText((picked ? "§b▶ §f" : "§f") + n + (n.equals(id) ? "" : " §8" + id)));
                    UiKit.onClick(row, e -> onPick.accept(id));
                    list.addChildren(row);
                    shown++;
                }
                if (shown == 0) list.addChildren(new Label().setText("§8Nothing matches \"" + q0.trim() + "\"."));
            }
        };
        search.bindObserver(v -> { onQuery.accept(v); fill.accept(v); });
        fill.accept(query);
        root.addChildren(search, scroller);
        return root;
    }
}
