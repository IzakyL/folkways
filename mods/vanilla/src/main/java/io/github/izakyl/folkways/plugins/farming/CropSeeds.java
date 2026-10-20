package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelReader;

/** The actual farming catalogue and outstanding sowing requests, without optional-mod tag guesses. */
public final class CropSeeds {
    public record Sowing(UUID node, ItemSpec seed, long count) {
    }

    private static final java.util.Map<net.minecraft.resources.ResourceLocation, java.util.function.Supplier<List<Item>>> PURPOSES = new java.util.LinkedHashMap<>();

    // Ordered alternatives have one shared normal-stack reserve; the first is retained first.
    public static void purpose(net.minecraft.resources.ResourceLocation owner, java.util.function.Supplier<List<Item>> alternatives) {
        PURPOSES.put(owner, alternatives);
    }

    public static List<List<Item>> purposes(Set<Item> seeds) {
        var remaining = new LinkedHashSet<>(seeds);
        var result = new java.util.ArrayList<List<Item>>();
        PURPOSES.values().forEach(source -> {
            List<Item> group = source.get().stream().distinct().filter(remaining::contains).toList();
            if (!group.isEmpty()) { result.add(group); remaining.removeAll(group); }
        });
        remaining.stream().sorted(java.util.Comparator.comparing(item -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()))
            .forEach(item -> result.add(List.of(item)));
        return List.copyOf(result);
    }

    private CropSeeds() {
    }

    public static Set<Item> catalogue(LevelReader level, BlockPos at) {
        Set<Item> seeds = new LinkedHashSet<>();
        for (var id : Crop.sowable()) {
            Crop.of(level, at, id).ifPresent(crop -> {
                seeds.add(crop.seed());
                crop.behavior().ifPresent(behavior -> seeds.addAll(behavior.seeds()));
            });
        }
        return java.util.Collections.unmodifiableSet(seeds);
    }

    public static List<Sowing> sowing(Colony colony, ServerLevel level) {
        return colony.service(FarmingContent.ID, FarmPresence.class)
            .map(presence -> presence.sowingPlans(level)).orElseGet(List::of);
    }
}
