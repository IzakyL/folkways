package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// The whole plan after a round, as a test reads it: the work on it, what comes before what, the work one resident
// must do together, who work is bound to, and asked work held off it - with what that round grew afresh and gave up.
public record Shown(Map<UUID, Vertex> vertices, List<Before> orders, List<Set<UUID>> groups, Map<UUID, UUID> pins,
                    Set<UUID> held, Set<UUID> fresh, List<Gone> gone) {

    public Shown {
        vertices = Map.copyOf(vertices);
        orders = List.copyOf(orders);
        groups = List.copyOf(groups);
        pins = Map.copyOf(pins);
        held = Set.copyOf(held);
        fresh = Set.copyOf(fresh);
        gone = List.copyOf(gone);
    }

    public static Shown of(WeaveView plan, WeaveDelta round) {
        List<Before> orders = new ArrayList<>();
        Set<Set<UUID>> groups = new LinkedHashSet<>();
        for (UUID id : plan.vertices().keySet()) {
            for (UUID next : plan.after(id)) {
                orders.add(new Before(id, next));
            }
            plan.grouped(id).ifPresent(groups::add);
        }
        return new Shown(new LinkedHashMap<>(plan.vertices()), orders, List.copyOf(groups), plan.pins(),
            plan.held(), round.fresh(), round.gone());
    }

    // A change that puts exactly this plan in place of none: for tests that lay work out, or hand it to the colony,
    // without growing it first.
    public static WeaveDelta delta(Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups) {
        return delta(vertices, links, groups, Map.of(), Set.of(), List.of(), Set.of());
    }

    public static WeaveDelta delta(Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups,
                                   Map<UUID, UUID> pins, Set<UUID> fresh, List<Gone> gone, Set<UUID> held) {
        // Overlapping groups are one group, holding only work on the plan, as the weave shows them.
        List<Set<UUID>> merged = new ArrayList<>();
        for (Set<UUID> group : groups) {
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
        Map<UUID, Set<UUID>> grouped = new LinkedHashMap<>();
        for (Set<UUID> group : merged) {
            group.retainAll(vertices.keySet());
            if (group.size() > 1) {
                for (UUID member : group) {
                    grouped.put(member, Set.copyOf(group));
                }
            }
        }
        Map<UUID, Optional<UUID>> pinned = new LinkedHashMap<>();
        pins.forEach((node, worker) -> pinned.put(node, Optional.of(worker)));
        return new WeaveDelta(vertices, Set.of(), new LinkedHashSet<>(links), Set.of(), grouped, pinned, fresh, gone,
            held.isEmpty() ? Optional.empty() : Optional.of(held));
    }

    public static WeaveDelta delta(Map<UUID, Vertex> vertices, List<Before> links, List<Set<UUID>> groups,
                                   Map<UUID, UUID> pins, Set<UUID> fresh) {
        return delta(vertices, links, groups, pins, fresh, List.of(), Set.of());
    }

    // Lays out a plan made by hand, as the schedule lays out the plan the weave shows it.
    static Schedule schedule(Tours tours, WeaveDelta plan, Crew crew, Ways ways, long tick, boolean retry) {
        return tours.schedule(view(plan), plan.fresh(), crew, ways, tick, retry);
    }

    public static WeaveView view(WeaveDelta delta) {
        WeaveView view = new WeaveView();
        view.apply(delta);
        return view;
    }
}
