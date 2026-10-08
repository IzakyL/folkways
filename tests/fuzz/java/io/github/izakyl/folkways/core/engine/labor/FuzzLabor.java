package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.engine.plan.Diagnosis;
import io.github.izakyl.folkways.core.engine.plan.Refused;
import io.github.izakyl.folkways.core.engine.plan.Shortfall;
import io.github.izakyl.folkways.core.engine.plan.Unassigned;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

/**
 * What a colony's labor knows about work the live fuzzers submitted, read as its own readout reads it: what has
 * been grown for a goal, where each of those nodes stands, and whether the last plan names a reason the goal
 * waits (a shortfall feeding any node grown for it, a reason one went unassigned, a station that turned it away).
 * Work that waits must say why; this is how a fuzzer asks.
 */
public final class FuzzLabor {

    private static final Field GRAPH = field("graph");
    private static final Field PREDECESSORS = field("predecessors");

    private FuzzLabor() {
    }

    public static Optional<NodeState> state(ServerLevel level, UUID colony, UUID node) {
        return Labor.site(colony, level.dimension()).flatMap(labor -> labor.state(node));
    }

    public static Diagnosis diagnosis(ServerLevel level, UUID colony) {
        return Labor.site(colony, level.dimension()).map(ColonyLabor::diagnosis).orElse(Diagnosis.NOTHING);
    }

    /** The goal's root and every node on the graph that must finish before it, however deep. */
    public static Set<UUID> grown(ServerLevel level, UUID colony, UUID root) {
        Set<UUID> found = new LinkedHashSet<>();
        Optional<ColonyLabor> labor = Labor.site(colony, level.dimension());
        if (labor.isEmpty()) {
            return found;
        }
        Map<UUID, Set<UUID>> before = read(PREDECESSORS, labor.get());
        Deque<UUID> due = new ArrayDeque<>(List.of(root));
        while (!due.isEmpty()) {
            UUID one = due.pop();
            if (found.add(one)) {
                due.addAll(before.getOrDefault(one, Set.of()));
            }
        }
        return found;
    }

    /** Each of these nodes on the graph, by kind and phase: what the goal is waiting on, and how it moves. */
    public static List<String> phases(ServerLevel level, UUID colony, Set<UUID> nodes) {
        List<String> out = new ArrayList<>();
        Labor.site(colony, level.dimension()).ifPresent(labor -> {
            Map<UUID, Vertex> graph = read(GRAPH, labor);
            for (UUID id : nodes) {
                Vertex vertex = graph.get(id);
                if (vertex != null) {
                    out.add(vertex.node().getClass().getSimpleName() + " " + id.toString().substring(0, 8) + " "
                        + labor.state(id).map(Enum::name).orElse("?"));
                }
            }
        });
        return out;
    }

    /** The reasons the last plan gives for any of these nodes waiting; empty when it gives none. */
    public static List<String> why(ServerLevel level, UUID colony, Set<UUID> nodes) {
        return why(level, colony, nodes, Set.of());
    }

    /**
     * The reasons the last plan gives for a goal waiting: anything it says about the nodes grown for it, and any
     * shortfall or refusal of goods the goal's making could call for whose consumer is no longer on the graph
     * (a growth that failed is taken off whole, so its gaps name nodes that never landed). Empty when it gives none.
     */
    public static List<String> why(ServerLevel level, UUID colony, Set<UUID> grown, Set<Item> wants) {
        Diagnosis diagnosis = diagnosis(level, colony);
        Map<UUID, Vertex> graph = Labor.site(colony, level.dimension())
            .map(labor -> FuzzLabor.<Map<UUID, Vertex>>read(GRAPH, labor)).orElse(Map.of());
        List<String> found = new ArrayList<>();
        for (Unassigned unassigned : diagnosis.unassigned()) {
            if (grown.contains(unassigned.node())) {
                found.add("unassigned " + unassigned.why());
            }
        }
        for (Shortfall shortfall : diagnosis.shortfalls()) {
            boolean ours = grown.contains(shortfall.consumer()) || shortfall.holes().stream().anyMatch(grown::contains);
            boolean orphan = !graph.containsKey(shortfall.consumer())
                && Goods.members(shortfall.what()).stream().anyMatch(wants::contains);
            if (ours || orphan) {
                found.add("short of " + shortfall.missing() + " " + shortfall.what().describe() + ": " + shortfall.why()
                    + (ours ? "" : " (for a growth taken off)"));
            }
        }
        for (Refused refused : diagnosis.refused()) {
            if (refused.asked().map(spec -> Goods.members(spec).stream().anyMatch(wants::contains)).orElse(false)) {
                found.add("refused " + refused.why().translationKey());
            }
        }
        return found;
    }

    /** The labor's own one-line readout: what it holds, plans and runs. */
    public static String heartbeat(ServerLevel level, UUID colony) {
        return Labor.site(colony, level.dimension()).map(ColonyLabor::heartbeat).orElse("no labor");
    }

    /** Everything the last plan says is wrong, for a failure's detail. */
    public static String told(ServerLevel level, UUID colony) {
        Diagnosis diagnosis = diagnosis(level, colony);
        Collection<String> stalls = Labor.site(colony, level.dimension()).map(ColonyLabor::stalls).orElse(List.of());
        return "shortfalls " + diagnosis.shortfalls().stream()
            .map(one -> one.missing() + " " + one.what().describe() + " " + one.why()).toList()
            + ", unassigned " + diagnosis.unassigned().stream().map(Unassigned::why).toList()
            + ", refused " + diagnosis.refused().stream().map(one -> one.why().translationKey()).toList()
            + ", stalls " + stalls;
    }

    private static Field field(String name) {
        try {
            Field found = ColonyLabor.class.getDeclaredField(name);
            found.setAccessible(true);
            return found;
        } catch (NoSuchFieldException missing) {
            throw new IllegalStateException("ColonyLabor has no " + name + " to read", missing);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T read(Field field, ColonyLabor labor) {
        try {
            return (T) field.get(labor);
        } catch (IllegalAccessException denied) {
            throw new IllegalStateException(denied);
        }
    }
}
