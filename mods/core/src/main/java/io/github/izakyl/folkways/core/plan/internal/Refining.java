package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

// The rules that price and refine intents. A node rewrite keeps the intent's id as the work's last step, and
// whatever came before it comes before the work's first steps. A material refinement lays into the lent expansion:
// its consumer already exists, and goods within reach may need no new work at all.
final class Refining {
    private static final int LIMIT = 256;
    private final List<Refinement> rules;
    private final Supplier<Refinement.Context> context;

    public Refining(List<Refinement> rules) {
        this(rules, () -> Refinement.Context.NONE);
    }

    public Refining(List<Refinement> rules, Supplier<Refinement.Context> context) {
        this.rules = List.copyOf(rules);
        this.context = context;
    }

    public record Result(Grown graph, boolean complete, List<Hold> holds) {
        public Result { holds = List.copyOf(holds); }
    }

    public OptionalLong estimate(Intent intent) {
        Refinement.Context lent = context.get();
        for (Refinement rule : rules) {
            OptionalLong cost = rule.estimate(intent, lent);
            if (cost.isPresent()) {
                return cost;
            }
        }
        return OptionalLong.empty();
    }

    public long lay(Intent intent, Refinement.Context lent) {
        for (Refinement rule : rules) {
            OptionalLong laid = rule.lay(intent, lent);
            if (laid.isPresent()) {
                return laid.getAsLong();
            }
        }
        return 0;
    }

    public Optional<Result> expand(Grown original) {
        Result result = reduce(original);
        return result.complete() ? Optional.of(result) : Optional.empty();
    }

    public Result reduce(Grown original) {
        return reduce(new Result(original, false, List.of()));
    }

    public Result reduce(Result previous) {
        Grown graph = previous.graph();
        List<Hold> holds = new ArrayList<>(previous.holds());
        Refinement.Context lent = context.get();
        for (int step = 0; step < LIMIT; step++) {
            List<Intent> intents = open(graph);
            if (intents.isEmpty()) return new Result(graph, true, holds);
            boolean advanced = false;
            for (Intent intent : intents) {
                for (Refinement rule : rules) {
                    Optional<Refinement.Change> answer = rule.rewrite(intent, graph, lent);
                    if (answer.isPresent()) {
                        graph = substitute(graph, intent, answer.get());
                        holds.addAll(answer.get().holds());
                        advanced = true;
                        break;
                    }
                }
                if (advanced) break;
            }
            if (!advanced) return new Result(graph, false, holds);
        }
        if (open(graph).isEmpty()) return new Result(graph, true, holds);
        throw new IllegalArgumentException("refinement did not terminate within " + LIMIT + " steps");
    }

    private List<Intent> open(Grown graph) {
        return graph.nodes().stream().filter(Intent.class::isInstance).map(Intent.class::cast).toList();
    }

    private static Grown substitute(Grown graph, Intent intent, Refinement.Change change) {
        Grown replacement = change.replacement();
        UUID boundary = intent.id();
        Set<UUID> ids = new LinkedHashSet<>();
        replacement.nodes().forEach(node -> ids.add(node.id()));
        if (!ids.contains(boundary)) {
            throw new IllegalArgumentException("refinement lost completion boundary " + boundary);
        }
        Map<UUID, Set<UUID>> previous = new HashMap<>();
        Set<UUID> entries = new LinkedHashSet<>(ids);
        for (Before edge : replacement.links()) {
            previous.computeIfAbsent(edge.to(), k -> new LinkedHashSet<>()).add(edge.from());
            entries.remove(edge.to());
        }
        Set<UUID> ancestors = new LinkedHashSet<>();
        Deque<UUID> pending = new ArrayDeque<>();
        pending.add(boundary);
        while (!pending.isEmpty()) {
            UUID id = pending.remove();
            if (ancestors.add(id)) pending.addAll(previous.getOrDefault(id, Set.of()));
        }
        if (!ancestors.equals(ids)) {
            throw new IllegalArgumentException("refinement work escapes its completion boundary " + boundary);
        }
        List<Node> nodes = new ArrayList<>();
        for (Node node : graph.nodes()) {
            if (node.id().equals(boundary)) nodes.addAll(replacement.nodes());
            else nodes.add(node);
        }
        Set<Before> edges = new LinkedHashSet<>(replacement.links());
        for (Before edge : graph.links()) {
            if (edge.to().equals(boundary)) {
                for (UUID entry : entries) edges.add(new Before(edge.from(), entry));
            } else edges.add(edge);
        }
        List<Set<UUID>> groups = new ArrayList<>();
        for (Set<UUID> group : graph.workerGroups()) {
            Set<UUID> expanded = new LinkedHashSet<>(group);
            if (expanded.contains(boundary)) expanded.addAll(ids);
            groups.add(expanded);
        }
        edges.addAll(change.links());
        groups.addAll(change.workerGroups());
        groups.addAll(replacement.workerGroups());
        return new Grown(nodes, List.copyOf(edges), groups);
    }
}
