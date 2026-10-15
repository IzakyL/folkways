package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.Ledger;
import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.resources.ResourceLocation;

// Cross-cutting prerequisites, such as a shared tool loan, can wrap work without coupling its producer
// to the plugin that supplies them. Every wrapper preserves the original completion IDs and material budget.
public final class WorkDecorators {
    private static volatile Map<ResourceLocation, BiFunction<Colony, Grown, Grown>> decorators = Map.of();

    private WorkDecorators() {
    }

    public static synchronized void register(ResourceLocation owner, BiFunction<Colony, Grown, Grown> decorator) {
        Ledger.writing(owner);
        Map<ResourceLocation, BiFunction<Colony, Grown, Grown>> next = new LinkedHashMap<>(decorators);
        if (next.putIfAbsent(Objects.requireNonNull(owner), Objects.requireNonNull(decorator)) != null) {
            throw new IllegalArgumentException("work decorator already registered: " + owner);
        }
        decorators = java.util.Collections.unmodifiableMap(next);
    }

    public static Grown apply(Colony colony, Grown work) {
        Grown result = work;
        for (var decorator : decorators.values()) {
            result = Objects.requireNonNull(decorator.apply(colony, result));
            if (!result.completions().equals(work.completions())) {
                throw new IllegalArgumentException("a work decorator changed completion IDs");
            }
        }
        return result;
    }

    public static Workshop apply(Colony colony, Workshop workshop) {
        return decorators.isEmpty() ? workshop : new Decorated(colony, workshop);
    }

    private record Decorated(Colony colony, Workshop original) implements Workshop {
        public UUID key() { return original.key(); }
        public ResourceLocation id() { return original.id(); }
        public WorkSite site() { return original.site(); }
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            return original.options(intent, graph).stream().map(change -> new Refinement.Change(
                apply(colony, change.replacement()), change.links(), change.workerGroups(), change.holds())).toList();
        }
    }
}
