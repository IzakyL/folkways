package io.github.izakyl.folkways.core.facility.api.mechanism;

import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Link;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismGraph.Role;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan.EdgeBuilder;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan.NodeBuilder;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reads how a built mechanism works, for whoever must operate it: what turns what, which control commands which part,
 * and which way a turn reaches each actuator. Each integration describes its own parts through a {@link Provider}; this
 * class merges their answers, folds plain relays away and keeps the graph within its size limit. It also tells a copy
 * of the mechanism what to carry along and what to compare.
 *
 * <p>Server thread only.
 */
public final class Mechanisms {

    public interface Provider {

        /** Names the parts this integration knows among the scanned blocks and entities. */
        void describe(MechanismScan scan);

        /** Links parts once every provider has described them. */
        default void link(MechanismScan scan) {
        }

        /** Values that change as the part at `pos` works, keyed by a short name, added to `into`. */
        default void observe(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockEntity block,
                             Map<String, Object> into) {
        }

        /** Values that change as the entity moves or works, added to `into`. */
        default void observe(Entity entity, Map<String, Object> into) {
        }

        /** Whether the entity belongs to a mechanism, so a copy of the mechanism carries it along. */
        default boolean mechanical(Entity entity) {
            return false;
        }

        /** Whether a block without a block entity or redstone output is still part of a mechanism. */
        default boolean mechanical(BlockState state) {
            return false;
        }
    }

    private record Entry(ResourceLocation id, Provider provider) {
    }

    private static volatile List<Entry> providers = List.of();

    private Mechanisms() {
    }

    public static synchronized void register(ResourceLocation id, Provider provider) {
        Objects.requireNonNull(provider, "provider");
        if (providers.stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalStateException(id + " already describes mechanisms");
        }
        List<Entry> next = new ArrayList<>(providers);
        next.add(new Entry(id, provider));
        providers = List.copyOf(next);
    }

    public static MechanismGraph graph(MechanismScan scan) {
        for (Entry entry : providers) {
            entry.provider().describe(scan);
        }
        for (var cell : scan.cells()) {
            Controls.motion(scan.level(), cell.at()).forEach(motion -> {
                var node = scan.node(cell.storage()).role(Role.ACTUATOR);
                motion.readings().forEach((name, reading) ->
                    reading.read(scan.level()).ifPresent(value -> node.put(name, describe(value))));
            });
        }
        for (WorldSpaces.Frame frame : scan.frames()) {
            frame.bounds().ifPresent(bounds -> scan.node(frame).kind("structure").role(Role.MOVING)
                .put("structure", frame.id().toString())
                .put("size", List.of(bounds.getXSpan(), bounds.getYSpan(), bounds.getZSpan()))
                .put("note", "a moving structure: its parts are in its own coordinates"));
        }
        for (Entry entry : providers) {
            entry.provider().link(scan);
        }
        List<EdgeBuilder> edges = fold(scan);
        List<NodeBuilder> kept = new ArrayList<>();
        Set<NodeBuilder> linked = new LinkedHashSet<>();
        edges.forEach(edge -> {
            linked.add(edge.from());
            linked.add(edge.to());
        });
        for (NodeBuilder node : scan.nodes()) {
            if (!node.folds() || linked.contains(node)) {
                kept.add(node);
            }
        }
        kept.sort(Comparator.comparingInt(Mechanisms::rank));
        boolean truncated = kept.size() > MechanismGraph.MAX_NODES;
        List<NodeBuilder> shown = kept.subList(0, Math.min(kept.size(), MechanismGraph.MAX_NODES));
        Set<NodeBuilder> present = new LinkedHashSet<>(shown);
        List<MechanismGraph.Node> nodes = scan.nodes().stream().filter(present::contains).map(NodeBuilder::build)
            .toList();
        List<MechanismGraph.Edge> built = new ArrayList<>();
        for (EdgeBuilder edge : edges) {
            if (present.contains(edge.from()) && present.contains(edge.to())) {
                if (built.size() == MechanismGraph.MAX_EDGES) {
                    truncated = true;
                    break;
                }
                built.add(new MechanismGraph.Edge(edge.from().id(), edge.to().id(), edge.link(), edge.details()));
            }
        }
        Set<String> ids = new LinkedHashSet<>();
        nodes.forEach(node -> ids.add(node.id()));
        List<MechanismGraph.Path> paths = scan.paths().stream()
            .filter(path -> ids.contains(path.from()) && ids.contains(path.to())).toList();
        return new MechanismGraph(nodes, built, paths, List.copyOf(scan.unsupportedNotes()), truncated);
    }

    // Controls and what they move come first when the graph must be cut; plain machines last.
    private static int rank(NodeBuilder node) {
        if (node.has(Role.CONTROL) || node.has(Role.SOURCE)) {
            return 0;
        }
        if (node.has(Role.ACTUATOR) || node.has(Role.MOVING) || node.has(Role.REDSTONE)) {
            return 1;
        }
        return node.has(Role.TRANSMISSION) ? 2 : 3;
    }

    /**
     * Folds each plain relay with exactly two drive links into one link between its neighbours, the ratio multiplied
     * through and the relays named in `via`.
     */
    private static List<EdgeBuilder> fold(MechanismScan scan) {
        List<EdgeBuilder> edges = new ArrayList<>(scan.edges());
        boolean folded = true;
        while (folded) {
            folded = false;
            Map<NodeBuilder, List<EdgeBuilder>> touching = new HashMap<>();
            for (EdgeBuilder edge : edges) {
                touching.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge);
                touching.computeIfAbsent(edge.to(), key -> new ArrayList<>()).add(edge);
            }
            for (Map.Entry<NodeBuilder, List<EdgeBuilder>> entry : touching.entrySet()) {
                NodeBuilder relay = entry.getKey();
                List<EdgeBuilder> both = entry.getValue();
                if (!relay.folds() || both.size() != 2 || both.stream().anyMatch(edge -> edge.link() != Link.DRIVES)) {
                    continue;
                }
                EdgeBuilder first = both.get(0);
                EdgeBuilder second = both.get(1);
                // Orient the pair as upstream -> relay -> downstream when the edges say which way rotation flows.
                if (first.from() == relay && second.to() == relay) {
                    EdgeBuilder swap = first;
                    first = second;
                    second = swap;
                }
                NodeBuilder upstream = first.to() == relay ? first.from() : first.to();
                NodeBuilder downstream = second.from() == relay ? second.to() : second.from();
                if (upstream == downstream) {
                    continue;
                }
                double ratio = ratio(first, upstream) * ratio(second, relay);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("ratio", ratio);
                List<String> via = new ArrayList<>(via(first));
                via.add(relay.kind());
                via.addAll(via(second));
                details.put("via", via.size() > 6 ? List.of(via.getFirst(), "+" + (via.size() - 2), via.getLast()) : via);
                details.put("relays", relays(first) + relays(second) + 1);
                for (String flag : List.of("driven", "engaged")) {
                    if (Boolean.FALSE.equals(first.details().get(flag)) || Boolean.FALSE.equals(second.details().get(flag))) {
                        details.put(flag, false);
                    }
                }
                edges.remove(first);
                edges.remove(second);
                edges.add(new EdgeBuilder(upstream, downstream, Link.DRIVES, details));
                folded = true;
                break;
            }
        }
        return edges;
    }

    // The speed at the edge's far end per unit speed at `near`.
    private static double ratio(EdgeBuilder edge, NodeBuilder near) {
        double ratio = edge.details().get("ratio") instanceof Number number ? number.doubleValue() : 1;
        if (edge.from() == near || ratio == 0) {
            return ratio;
        }
        return 1 / ratio;
    }

    @SuppressWarnings("unchecked")
    private static List<String> via(EdgeBuilder edge) {
        Object via = edge.details().get("via");
        return via instanceof List<?> list ? (List<String>) list : List.of();
    }

    private static int relays(EdgeBuilder edge) {
        return edge.details().get("relays") instanceof Number number ? number.intValue() : 0;
    }

    /** What a sandboxed copy compares for the block: the providers' values, merged. Empty for plain blocks. */
    public static Map<String, Object> observe(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity block = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        Map<String, Object> values = new LinkedHashMap<>();
        for (Entry entry : providers) {
            entry.provider().observe(level, pos, state, block, values);
        }
        Controls.motion(level, WorldSpaces.at(level, pos)).forEach(motion -> motion.readings().forEach((name, reading) ->
                reading.read(level).ifPresent(value -> values.put(name, describe(value)))));
        return values;
    }

    public static Map<String, Object> observe(Entity entity) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Entry entry : providers) {
            entry.provider().observe(entity, values);
        }
        return values;
    }

    public static boolean mechanical(Entity entity) {
        return providers.stream().anyMatch(entry -> entry.provider().mechanical(entity));
    }

    static boolean mechanicalBlock(BlockState state) {
        return providers.stream().anyMatch(entry -> entry.provider().mechanical(state));
    }

    /** A signal type as plain data, as facility tools print it. */
    public static Map<String, Object> describe(SignalType type) {
        return switch (type) {
            case SignalType.Real real -> Map.of("kind", "real", "min", real.min(), "max", real.max(),
                "step", real.step());
            case SignalType.Vector vector -> Map.of("kind", "vector",
                "axes", vector.axes().stream().map(Mechanisms::describe).toList());
            case SignalType.Bool ignored -> Map.of("kind", "bool");
            case SignalType.Pulse ignored -> Map.of("kind", "pulse");
            case SignalType.Data ignored -> Map.of("kind", "data");
        };
    }

    /** A reading as plain data, as facility definitions write it. */
    public static Object describe(Value value) {
        return DefinitionCodec.readout(value);
    }
}
