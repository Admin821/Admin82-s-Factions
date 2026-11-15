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
            if (!holder.is(ORES)) continue;
            Item item = block.asItem();
            if (!(item instanceof BlockItem)) continue;
            if (!belongs(holder, BuiltInRegistries.BLOCK.getKey(block), dim)) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            if (!blacklisted(id)) ids.add(id);
        }
        ids.sort(null);
        return ids;
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
