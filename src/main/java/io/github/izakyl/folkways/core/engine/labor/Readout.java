package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.engine.plan.Diagnosis;
import io.github.izakyl.folkways.core.engine.plan.Schedule;
import io.github.izakyl.folkways.core.engine.plan.Shortfall;
import io.github.izakyl.folkways.core.engine.plan.Unassigned;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.Why;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Readout {

    private static final int LONG_ENOUGH = 200;

    private static final int MOST_MISSING = 6;

    private int bodies;
    private int messages;
    private int didFinish;
    private final Map<String, Integer> completed = new LinkedHashMap<>();
    private int didRelease;
    private final Map<String, Integer> didRefuse = new LinkedHashMap<>();
    private final Map<String, Integer> didBreak = new LinkedHashMap<>();

    void froze(int bodies, int messages) {
        this.bodies = bodies;
        this.messages = messages;
    }

    void finished(Node node) {
        didFinish++;
        completed(node);
    }

    void completed(Node node) {
        if (node != null) completed.merge(node.spec().owner() + "/" + node.getClass().getSimpleName(), 1, Integer::sum);
    }

    void released() {
        didRelease++;
    }

    void refused(RefusalKind why) {
        didRefuse.merge(named(why), 1, Integer::sum);
    }

    void broke(String item) {
        didBreak.merge(item, 1, Integer::sum);
    }

    String line(Map<UUID, Vertex> graph, Phases phases, Schedule schedule, Diagnosis diagnosis,
            Runners runners, long solvingNanos) {
        StringBuilder said = new StringBuilder("bodies=").append(bodies)
            .append(" messages=").append(messages)
            .append(" nodes=").append(graph.size())
            .append(" tours=").append(schedule.tours().size())
            .append(" phases=").append(histogram(phases))
            .append(" done=").append(didFinish)
            .append(" released=").append(didRelease);
        if (!diagnosis.unassigned().isEmpty()) {
            Map<Unassigned.Reason, Integer> why = new EnumMap<>(Unassigned.Reason.class);
            for (Unassigned nobody : diagnosis.unassigned()) {
                why.merge(nobody.why(), 1, Integer::sum);
            }
            said.append(" unassigned=").append(why);
        }
        Map<UUID, Unassigned.Reason> waiting = new LinkedHashMap<>();
        for (Unassigned nobody : diagnosis.unassigned()) {
            waiting.put(nobody.node(), nobody.why());
        }
        said.append(" unassignedNodes=").append(waiting);
        Map<String, Integer> waitingKinds = new LinkedHashMap<>();
        for (Unassigned nobody : diagnosis.unassigned()) {
            Vertex vertex = graph.get(nobody.node());
            if (vertex != null) {
                Node node = vertex.node();
                String key = nobody.why() + "/" + node.spec().owner() + "/" + node.getClass().getSimpleName();
                waitingKinds.merge(key, 1, Integer::sum);
            }
        }
        said.append(" unassignedKinds=").append(waitingKinds);
        if (!diagnosis.shortfalls().isEmpty()) {
            said.append(" shortfall=").append(diagnosis.shortfalls().size())
                .append(whyOf(diagnosis.shortfalls()))
                .append(" missing=").append(missingOf(diagnosis.shortfalls()));
        }
        if (!diagnosis.refused().isEmpty()) {
            said.append(" turned=").append(diagnosis.refused().size());
        }
        if (!didRefuse.isEmpty()) {
            said.append(" refused=").append(didRefuse);
        }
        if (!didBreak.isEmpty()) {
            said.append(" broke=").append(didBreak);
        }
        Map<String, Integer> standing = new LinkedHashMap<>();
        int skipped = 0;
        for (BodyRunner runner : runners.all()) {
            skipped += runner.drainSkipped();
            standing.merge(runner.standing(), 1, Integer::sum);
        }
        if (!standing.isEmpty()) {
            said.append(" standing=").append(standing);
        }
        if (skipped > 0) {
            said.append(" skipped=").append(skipped);
        }
        if (!completed.isEmpty()) said.append(" completed=").append(completed);
        completed.clear();
        didFinish = 0;
        didRelease = 0;
        didRefuse.clear();
        didBreak.clear();
        if (solvingNanos > 0) {
            said.append(" solving");
            if (solvingNanos >= 1_000_000_000L) {
                said.append('=').append(solvingNanos / 1_000_000_000L).append('s');
            }
        }
        return said.toString();
    }

    List<String> stalls(Map<UUID, Vertex> graph, Phases phases, Runners runners,
            java.util.function.Function<UUID, String> why, java.util.function.Function<UUID, String> key) {
        List<String> lines = new ArrayList<>();
        Map<String, Integer> heads = new java.util.TreeMap<>();
        Map<String, Integer> routes = new java.util.TreeMap<>();
        for (BodyRunner runner : runners.all()) {
            if (runner.stalledTicks() > 0) {
                List<UUID> queued = runner.queued();
                UUID head = queued.isEmpty() ? null : queued.get(0);
                lines.add("stall body=" + brief(runner.resident())
                    + " for=" + runner.stalledTicks() + "t"
                    + " head=" + brief(head) + " " + where(graph, phases, head)
                    + " queued=" + queued.size()
                    + (head == null ? "" : " " + why.apply(head)));
                if (head != null) {
                    heads.merge(key.apply(head), 1, Integer::sum);
                }
                for (UUID each : queued) {
                    routes.merge(key.apply(each), 1, Integer::sum);
                }
            }
            if (runner.busyTicks() >= LONG_ENOUGH) {
                lines.add("busy body=" + brief(runner.resident())
                    + " for=" + runner.busyTicks() + "t " + runner.standing()
                    + runner.inHand()
                        .map(node -> " node=" + brief(node.id())
                            + " at " + node.spec().site().where())
                        .orElse(" (its own business, not a node)"));
            }
        }
        if (!heads.isEmpty()) {
            lines.add("stallHeads=" + heads);
            lines.add("stallRoutes=" + routes);
        }
        return lines;
    }

    private static String where(Map<UUID, Vertex> graph, Phases phases, UUID node) {
        if (node == null) {
            return "nothing queued";
        }
        Vertex vertex = graph.get(node);
        if (vertex == null) {
            return "off the graph";
        }
        return phases.of(node) + " at " + vertex.node().spec().site().where();
    }

    private static Map<NodeState, Integer> histogram(Phases phases) {
        Map<NodeState, Integer> counted = new EnumMap<>(NodeState.class);
        for (NodeState phase : NodeState.values()) {
            int many = phases.in(phase).size();
            if (many > 0) {
                counted.put(phase, many);
            }
        }
        return counted;
    }

    private static String missingOf(List<Shortfall> shortfalls) {
        Map<String, Long> tally = new LinkedHashMap<>();
        for (Shortfall each : shortfalls) {
            tally.merge(String.valueOf(each.what()), each.missing(), Long::sum);
        }
        return tally.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(MOST_MISSING)
            .map(one -> one.getKey() + "=" + one.getValue())
            .collect(java.util.stream.Collectors.joining(", ", "{", tally.size() > MOST_MISSING
                ? ", +" + (tally.size() - MOST_MISSING) + " more}" : "}"));
    }

    private static String whyOf(List<Shortfall> shortfalls) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (Shortfall each : shortfalls) {
            tally.merge(name(each.why()), 1, Integer::sum);
        }
        return tally.toString();
    }

    private static String name(Why why) {
        return switch (why) {
            case Why.Turned turned -> "TURNED:" + named(turned.why());
            case Why.Cooling ignored -> "COOLING";
            case Why.NoSource ignored -> "NO_SOURCE";
            case Why.NoRoom ignored -> "NO_ROOM";
            case Why.NoWay ignored -> "NO_WAY";
            case Why.NoHand ignored -> "NO_HAND";
        };
    }

    private static String named(RefusalKind why) {
        return why instanceof Enum<?> constant ? constant.name() : why.translationKey();
    }

    private static String brief(UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }
}
