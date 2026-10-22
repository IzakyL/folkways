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

    // Every runner follows its route as laid now, from its start: work it let go of since is on it again.
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

    // 有没有一只手在原地干等太久：它队首的活一直拿不到（多半在等别人路线里还没开工的节点）。
    boolean stalled(int ticks) {
        for (BodyRunner runner : byResident.values()) {
            if (runner.stalledTicks() >= ticks) {
                return true;
            }
        }
        return false;
    }

    void letGo(ColonyLabor labor, Set<UUID> nodes) {
        for (BodyRunner runner : byResident.values()) {
            if (runner.holding().filter(nodes::contains).isPresent()) {
                runner.drop(labor, "replaced");
            }
        }
    }

    void close(ColonyLabor labor) {
        byResident.values().forEach(runner -> runner.abandon(labor));
        byResident.clear();
    }

    // A body carried off out of the world is neither here nor away: its runner keeps everything it had and waits.
    void away(ColonyLabor labor, Set<UUID> here, Set<UUID> carriedOff, long now) {
        for (BodyRunner runner : byResident.values()) {
            if (carriedOff.contains(runner.resident())) {
                continue;
            }
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
