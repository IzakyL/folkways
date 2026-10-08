package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.engine.plan.Tour;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

final class Runners {

    private final Map<UUID, BodyRunner> byResident = new LinkedHashMap<>();

    private final Consumer<UUID> arrived;

    Runners(Consumer<UUID> arrived) {
        this.arrived = arrived;
    }

    BodyRunner of(UUID resident) {
        BodyRunner had = byResident.get(resident);
        if (had != null) {
            return had;
        }
        BodyRunner fresh = new BodyRunner(resident);
        byResident.put(resident, fresh);
        arrived.accept(resident);
        return fresh;
    }

    Collection<BodyRunner> all() {
        return byResident.values();
    }

    void follow(Map<UUID, Tour> tours) {
        for (Map.Entry<UUID, Tour> given : tours.entrySet()) {
            of(given.getKey()).follow(given.getValue().nodes());
        }
        for (BodyRunner runner : byResident.values()) {
            if (!tours.containsKey(runner.resident())) {
                runner.follow(List.of());
            }
        }
    }

    Map<UUID, UUID> inHand() {
        Map<UUID, UUID> held = new LinkedHashMap<>();
        for (BodyRunner runner : byResident.values()) {
            runner.holding().ifPresent(node -> held.put(runner.resident(), node));
        }
        return Map.copyOf(held);
    }

    void letGo(ColonyLabor labor, Set<UUID> nodes) {
        for (BodyRunner runner : byResident.values()) {
            if (runner.holding().filter(nodes::contains).isPresent()) {
                runner.drop(labor);
            }
        }
    }

    void close(ColonyLabor labor) {
        byResident.values().forEach(runner -> runner.abandon(labor));
        byResident.clear();
    }

    void away(ColonyLabor labor, Set<UUID> here, long now) {
        for (BodyRunner runner : byResident.values()) {
            if (here.contains(runner.resident())) {
                runner.here(labor);
            } else {
                runner.away(labor, now);
            }
        }
    }

    void reconcile(ColonyLabor labor, Set<UUID> onTheRoster) {
        byResident.entrySet().removeIf(entry -> {
            if (onTheRoster.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().abandon(labor);
            labor.gone(entry.getKey());
            return true;
        });
    }
}
