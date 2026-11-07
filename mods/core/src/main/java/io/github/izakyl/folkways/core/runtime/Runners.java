package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.plan.internal.Tour;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.work.internal.WorkExecution;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;

final class Runners {

    private final Map<UUID, BodyRunner> byResident = new LinkedHashMap<>();

    private final Consumer<UUID> arrived;
    private final WorkExecution execution;

    Runners(WorkExecution execution, Consumer<UUID> arrived) {
        this.arrived = arrived;
        this.execution = execution;
    }

    BodyRunner of(UUID resident) {
        BodyRunner had = byResident.get(resident);
        if (had != null) {
            return had;
        }
        BodyRunner fresh = new BodyRunner(resident, execution);
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

    void letGo(Set<UUID> nodes) {
        for (BodyRunner runner : byResident.values()) {
            runner.cancelNodes(nodes);
        }
    }

    void checkAbsent(ServerLevel level, Set<UUID> present) {
        for (BodyRunner runner : byResident.values()) {
            if (!present.contains(runner.resident())) {
                runner.checkAbsent(level);
            }
        }
    }

    void close() {
        byResident.values().forEach(runner -> runner.abandon());
        byResident.clear();
    }

    // A body carried off out of the world is neither here nor away: its runner keeps everything it had and waits.
    void away(Set<UUID> here, Set<UUID> carriedOff, long now) {
        for (BodyRunner runner : byResident.values()) {
            if (carriedOff.contains(runner.resident())) {
                continue;
            }
            if (here.contains(runner.resident())) {
                runner.here();
            } else {
                runner.away(now);
            }
        }
    }

    void reconcile(ColonyLabor labor, Set<UUID> onTheRoster) {
        byResident.entrySet().removeIf(entry -> {
            if (onTheRoster.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().abandon();
            labor.gone(entry.getKey());
            return true;
        });
    }
}
