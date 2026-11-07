package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.Vertex;
import io.github.izakyl.folkways.core.plan.internal.WeaveView;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.ReservedInput;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

// The plan's distinct draws add up; repeated readings of this exported snapshot do not.
final class InputReservations {
    private InputReservations() { }

    private record Key(UUID node, Optional<WorldPos> store, Optional<UUID> resident, ItemSpec goods,
                       Optional<UUID> producer) { }

    static List<ReservedInput> read(WeaveView graph, Function<UUID, NodeState> phases,
                                    Function<UUID, UUID> workers) {
        Map<Key, Long> inputs = new LinkedHashMap<>();
        for (Vertex vertex : graph.vertices().values()) {
            NodeState state = phases.apply(vertex.id());
            // SETTLING follows commit: these inputs are already spent, while the real products await hand-off.
            if (state.terminal() || state == NodeState.SETTLING) {
                continue;
            }
            for (var draw : vertex.plan().draws()) {
                if (draw.count() <= 0) {
                    continue;
                }
                Optional<WorldPos> store = draw.from().map(stash -> stash.pos());
                Optional<UUID> resident = draw.resident();
                if (draw.producer().isPresent()) {
                    UUID producer = draw.producer().get();
                    NodeState stage = phases.apply(producer);
                    Vertex source = graph.vertex(producer);
                    boolean partial = stage == NodeState.WORKING && source != null
                        && source.node().producesBeforeSettlement();
                    if (stage == NodeState.SETTLING || stage == NodeState.DONE || partial) {
                        resident = Optional.ofNullable(workers.apply(producer));
                    }
                }
                inputs.merge(new Key(vertex.id(), store, resident, draw.spec(), draw.producer()), draw.count(), Math::addExact);
            }
        }
        return inputs.entrySet().stream().map(entry -> new ReservedInput(entry.getKey().node(),
            entry.getKey().store(), entry.getKey().resident(), entry.getKey().goods(), entry.getValue(),
            entry.getKey().producer())).toList();
    }
}
