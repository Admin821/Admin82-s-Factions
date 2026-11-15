package com.admin82.factions.seam;

import com.admin82.factions.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Which ores can appear in a dimension's seams: every block in the {@code c:ores} tag (vanilla and modded) that
 * belongs to that dimension, minus the admin blacklist. The list is sorted so the same world seed always gives the
 * same seams.
 */
public final class SeamOres {

    private static final TagKey<Block> ORES = tag("ores");
    private static final TagKey<Block> IN_STONE = tag("ores_in_ground/stone");
    private static final TagKey<Block> IN_DEEPSLATE = tag("ores_in_ground/deepslate");
    private static final TagKey<Block> IN_NETHERRACK = tag("ores_in_ground/netherrack");
    private static final TagKey<Block> IN_END_STONE = tag("ores_in_ground/end_stone");

    private SeamOres() {}

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", path));
    }

    /** Ore item ids for the level's dimension, sorted. */
    public static List<String> forLevel(ServerLevel level) {
        String dim = level.dimension() == Level.NETHER ? "nether" : level.dimension() == Level.END ? "end"
                : level.dimension() == Level.OVERWORLD ? "overworld" : "other";
        List<String> ids = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            var holder = block.builtInRegistryHolder();
            if (!isOre(block)) continue;
            Item item = oreItem(block);
            if (item == null) continue;
            if (!belongs(holder, BuiltInRegistries.BLOCK.getKey(block), dim)) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (!blacklisted(id)) ids.add(id);
        }
        ids.sort(null);
        return ids;
    }

    /**
     * Every ore in the game, from any dimension and blacklisted or not, sorted: blocks tagged {@code c:ores}, plus ore
     * blocks a mod forgot to tag (named like {@code *_ore}). For admins spawning seams by hand; works on the client too.
     */
    public static List<String> all() {
        java.util.TreeSet<String> ids = new java.util.TreeSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Item item = isOre(block) ? oreItem(block) : null;
            if (item != null) ids.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        return new ArrayList<>(ids);
    }

    /**
     * Whether a block is an ore: tagged {@code c:ores}, or named like one, since plenty of mods don't tag theirs
     * ({@code *_ore}, {@code *_ore_*}, or NTM-style {@code ore_*}). This mod's own blocks never count.
     */
    public static boolean isOre(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id.getNamespace().equals(com.admin82.factions.AdminsFactions.MODID)) return false;
        if (block.builtInRegistryHolder().is(ORES)) return true;
        Item item = oreItem(block);
        if (item != null && item.builtInRegistryHolder().is(net.neoforged.neoforge.common.Tags.Items.ORES)) return true;
        String path = id.getPath();
        return path.endsWith("_ore") || path.contains("_ore_") || path.startsWith("ore_") || path.equals("ancient_debris");
    }

    /** The item for an ore block: its block item, or failing that an item registered under the same id. */
    @javax.annotation.Nullable
    public static Item oreItem(Block block) {
        Item item = block.asItem();
        if (item != net.minecraft.world.item.Items.AIR) return item;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id) : null;
    }

    /** Uses the "ores in ground" tags when a mod sets them, otherwise guesses from the block's name. */
    private static boolean belongs(net.minecraft.core.Holder<Block> holder, ResourceLocation id, String dim) {
        if (dim.equals("other")) return true;
        boolean stone = holder.is(IN_STONE) || holder.is(IN_DEEPSLATE);
        boolean nether = holder.is(IN_NETHERRACK);
        boolean end = holder.is(IN_END_STONE);
        if (!stone && !nether && !end) {
            String path = id.getPath().toLowerCase(Locale.ROOT);
            nether = path.contains("nether") || path.contains("ancient_debris") || path.contains("blackstone");
            end = !nether && (path.startsWith("end_") || path.contains("_end_") || path.contains("endstone"));
            stone = !nether && !end;
        }
        return switch (dim) {
            case "nether" -> nether;
            case "end" -> end;
            default -> stone;
        };
    }

    /** Whether an ore is on the admin blacklist (exact id, or "modid:*"). */
    public static boolean blacklisted(String id) {
        String ns = id.contains(":") ? id.substring(0, id.indexOf(':')) : "minecraft";
        for (String entry : Config.SEAM_ORE_BLACKLIST.get()) {
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (e.equals(id) || e.equals(ns + ":*")) return true;
        }
        return false;
    }
}
