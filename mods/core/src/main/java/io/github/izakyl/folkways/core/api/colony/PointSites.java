package io.github.izakyl.folkways.core.api.colony;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** The native block predicates of ledger-owned point sites, declared before colonies load. */
public final class PointSites {
    private static volatile Map<ResourceLocation, BiPredicate<Level, BlockPos>> sites = Map.of();
    private PointSites() { }

    public static synchronized void register(ResourceLocation owner, BiPredicate<Level, BlockPos> accepts) {
        Objects.requireNonNull(owner); Objects.requireNonNull(accepts);
        if (sites.containsKey(owner)) throw new IllegalStateException(owner + " already declares point sites");
        Map<ResourceLocation, BiPredicate<Level, BlockPos>> next = new LinkedHashMap<>(sites);
        next.put(owner, accepts); sites = Map.copyOf(next);
    }

    public static boolean accepts(ResourceLocation owner, Level level, BlockPos cell) {
        var accepts = sites.get(owner);
        return accepts != null && accepts.test(level, cell);
    }
}
