package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Synchronous test drivers advance the observer, then finish its queued work, before asking for stock. */
public final class InventoryObservations {
    private static final int STEPS = 1_000;

    private InventoryObservations() {
    }

    // Every enrolled resident whose body is loaded is present.
    public static void publish(Colony colony, ServerLevel level) {
        publish(data(colony, level), level);
    }

    public static void publish(ColonyData colony, ServerLevel level) {
        Map<UUID, Body> present = new LinkedHashMap<>();
        colony.works().residents().forEach(resident -> {
            var entity = level.getEntity(resident.id());
            if (entity != null && !entity.isRemoved()) {
                Bodies.of(entity).ifPresent(body -> present.put(resident.id(), body));
            }
        });
        publish(colony, level, present, STEPS);
    }

    public static void publish(Colony colony, ServerLevel level, Map<UUID, Body> present) {
        publish(data(colony, level), level, present, STEPS);
    }

    public static void publish(ColonyData colony, ServerLevel level, Map<UUID, Body> present, int steps) {
        colony.inventory().advance(level, colony.works(), present);
        work(colony.inventory(), level.getServer(), steps);
    }

    // Finishes the index's queued work without a new observation.
    public static void work(InventoryIndex index, MinecraftServer server, int steps) {
        index.work(server, steps);
    }

    private static ColonyData data(Colony colony, ServerLevel level) {
        return ColonyData.find(level.getServer(), colony.id()).orElseThrow();
    }
}
