package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class Workshops {

    private final Map<ResourceKey<Level>, Map<ResourceLocation, List<Workshop>>> published =
        new LinkedHashMap<>();

    boolean publish(ResourceLocation owner, ResourceKey<Level> dimension, List<Workshop> workshops) {
        Map<ResourceLocation, List<Workshop>> here =
            published.computeIfAbsent(dimension, key -> new LinkedHashMap<>());
        List<Workshop> had = workshops.isEmpty() ? here.remove(owner) : here.put(owner, workshops);
        return !(had == null ? workshops.isEmpty() : had.equals(workshops));
    }

    List<Workshop> in(ResourceKey<Level> dimension) {
        List<Workshop> found = new ArrayList<>();
        published.getOrDefault(dimension, Map.of()).values().forEach(found::addAll);
        return List.copyOf(found);
    }

    Set<BlockPos> sitesIn(ServerLevel level) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (Workshop workshop : in(level.dimension())) {
            if (workshop.site().where().in(level)) {
                found.add(workshop.site().where().block(level));
            }
        }
        return Set.copyOf(found);
    }

    void forget(ResourceLocation owner) {
        published.values().forEach(here -> here.remove(owner));
    }

    void clear() {
        published.clear();
    }
}
