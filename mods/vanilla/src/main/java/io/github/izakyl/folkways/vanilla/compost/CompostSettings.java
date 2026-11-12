package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.front.api.Fronts;
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

/** Per-colony compost preferences, saved with the colony rather than a player's book. */
public final class CompostSettings {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "compost_settings");
    public static final int DEFAULT_RESERVE = 64;
    public static final int MAX_RESERVE = 1_000_000;
    private static final String ENABLED = "enabled";
    private static final String DEFAULT = "default_reserve";
    private static final String OVERRIDES = "reserves";

    private CompostSettings() { }

    public static boolean enabled(Colony colony) {
        CompoundTag tag = colony.kept(ID);
        return !tag.contains(ENABLED, Tag.TAG_BYTE) || tag.getBoolean(ENABLED);
    }

    public static int defaultReserve(Colony colony) {
        CompoundTag tag = colony.kept(ID);
        return tag.contains(DEFAULT, Tag.TAG_INT) ? clamp(tag.getInt(DEFAULT)) : DEFAULT_RESERVE;
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
        CompoundTag tag = colony.kept(ID).copy();
        tag.putBoolean(ENABLED, enabled);
        colony.keep(ID, tag);
    }

    public static void defaultReserve(Colony colony, int count) {
        CompoundTag tag = colony.kept(ID).copy();
        tag.putInt(DEFAULT, clamp(count));
        colony.keep(ID, tag);
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
