package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.plan.api.Grown;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.minecraft.resources.ResourceLocation;

// Cross-cutting prerequisites, such as a shared tool loan, can wrap work without coupling its producer
// to the plugin that supplies them. Every wrapper preserves the original completion IDs and material budget.
public final class WorkDecorators {

    private static BiConsumer<ResourceLocation, BiFunction<Colony, Grown, Grown>> registration;

    private WorkDecorators() {
    }

    public static void install(
            BiConsumer<ResourceLocation, BiFunction<Colony, Grown, Grown>> installed) {
        registration = Objects.requireNonNull(installed);
    }

    public static void register(ResourceLocation owner, BiFunction<Colony, Grown, Grown> decorator) {
        registration.accept(owner, decorator);
    }
}
