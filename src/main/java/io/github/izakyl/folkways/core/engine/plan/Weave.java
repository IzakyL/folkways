package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Grown;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// The plan as the schedule takes it: the work and its two kinds of edge - what must come before what (`links`) and
// the goods going into each piece of work (`flows`) - which work one resident must do together (`groups`), and who
// must do it (`pins`). Groups are kept whole and apart: overlapping ones are one group, and a group holds only work
// on the plan, at least two of it. Asked work its owner keeps off the plan for now, waiting on work that ended
// undone, is `held`: it has not ended. Asked work the plan gave up for its owner is `gone`, with how it ended.
public record Weave(Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups,
                    Map<UUID, UUID> pins, Set<UUID> fresh, Map<UUID, Grown> pending,
                    Map<UUID, Integer> ranks, List<Gone> gone, List<Flow> flows, Set<UUID> held) {

    public Weave(Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups,
                 Map<UUID, UUID> pins, Set<UUID> fresh, Map<UUID, Grown> pending,
                 Map<UUID, Integer> ranks, List<Gone> gone) {
        this(vertices, links, groups, pins, fresh, pending, ranks, gone, List.of(), Set.of());
    }

    public Weave(Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups,
                 Map<UUID, UUID> pins, Set<UUID> fresh, Map<UUID, Grown> pending,
                 Map<UUID, Integer> ranks) {
        this(vertices, links, groups, pins, fresh, pending, ranks, List.of());
    }

    public Weave(Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups,
                 Map<UUID, UUID> pins, Set<UUID> fresh, Map<UUID, Grown> pending) {
        this(vertices, links, groups, pins, fresh, pending, Map.of());
    }

    public Weave(Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups,
                 Map<UUID, UUID> pins, Set<UUID> fresh) {
        this(vertices, links, groups, pins, fresh, Map.of());
    }

    public Weave(Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups) {
        this(vertices, links, groups, Map.of(), Set.of());
    }

    public int rankOf(UUID node) {
        return ranks.getOrDefault(node, 0);
    }

    // What must come before what, whichever kind of edge says so: every link, and every flow from work to work.
    public List<Before> orders() {
        Set<Before> orders = new LinkedHashSet<>(links);
        for (Flow flow : flows) {
            flow.after().ifPresent(from -> orders.add(new Before(from, flow.to())));
        }
        return List.copyOf(orders);
    }

    public Weave {
        ranks = Map.copyOf(ranks);
        gone = List.copyOf(gone);
        pending = Map.copyOf(pending);
        pins = Map.copyOf(pins);
        vertices = Map.copyOf(vertices);
        links = List.copyOf(links);
        flows = List.copyOf(flows);
        held = Set.copyOf(held);
        fresh = Set.copyOf(fresh);
        groups = merged(groups, vertices.keySet());
    }

    private static List<Set<UUID>> merged(List<Set<UUID>> given, Set<UUID> on) {
        List<Set<UUID>> merged = new ArrayList<>();
        for (Set<UUID> group : given) {
            Set<UUID> joined = new LinkedHashSet<>(group);
            for (var others = merged.iterator(); others.hasNext(); ) {
                Set<UUID> other = others.next();
                if (!Collections.disjoint(other, joined)) {
                    joined.addAll(other);
                    others.remove();
                }
            }
            merged.add(joined);
        }
        List<Set<UUID>> kept = new ArrayList<>(merged.size());
        for (Set<UUID> group : merged) {
            group.retainAll(on);
            if (group.size() > 1) {
                kept.add(Collections.unmodifiableSet(group));
            }
        }
        return List.copyOf(kept);
    }

    public static final Weave EMPTY = new Weave(Map.of(), List.of(), List.of());
}
