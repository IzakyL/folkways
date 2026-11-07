package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

// Where the colony's work may take goods from and put them: whatever its owners offer as stores, by dimension.
final class StorePlaces {

    private final Map<ResourceKey<Level>, Map<ResourceLocation, List<WorldPos>>> published = new LinkedHashMap<>();

    private final Map<ResourceKey<Level>, List<WorldPos>> combined = new LinkedHashMap<>();
    private final Map<ResourceKey<Level>, Set<WorldPos>> unique = new LinkedHashMap<>();

    boolean publish(ResourceLocation owner, ResourceKey<Level> dimension, List<WorldPos> stores) {
        Map<ResourceLocation, List<WorldPos>> here =
            published.computeIfAbsent(dimension, key -> new LinkedHashMap<>());
        List<WorldPos> had = stores.isEmpty() ? here.remove(owner) : here.put(owner, stores);
        boolean changed = !(had == null ? stores.isEmpty() : had.equals(stores));
        if (changed) {
            combine(dimension);
        }
        return changed;
    }

    Set<ResourceKey<Level>> dimensions() {
        return Set.copyOf(published.keySet());
    }

    List<WorldPos> in(ResourceKey<Level> dimension) {
        return combined.getOrDefault(dimension, List.of());
    }

    Set<WorldPos> setIn(ResourceKey<Level> dimension) {
        return unique.getOrDefault(dimension, Set.of());
    }

    private void combine(ResourceKey<Level> dimension) {
        Set<WorldPos> found = new LinkedHashSet<>();
        published.getOrDefault(dimension, Map.of()).values().forEach(found::addAll);
        combined.put(dimension, List.copyOf(found));
        unique.put(dimension, Set.copyOf(found));
    }

    void forget(ResourceLocation owner) {
        published.forEach((dimension, here) -> {
            if (here.remove(owner) != null) {
                combine(dimension);
            }
        });
    }

    void clear() {
        published.clear();
        combined.clear();
        unique.clear();
    }
}
