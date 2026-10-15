package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.Ledger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Optional harvest actions stay out of the ordinary crop lifecycle until a plot selects one. */
public final class HarvestMethods {
    public static final ResourceLocation PICK = ResourceLocation.fromNamespaceAndPath("folkways", "pick");
    private static volatile Map<ResourceLocation, HarvestMethod> methods = Map.of();

    private HarvestMethods() {
    }

    public static synchronized void register(ResourceLocation id, HarvestMethod method) {
        Ledger.writing("farm harvest methods");
        if (id.equals(PICK) || methods.containsKey(id)) throw new IllegalArgumentException("harvest method " + id);
        Map<ResourceLocation, HarvestMethod> next = new LinkedHashMap<>(methods);
        next.put(Objects.requireNonNull(id), Objects.requireNonNull(method));
        methods = java.util.Collections.unmodifiableMap(next);
    }

    public static List<ResourceLocation> choices() {
        List<ResourceLocation> choices = new ArrayList<>();
        choices.add(PICK);
        choices.addAll(methods.keySet());
        return List.copyOf(choices);
    }

    public static ResourceLocation idOf(HarvestMethod method) {
        return methods.entrySet().stream().filter(entry -> entry.getValue() == method)
            .map(Map.Entry::getKey).findFirst().orElse(PICK);
    }

    public static Optional<HarvestMethod> of(ResourceLocation id) {
        return id == null ? Optional.empty() : Optional.ofNullable(methods.get(id));
    }
}
