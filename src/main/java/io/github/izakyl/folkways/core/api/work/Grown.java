package io.github.izakyl.folkways.core.api.work;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// `follows` orders work here after work asked for before, outside this fragment: each comes after its `from`
// for as long as that is still asked.
public record Grown(List<Node> nodes, List<Before> links, int rank, List<Set<UUID>> workerGroups,
                    List<Before> follows) {

    public Grown(List<Node> nodes, List<Before> links) {
        this(nodes, links, 0, List.of());
    }

    public Grown(List<Node> nodes, List<Before> links, int rank, List<Set<UUID>> workerGroups) {
        this(nodes, links, rank, workerGroups, List.of());
    }

    public Grown following(List<Before> earlier) {
        return new Grown(nodes, links, rank, workerGroups, earlier);
    }

    public Grown byOneWorker() {
        Set<UUID> all = new LinkedHashSet<>();
        nodes.forEach(node -> all.add(node.id()));
        List<Set<UUID>> groups = new ArrayList<>(workerGroups);
        groups.add(all);
        return new Grown(nodes, links, rank, groups, follows);
    }

    public Grown ranked(int rank) {
        return new Grown(nodes, links, rank, workerGroups, follows);
    }

    public Grown {
        nodes = List.copyOf(nodes);
        links = List.copyOf(links);
        follows = List.copyOf(follows);
        workerGroups = workerGroups.stream().map(Set::copyOf).toList();
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("a promise that grows nothing is not work");
        }
        Map<UUID, Set<UUID>> next = new LinkedHashMap<>();
        Map<UUID, Integer> incoming = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (incoming.putIfAbsent(node.id(), 0) != null) {
                throw new IllegalArgumentException("duplicate work node " + node.id());
            }
            next.put(node.id(), new LinkedHashSet<>());
        }
        for (Set<UUID> group : workerGroups) {
            if (group.isEmpty() || !incoming.keySet().containsAll(group)) {
                throw new IllegalArgumentException("worker group outside work fragment: " + group);
            }
        }
        for (Before link : links) {
            if (!incoming.containsKey(link.from()) || !incoming.containsKey(link.to())) {
                throw new IllegalArgumentException("dependency outside work fragment: " + link);
            }
            if (next.get(link.from()).add(link.to())) {
                incoming.compute(link.to(), (id, count) -> count + 1);
            }
        }
        for (Before link : follows) {
            if (incoming.containsKey(link.from()) || !incoming.containsKey(link.to())) {
                throw new IllegalArgumentException("work followed is not from before this fragment: " + link);
            }
        }
        ArrayDeque<UUID> ready = new ArrayDeque<>();
        incoming.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int visited = 0;
        while (!ready.isEmpty()) {
            UUID id = ready.remove();
            visited++;
            for (UUID after : next.get(id)) {
                if (incoming.compute(after, (key, count) -> count - 1) == 0) {
                    ready.add(after);
                }
            }
        }
        if (visited != nodes.size()) {
            throw new IllegalArgumentException("work dependencies contain a cycle");
        }
    }

    public Set<UUID> completions() {
        Set<UUID> due = new LinkedHashSet<>();
        for (Node node : nodes) {
            due.add(node.id());
        }
        return due;
    }

    public Grown remaining(Set<UUID> left) {
        Set<UUID> kept = new LinkedHashSet<>();
        for (Node node : nodes) {
            if (left.contains(node.id())) {
                kept.add(node.id());
            }
        }
        if (kept.size() == nodes.size()) {
            return this;
        }
        List<Set<UUID>> groups = new ArrayList<>();
        for (Set<UUID> group : workerGroups) {
            Set<UUID> still = new LinkedHashSet<>(group);
            still.retainAll(kept);
            if (!still.isEmpty()) {
                groups.add(still);
            }
        }
        return new Grown(nodes.stream().filter(node -> kept.contains(node.id())).toList(),
            links.stream().filter(link -> kept.contains(link.from()) && kept.contains(link.to())).toList(),
            rank, groups, follows.stream().filter(link -> kept.contains(link.to())).toList());
    }

    public static Grown of(Node node) {
        return new Grown(List.of(node), List.of());
    }

    public static Grown then(Node first, Node next) {
        return new Grown(List.of(first, next), List.of(new Before(first.id(), next.id())));
    }
}
