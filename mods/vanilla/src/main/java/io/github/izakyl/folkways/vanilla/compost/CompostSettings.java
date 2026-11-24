package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.SlotSnapshot;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.farming.CropSeeds;
import io.github.izakyl.folkways.vanilla.farming.FarmingContent;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

/**
 * Per-colony compost preferences. The switch and the default reserve are the composting scope's settings, shown on
 * the book's settings page; a reserve set for one kind of seed is kept with the colony beside them.
 */
public final class CompostSettings {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "compost_settings");
    public static final int DEFAULT_RESERVE = 64;
    public static final int MAX_RESERVE = 1_000_000;
    /** Whether residents compost surplus seeds at all; the composting scope's own setting. */
    static final Schema.Setting.Flag ENABLED = new Schema.Setting.Flag("enabled",
        "folkways.settings.compost.enabled", ResourceLocation.withDefaultNamespace("composter"), true);
    /** The seeds kept back from composting, of every kind without a reserve of its own. */
    static final Schema.Setting.Count DEFAULT = new Schema.Setting.Count("default_reserve",
        "folkways.settings.compost.default_reserve", ResourceLocation.withDefaultNamespace("wheat_seeds"), 0,
        MAX_RESERVE, DEFAULT_RESERVE);
    static final Schema SCHEMA = new Schema(List.of(ENABLED, DEFAULT));
    private static final String OVERRIDES = "reserves";

    private CompostSettings() { }

    public static boolean enabled(Colony colony) {
        return Fronts.of(colony).settings(CompostContent.ID).flag(ENABLED.key());
    }

    public static int defaultReserve(Colony colony) {
        return clamp(Fronts.of(colony).settings(CompostContent.ID).count(DEFAULT.key()));
    }

    public static int reserve(Colony colony, Item item) {
        CompoundTag overrides = colony.kept(ID).getCompound(OVERRIDES);
        String key = BuiltInRegistries.ITEM.getKey(SeedReserves.canonical(item)).toString();
        return overrides.contains(key, Tag.TAG_INT) ? clamp(overrides.getInt(key)) : defaultReserve(colony);
    }

    public static boolean overridden(Colony colony, Item item) {
        CompoundTag overrides = colony.kept(ID).getCompound(OVERRIDES);
        return overrides.contains(BuiltInRegistries.ITEM.getKey(SeedReserves.canonical(item)).toString(), Tag.TAG_INT);
    }

    public static void enabled(Colony colony, boolean enabled) {
        ColonyFront.of(colony).setSetting(CompostContent.ID, ENABLED.key(), new ColonySettings.Value.Flag(enabled));
    }

    public static void defaultReserve(Colony colony, int count) {
        ColonyFront.of(colony).setSetting(CompostContent.ID, DEFAULT.key(), new ColonySettings.Value.Count(clamp(count)));
    }

    public static void reserve(Colony colony, Item item, int count) {
        CompoundTag tag = colony.kept(ID).copy();
        CompoundTag overrides = tag.getCompound(OVERRIDES).copy();
        overrides.putInt(BuiltInRegistries.ITEM.getKey(SeedReserves.canonical(item)).toString(), clamp(count));
        tag.put(OVERRIDES, overrides);
        colony.keep(ID, tag);
    }

    public static void reset(Colony colony, Item item) {
        CompoundTag tag = colony.kept(ID).copy();
        CompoundTag overrides = tag.getCompound(OVERRIDES).copy();
        clear(overrides, item);
        tag.put(OVERRIDES, overrides);
        colony.keep(ID, tag);
    }

    /** Only seeds in real stores/packs or configured/current crops appear, never the entire catalogue. */
    public static List<Item> discovered(Colony colony, MinecraftServer server) {
        Set<Item> catalogue = CropSeeds.catalogue(server.overworld(), BlockPos.ZERO);
        Set<Item> found = new LinkedHashSet<>();
        var snapshot = colony.inventory().snapshot();
        if (!snapshot.coverage().complete()) {
            colony.inventory().ensureObserved();
        }
        snapshot.stores().values().stream().filter(store -> store.usability().usable())
            .flatMap(store -> store.entries().stream()).map(entry -> entry.prototype().getItem())
            .filter(catalogue::contains).forEach(found::add);
        snapshot.residents().values().forEach(resident -> inspect(resident.pack(), catalogue, found));
        for (var level : server.getAllLevels()) {
            for (var sowing : CropSeeds.sowing(colony, level)) {
                catalogue.stream().filter(item -> Goods.matches(item.getDefaultInstance(), sowing.seed())).forEach(found::add);
            }
            for (var zone : Fronts.of(colony).zonesIn(level)) {
                if (!zone.delegation().equals(FarmingContent.PLOT)) { continue; }
                found.addAll(CropSeeds.forCrop(level, zone.cells().stream().findFirst().orElse(BlockPos.ZERO),
                    zone.settings().choice(FarmingContent.CROP)));
            }
        }
        return found.stream().map(SeedReserves::canonical).distinct()
            .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).toList();
    }

    private static void inspect(List<SlotSnapshot> inventory, Set<Item> catalogue, Set<Item> found) {
        for (var slot : inventory) {
            var stack = slot.stackCopy();
            if (!stack.isEmpty() && catalogue.contains(stack.getItem())) { found.add(stack.getItem()); }
        }
    }

    private static void clear(CompoundTag overrides, Item item) {
        overrides.remove(BuiltInRegistries.ITEM.getKey(SeedReserves.canonical(item)).toString());
    }

    private static int clamp(int count) { return Math.clamp(count, 0, MAX_RESERVE); }
}
