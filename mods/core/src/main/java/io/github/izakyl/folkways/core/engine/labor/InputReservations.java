package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.batch.BatchSession;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.WeaveView;
import java.util.LinkedHashMap;
import java.util.ArrayList;
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
        return read(graph, phases, workers, node -> graph.vertex(node).plan().draws());
    }

    static List<ReservedInput> read(WeaveView graph, Function<UUID, NodeState> phases,
                                    Function<UUID, UUID> workers, Function<UUID, List<Placement.Draw>> draws) {
        return read(graph, phases, workers, draws, node -> List.of());
    }

    private static final class Produced {
        final ItemSpec goods;
        long left;
        Produced(ReservedInput hold) { goods = hold.goods(); left = hold.count(); }
    }

    static List<ReservedInput> read(WeaveView graph, Function<UUID, NodeState> phases,
                                    Function<UUID, UUID> workers, Function<UUID, List<Placement.Draw>> draws,
                                    Function<UUID, List<ReservedInput>> outputs) {
        Map<Key, Long> inputs = new LinkedHashMap<>();
        Map<UUID, List<Produced>> produced = new LinkedHashMap<>();
        for (Vertex vertex : graph.vertices().values()) {
            NodeState state = phases.apply(vertex.id());
            // SETTLING follows commit: these inputs are already spent, while the real products await hand-off.
            if (state.terminal() || state == NodeState.SETTLING) continue;
            for (var draw : draws.apply(vertex.id())) {
                if (draw.count() <= 0) continue;
                Optional<WorldPos> store = draw.from().map(stash -> stash.pos());
                Optional<UUID> resident = draw.resident();
                long count = draw.count();
                if (draw.producer().isPresent()) {
                    UUID producer = draw.producer().get();
                    NodeState stage = phases.apply(producer);
                    Vertex source = graph.vertex(producer);
                    boolean partial = stage == NodeState.WORKING && source != null
                        && source.node().spec().workload() instanceof Workload.Within
                        && !(source.node().spec().workload() instanceof BatchSession);
                    if (stage == NodeState.WORKING && source != null
                            && source.node().spec().workload() instanceof BatchSession) {
                        // The real early output stays protected by its parent at its actual location. Only
                        // the as-yet-unproduced part remains a pending downstream claim, never a second hold.
                        List<Produced> available = produced.computeIfAbsent(producer,
                            id -> outputs.apply(id).stream().map(Produced::new).toList());
                        for (Produced product : available) {
                            if (count <= 0 || !Goods.overlap(product.goods, draw.spec())) continue;
                            long covered = Math.min(count, product.left);
                            product.left -= covered;
                            count -= covered;
                        }
                    }
                    if (stage == NodeState.SETTLING || stage == NodeState.DONE || partial) {
                        resident = Optional.ofNullable(workers.apply(producer));
                    }
                } else if (store.isEmpty() && resident.isEmpty()) {
                    // Legacy placements still name the consumer's pack; explicit Cargo always names its true owner.
                    resident = Optional.ofNullable(workers.apply(vertex.id()));
                }
                if (count > 0) inputs.merge(new Key(vertex.id(), store, resident, draw.spec(), draw.producer()), count, Math::addExact);
            }
        }
        for (Vertex vertex : graph.vertices().values()) {
            if (phases.apply(vertex.id()) != NodeState.WORKING) continue;
            for (ReservedInput hold : outputs.apply(vertex.id())) {
                inputs.merge(new Key(hold.node(), hold.store(), hold.resident(), hold.goods(), hold.producer()),
                    hold.count(), Math::addExact);
            }
        }
        return inputs.entrySet().stream().map(entry -> new ReservedInput(entry.getKey().node(),
            entry.getKey().store(), entry.getKey().resident(), entry.getKey().goods(), entry.getValue(),
            entry.getKey().producer())).toList();
    }
}
