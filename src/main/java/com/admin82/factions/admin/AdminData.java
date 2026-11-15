package com.admin82.factions.admin;

import com.admin82.factions.economy.ExchangeManager;
import com.admin82.factions.economy.MarketListing;
import com.admin82.factions.economy.MarketManager;
import com.admin82.factions.entity.monument.AiBehaviorData;
import com.admin82.factions.monument.MonumentData;
import com.admin82.factions.supplydrop.SupplyDropData;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Export and import of admin-made content and settings, as readable SNBT files in
 * {@code config/adminsfactions/exports/}. Files can be copied between servers.
 */
public final class AdminData {

    public static final int FORMAT_VERSION = 1;

    public enum Section {
        SETTINGS("Mod settings", "Claim costs, taxes, war rules, bounties and the rest of the settings panel."),
        MONUMENTS("Monuments & loot pools", "Every monument: name, tier, timers, chunks, loot pools, guard roster, gear and drops."),
        AI_PROFILES("AI combat profiles", "Accuracy, reaction, awareness and aggression for each guard class."),
        SUPPLY_DROPS("Supply drops", "Supply drop loot and spawn settings."),
        EXCHANGE("Currency exchange", "Every exchange rate and whether players can buy items."),
        SERVER_MARKET("Server market listings", "Server shop items and server buy orders (not player listings).");

        public final String label;
        public final String help;

        Section(String label, String help) {
            this.label = label;
            this.help = help;
        }

        public int bit() { return 1 << ordinal(); }
    }

    public record ExportFile(String name, long size, long modified, int sections, String exportedBy) {}

    private AdminData() {}

    public static Path exportDir() throws IOException {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("adminsfactions").resolve("exports");
        Files.createDirectories(dir);
        return dir;
    }

    /** Only plain file names inside the export folder; blocks paths like "../x". */
    public static Path resolve(String name) throws IOException {
        String clean = name.replaceAll("[^A-Za-z0-9 _.-]", "").trim();
        if (clean.isEmpty() || clean.startsWith(".")) throw new IOException("Invalid file name.");
        if (!clean.endsWith(".snbt")) clean += ".snbt";
        Path dir = exportDir();
        Path file = dir.resolve(clean).normalize();
        if (!file.getParent().equals(dir.normalize())) throw new IOException("Invalid file name.");
        return file;
    }

    // Export

    /** Writes the chosen sections to a new file and returns its name. */
    public static String export(MinecraftServer server, int sections, String requestedName, String exportedBy) throws IOException {
        HolderLookup.Provider registries = server.registryAccess();
        CompoundTag root = new CompoundTag();
        root.putInt("format", FORMAT_VERSION);
        root.putLong("exportedAt", System.currentTimeMillis());
        root.putString("exportedBy", exportedBy);
        CompoundTag data = new CompoundTag();
        for (Section s : Section.values()) {
            if ((sections & s.bit()) == 0) continue;
            data.put(s.name(), switch (s) {
                case SETTINGS -> exportSettings(server);
                case MONUMENTS -> MonumentData.get(server).save(new CompoundTag(), registries);
                case AI_PROFILES -> AiBehaviorData.get(server).save(new CompoundTag(), registries);
                case SUPPLY_DROPS -> SupplyDropData.get(server).save(new CompoundTag(), registries);
                case EXCHANGE -> ExchangeManager.get(server).save(new CompoundTag(), registries);
                case SERVER_MARKET -> exportServerMarket(server, registries);
            });
        }
        root.put("data", data);

        String base = requestedName == null || requestedName.isBlank()
                ? "export-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
                : requestedName;
        Path file = resolve(base);
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        return file.getFileName().toString();
    }

    private static CompoundTag exportSettings(MinecraftServer server) {
        CompoundTag tag = new CompoundTag();
        for (AdminSettings.Setting s : AdminSettings.all()) tag.putDouble(s.key(), s.get(server));
        return tag;
    }

    private static CompoundTag exportServerMarket(MinecraftServer server, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (MarketListing l : MarketManager.get(server).getListings()) {
            if (l.isAdminListing()) list.add(l.save(registries));
        }
        CompoundTag tag = new CompoundTag();
        tag.put("listings", list);
        return tag;
    }

    // Import

    public static CompoundTag read(String name) throws IOException {
        Path file = resolve(name);
        if (!Files.exists(file)) throw new IOException("File not found: " + name);
        try {
            return TagParser.parseTag(Files.readString(file, StandardCharsets.UTF_8));
        } catch (CommandSyntaxException e) {
            throw new IOException("The file is not valid SNBT: " + e.getMessage());
        }
    }

    /** Applies the chosen sections from a file. Returns one line per section that was imported. */
    public static List<String> importFile(MinecraftServer server, String name, int sections) throws IOException {
        CompoundTag root = read(name);
        CompoundTag data = root.getCompound("data");
        HolderLookup.Provider registries = server.registryAccess();
        // Read every chosen section first. If anything in the file is broken, nothing has been changed yet.
        List<Runnable> apply = new ArrayList<>();
        List<String> done = new ArrayList<>();
        try {
            for (Section s : Section.values()) {
                if ((sections & s.bit()) == 0 || !data.contains(s.name(), Tag.TAG_COMPOUND)) continue;
                CompoundTag tag = data.getCompound(s.name());
                switch (s) {
                    case SETTINGS -> apply.add(() -> done.add("Mod settings: " + importSettings(server, tag) + " values"));
                    case MONUMENTS -> {
                        MonumentData loaded = MonumentData.load(tag, registries);
                        apply.add(() -> {
                            replace(server, "adminsfactions_monuments", loaded);
                            done.add("Monuments: " + loaded.getAll().size() + " monuments");
                        });
                    }
                    case AI_PROFILES -> {
                        AiBehaviorData loaded = AiBehaviorData.load(tag, registries);
                        apply.add(() -> { replace(server, "adminsfactions_ai_behavior", loaded); done.add("AI combat profiles"); });
                    }
                    case SUPPLY_DROPS -> {
                        SupplyDropData loaded = SupplyDropData.load(tag, registries);
                        apply.add(() -> { replace(server, "adminsfactions_supply_drops", loaded); done.add("Supply drops"); });
                    }
                    case EXCHANGE -> {
                        ExchangeManager loaded = ExchangeManager.load(tag, registries);
                        apply.add(() -> {
                            replace(server, "adminsfactions_exchange", loaded);
                            done.add("Currency exchange: " + loaded.getRates().size() + " rates");
                        });
                    }
                    case SERVER_MARKET -> {
                        List<MarketListing> listings = readServerMarket(tag, registries);
                        apply.add(() -> done.add("Server market: " + replaceServerMarket(server, listings) + " listings"));
                    }
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Nothing was imported: the file has a broken section (" + e.getMessage() + ").");
        }
        apply.forEach(Runnable::run);
        server.overworld().getDataStorage().save();
        return done;
    }

    private static int importSettings(MinecraftServer server, CompoundTag tag) {
        int count = 0;
        for (AdminSettings.Setting s : AdminSettings.all()) {
            if (!tag.contains(s.key())) continue;
            double value = tag.getDouble(s.key());
            if (Math.abs(s.get(server) - value) > 1e-9) s.set(server, value);
            count++;
        }
        return count;
    }

    private static List<MarketListing> readServerMarket(CompoundTag tag, HolderLookup.Provider registries) {
        List<MarketListing> listings = new ArrayList<>();
        for (Tag raw : tag.getList("listings", Tag.TAG_COMPOUND)) {
            MarketListing l = MarketListing.load((CompoundTag) raw, registries);
            if (l == null || !l.isAdminListing()) continue;
            l.listingId = UUID.randomUUID();
            listings.add(l);
        }
        return listings;
    }

    /** Replaces the server's own listings; player listings are left alone. */
    private static int replaceServerMarket(MinecraftServer server, List<MarketListing> listings) {
        MarketManager market = MarketManager.get(server);
        List<UUID> old = market.getListings().stream().filter(MarketListing::isAdminListing).map(l -> l.listingId).toList();
        old.forEach(market::removeListing);
        listings.forEach(market::addListing);
        return listings.size();
    }

    private static void replace(MinecraftServer server, String dataName, SavedData data) {
        server.overworld().getDataStorage().set(dataName, data);
        data.setDirty();
    }

    // Listing files

    public static List<ExportFile> list() {
        List<ExportFile> files = new ArrayList<>();
        try (var stream = Files.list(exportDir())) {
            for (Path p : stream.filter(f -> f.getFileName().toString().endsWith(".snbt")).toList()) {
                int mask = 0;
                String by = "";
                try {
                    CompoundTag root = TagParser.parseTag(Files.readString(p, StandardCharsets.UTF_8));
                    CompoundTag data = root.getCompound("data");
                    for (Section s : Section.values()) if (data.contains(s.name())) mask |= s.bit();
                    by = root.getString("exportedBy");
                } catch (Exception ignored) {
                    // Unreadable file: still listed so it can be deleted.
                }
                files.add(new ExportFile(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toMillis(), mask, by));
            }
        } catch (IOException ignored) { }
        files.sort(Comparator.comparingLong(ExportFile::modified).reversed());
        return files;
    }

    public static void delete(String name) throws IOException {
        Files.deleteIfExists(resolve(name));
    }
}
