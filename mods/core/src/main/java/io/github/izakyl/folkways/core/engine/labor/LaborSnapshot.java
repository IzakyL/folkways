package io.github.izakyl.folkways.core.engine.labor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

// A non-draining read: chat diagnostics must not consume the server log's heartbeat counters.
public record LaborSnapshot(LaborCensus census, long solvingMillis, long scheduleMillis,
                            long limitedSchedules, Map<String, Integer> shortages, List<String> examples) {
    public LaborSnapshot {
        shortages = Map.copyOf(shortages);
        examples = List.copyOf(examples);
    }

    public static Optional<LaborSnapshot> of(ServerLevel level, UUID colony) {
        return Labor.site(colony, level.dimension()).map(labor -> labor.snapshot(level));
    }
}
