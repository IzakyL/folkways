package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
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

    boolean publish(ResourceLocation owner, ResourceKey<Level> dimension, List<WorldPos> stores) {
        Map<ResourceLocation, List<WorldPos>> here =
            published.computeIfAbsent(dimension, key -> new LinkedHashMap<>());
        List<WorldPos> had = stores.isEmpty() ? here.remove(owner) : here.put(owner, stores);
        return !(had == null ? stores.isEmpty() : had.equals(stores));
    }

    List<WorldPos> in(ResourceKey<Level> dimension) {
        Set<WorldPos> found = new LinkedHashSet<>();
        published.getOrDefault(dimension, Map.of()).values().forEach(found::addAll);
        return List.copyOf(found);
    }

    void forget(ResourceLocation owner) {
        published.values().forEach(here -> here.remove(owner));
    }

    void clear() {
        published.clear();
    }
}
