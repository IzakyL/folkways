package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.work.NodeState;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * A colony's labor counted as it stands this tick, for statistics: read-only, changes nothing.
 *
 * submitted   nodes that have come onto the plan since the labor began, ever
 * phases      the nodes on the plan now, by phase
 * planned     nodes laid on some body's route now
 * unassigned  nodes the last schedule left without a body, by reason
 * missing     goods the last plan found short, by item, summed over every shortfall
 * standing    the colony's bodies by what each is up to: idle, stalled, walking, working, holding, away,
 *             riding, or urge/&lt;node kind&gt; for a body's own business (eating, sleeping, mending...)
 */
public record LaborCensus(long submitted, Map<NodeState, Integer> phases, int planned,
        Map<String, Integer> unassigned, Map<String, Long> missing, Map<String, Integer> standing) {

    public static Optional<LaborCensus> of(ServerLevel level, UUID colony) {
        return Labor.site(colony, level.dimension()).map(labor -> labor.census(level));
    }
}
