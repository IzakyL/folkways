package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

final class Rules {

    private final Map<ResourceKey<Level>, Map<ResourceLocation, List<StoreRule>>> published = new LinkedHashMap<>();

    boolean publish(ResourceLocation owner, ResourceKey<Level> dimension, List<StoreRule> rules) {
        Map<ResourceLocation, List<StoreRule>> here =
            published.computeIfAbsent(dimension, key -> new LinkedHashMap<>());
        List<StoreRule> had = rules.isEmpty() ? here.remove(owner) : here.put(owner, rules);
        return !(had == null ? rules.isEmpty() : had.equals(rules));
    }

    Map<WorldPos, List<StoreRule>> in(ResourceKey<Level> dimension) {
        return byStore(published.getOrDefault(dimension, Map.of()).values());
    }

    Map<WorldPos, List<StoreRule>> all() {
        List<List<StoreRule>> every = new ArrayList<>();
        published.values().forEach(here -> every.addAll(here.values()));
        return byStore(every);
    }

    private static Map<WorldPos, List<StoreRule>> byStore(Iterable<List<StoreRule>> owners) {
        Map<WorldPos, List<StoreRule>> found = new LinkedHashMap<>();
        for (List<StoreRule> rules : owners) {
            for (StoreRule rule : rules) {
                found.computeIfAbsent(rule.where(), key -> new ArrayList<>()).add(rule);
            }
        }
        found.replaceAll((where, rules) -> List.copyOf(rules));
        return Map.copyOf(found);
    }

    void forget(ResourceLocation owner) {
        published.values().forEach(here -> here.remove(owner));
    }

    void clear() {
        published.clear();
    }
}
