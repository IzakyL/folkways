package io.github.izakyl.folkways.front.ui.panel;

import io.github.izakyl.folkways.front.api.ui.Bay;
import io.github.izakyl.folkways.front.api.ui.Inlay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

public final class Inlays {

    public record Entry(ResourceLocation owner, Supplier<? extends Inlay> factory) {
    }

    private static final Map<ResourceLocation, Map<ResourceLocation, Supplier<? extends Inlay>>> BY_BAY =
        new LinkedHashMap<>();

    private Inlays() {
    }

    public static void register(Bay bay, ResourceLocation owner, Supplier<? extends Inlay> factory) {
        Map<ResourceLocation, Supplier<? extends Inlay>> filled =
            BY_BAY.computeIfAbsent(bay.id(), key -> new LinkedHashMap<>());
        if (filled.putIfAbsent(owner, factory) != null) {
            throw new IllegalStateException(owner + " already fills a place in " + bay.id());
        }
    }

    public static List<Entry> in(Bay bay) {
        Map<ResourceLocation, Supplier<? extends Inlay>> filled = BY_BAY.get(bay.id());
        if (filled == null) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>(filled.size());
        filled.forEach((owner, factory) -> entries.add(new Entry(owner, factory)));
        return List.copyOf(entries);
    }
}
