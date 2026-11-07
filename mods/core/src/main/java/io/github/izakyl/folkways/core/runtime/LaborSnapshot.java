package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.plan.internal.Claim;
import io.github.izakyl.folkways.core.work.api.ReservedInput;
import io.github.izakyl.folkways.core.work.internal.HoldingRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

// A non-draining read: chat diagnostics must not consume the server log's heartbeat counters.
public record LaborSnapshot(LaborCensus census, long solvingMillis, long scheduleMillis,
                            long limitedSchedules, Map<String, Integer> shortages, List<String> examples,
                            List<Suspended> suspended) {
    public record Suspended(HoldingRegistry.SuspendedWork work, List<ReservedInput> inputs, List<Claim> claims) {
        public Suspended {
            inputs = List.copyOf(inputs);
            claims = List.copyOf(claims);
        }
    }

    public LaborSnapshot {
        shortages = Map.copyOf(shortages);
        examples = List.copyOf(examples);
        suspended = List.copyOf(suspended);
    }

    public static Optional<LaborSnapshot> of(ServerLevel level, UUID colony) {
        return Labor.site(colony, level.dimension()).map(labor -> labor.snapshot(level));
    }
}
