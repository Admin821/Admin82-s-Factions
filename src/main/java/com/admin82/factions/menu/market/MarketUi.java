package com.admin82.factions.menu.market;

import com.admin82.factions.economy.Currency;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.*;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.AnimalArmorItem;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SaddleItem;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TippedArrowItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import dev.vfyjxf.taffy.style.AlignItems;


import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Small layout helpers and item lookups shared by the market pages. */
final class MarketUi {

    private MarketUi() {}

    static List<Item> findItemResults(String query) {
        if (query == null || query.isBlank()) return List.of();
        String needle = query.toLowerCase(Locale.ROOT);
        return BuiltInRegistries.ITEM.stream()
                .filter(item -> {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    String name = item.getDescription().getString().toLowerCase(Locale.ROOT);
                    String key = id.toString().toLowerCase(Locale.ROOT);
                    return name.contains(needle) || key.contains(needle);
                })
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .limit(200)
                .toList();
    }

    /** Item category for the filter. Order matters: the first match wins. */
    static String categoryOf(ItemStack stack, String name, String itemId, String namespace) {
        Item item = stack.getItem();
        String path = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        if (namespace.equals("tacz")) {
            if (path.contains("ammo") || name.contains("ammo") || name.contains("round") || name.contains("magazine")) return "Ammo";
            return "Guns";
        }
        if (item instanceof ArrowItem || path.contains("ammo") || path.contains("bullet")) return "Ammo";
        if (item instanceof ArmorItem || item instanceof ShieldItem
                || item instanceof ElytraItem || item instanceof AnimalArmorItem) {
            return "Armor";
        }
        if (item instanceof SwordItem || item instanceof ProjectileWeaponItem
                || item instanceof TridentItem || item instanceof MaceItem) {
            return "Weapons";
        }
        if (item instanceof DiggerItem || item instanceof ShearsItem
                || item instanceof FishingRodItem || item instanceof FlintAndSteelItem
                || item instanceof BrushItem || item instanceof TieredItem) {
            return "Tools";
        }
        if (item instanceof PotionItem || item instanceof TippedArrowItem
                || stack.has(DataComponents.POTION_CONTENTS)) return "Potions";
        if (stack.has(DataComponents.FOOD)) return "Food";
        if (isRedstone(path)) return "Redstone";
        if (item instanceof BlockItem block && isUtilityBlock(block, path)) return "Utility";
        if (isResourceLike(path) && !(item instanceof BlockItem && !path.contains("ore"))) return "Resources";
        if (item instanceof BlockItem) return "Blocks";
        if (item instanceof EnderpearlItem || item instanceof BucketItem
                || item instanceof FireworkRocketItem || item instanceof BoatItem
                || item instanceof MinecartItem || item instanceof SpawnEggItem
                || item instanceof EnchantedBookItem || item instanceof MapItem
                || item instanceof LeadItem || item instanceof SaddleItem
                || path.contains("totem") || path.contains("compass") || path.contains("clock") || path.contains("spyglass")
                || path.contains("bundle") || path.contains("name_tag") || path.contains("key")) {
            return "Utility";
        }
        if (isResourceLike(path)) return "Resources";
        return "Misc";
    }

    private static boolean isRedstone(String path) {
        return path.contains("redstone") || path.contains("repeater") || path.contains("comparator")
                || path.contains("piston") || path.contains("observer") || path.contains("hopper")
                || path.contains("dropper") || path.contains("dispenser") || path.contains("lever")
                || path.contains("button") || path.contains("pressure_plate") || path.contains("tripwire")
                || path.contains("daylight_detector") || path.contains("target") || path.contains("rail")
                || path.contains("note_block") || path.contains("sculk_sensor") || path.contains("crafter")
                || path.contains("lightning_rod") || path.contains("tnt");
    }

    private static boolean isUtilityBlock(BlockItem item, String path) {
        var block = item.getBlock();
        return block instanceof EntityBlock
                || block instanceof BedBlock
                || block instanceof CraftingTableBlock
                || block instanceof AnvilBlock
                || block instanceof LadderBlock
                || block instanceof TorchBlock
                || block instanceof LanternBlock
                || path.contains("chest") || path.contains("barrel") || path.contains("furnace")
                || path.contains("smoker") || path.contains("table") || path.contains("stonecutter")
                || path.contains("grindstone") || path.contains("loom") || path.contains("anvil")
                || path.contains("beacon") || path.contains("shulker_box") || path.contains("bed");
    }

    private static boolean isResourceLike(String path) {
        return path.contains("ore") || path.contains("ingot") || path.contains("nugget") || path.contains("gem")
                || path.contains("raw_") || path.contains("coal") || path.contains("diamond") || path.contains("emerald")
                || path.contains("lapis") || path.contains("netherite") || path.contains("quartz") || path.contains("amethyst")
                || path.contains("scrap") || path.contains("dust") || path.contains("string") || path.contains("leather")
                || path.contains("feather") || path.contains("bone") || path.contains("gunpowder") || path.contains("slime_ball")
                || path.contains("blaze") || path.contains("ender_eye") || path.contains("pearl") || path.contains("shard")
                || path.contains("stick") || path.contains("flint") || path.contains("paper") || path.contains("dye");
    }

    /** Item name (cut to fit {@code maxPixels}) with the stack size. */
    @OnlyIn(Dist.CLIENT)
    static String itemLabel(ItemStack stack, int maxPixels) {
        var font = Minecraft.getInstance().font;
        String count = stack.getCount() > 1 ? " ×" + stack.getCount() : "";
        String name = stack.getHoverName().getString();
        int budget = Math.max(20, maxPixels - font.width(count) - 4);
        if (font.width(name) > budget) name = font.plainSubstrByWidth(name, budget - font.width("…")) + "…";
        return "§f" + name + "§7" + count;
    }

    static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    static String timeLeft(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds >= 86400) return (seconds / 86400) + "d";
        if (seconds >= 3600) return (seconds / 3600) + "h";
        return Math.max(1, seconds / 60) + "m";
    }

    static long priceCoinMult(String coin) {
        return switch (coin) {
            case "Platinum" -> Currency.COPPER_PER_PLATINUM;
            case "Gold"     -> Currency.COPPER_PER_GOLD;
            case "Silver"   -> Currency.COPPER_PER_SILVER;
            default         -> 1L;
        };
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement hbox(int gap) {
        var box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(gap));
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement vbox(int gap) {
        var box = new UIElement();
        box.layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(gap));
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement spacer(int height) {
        var box = new UIElement();
        box.layout(l -> l.height(height));
        return box;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement sectionLabel(String text) {
        return new Label().setText("§6§l" + text).layout(l -> l.height(12).paddingTop(2).paddingLeft(2));
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement infoRow(String label, String value) {
        var row = hbox(6);
        row.layout(l -> l.height(12));
        row.addChildren(new Label().setText("§7" + label).layout(l -> l.width(90)), new Label().setText(value));
        return row;
    }

    @OnlyIn(Dist.CLIENT)
    static Button smallButton(String text, int width, Runnable action) {
        var button = new Button().setText(text).setOnClick(e -> action.run());
        button.layout(l -> l.width(width).height(18));
        return button;
    }

    @OnlyIn(Dist.CLIENT)
    static UIElement backButton(Runnable action) {
        var button = new Button().setText("§7← Back").setOnClick(e -> action.run());
        button.layout(l -> l.width(70).height(20));
        return button;
    }

    @OnlyIn(Dist.CLIENT)
    static void tooltipOn(UIElement element, String... lines) {
        Component[] components = Arrays.stream(lines).map(Component::literal).toArray(Component[]::new);
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, event ->
                event.hoverTooltips = HoverTooltips.empty().append(components));
    }
}
