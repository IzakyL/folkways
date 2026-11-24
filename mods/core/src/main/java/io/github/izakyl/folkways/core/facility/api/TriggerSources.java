package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** Server-thread sources of arguments for conditional triggers. */
public final class TriggerSources {

    @FunctionalInterface
    public interface Source {
        Optional<Map<String, Value>> arguments(ServerLevel level, Colony colony, FacilityDefinition facility, String routine);
    }

    private static final Map<ResourceLocation, Source> SOURCES = new ConcurrentHashMap<>();

    private TriggerSources() {
    }

    public static void register(ResourceLocation id, Source source) {
        if (SOURCES.putIfAbsent(id, source) != null) {
            throw new IllegalStateException(id + " already supplies trigger arguments");
        }
    }

    public static Optional<Source> of(ResourceLocation id) {
        return Optional.ofNullable(SOURCES.get(id));
    }
}
