package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Before;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * What every live growth says binds work to work, kept as the growths change rather than read off all of them each
 * time it is asked: what must come before what (links, and flows from work to work), counted by how many growths
 * say so, and which sets of work one resident must do together. Whoever keeps it hears each order that comes or goes,
 * and each piece of work whose set of fellows moved.
 */
final class Bonds {

    interface Heard {

        // An order now said by some growth, or by none any more.
        void ordered(Before order);

        // Work whose fellows - the sets it is bound into - changed.
        void bound(UUID node);
    }

    private final Map<UUID, Map<UUID, Integer>> next = new HashMap<>();
    private final Map<UUID, Map<UUID, Integer>> previous = new HashMap<>();
    private final Map<UUID, List<Set<UUID>>> sets = new HashMap<>();
    private final Heard heard;

    Bonds(Heard heard) {
        this.heard = heard;
    }

    void order(Before order, int by) {
        int was = count(next, order.from(), order.to());
        count(next, order.from(), order.to(), by);
        count(previous, order.to(), order.from(), by);
        if ((was > 0) != (was + by > 0)) {
            heard.ordered(order);
        }
    }

    void together(Set<UUID> set, int by) {
        for (UUID member : set) {
            List<Set<UUID>> in = sets.computeIfAbsent(member, key -> new ArrayList<>());
            if (by > 0) {
                in.add(set);
            } else {
                // The very set given, not an equal one: two growths may each say the same pair.
                for (int at = in.size() - 1; at >= 0; at--) {
                    if (in.get(at) == set) {
                        in.remove(at);
                        break;
                    }
                }
                if (in.isEmpty()) {
                    sets.remove(member);
                }
            }
            heard.bound(member);
        }
    }

    boolean ordered(Before order) {
        return count(next, order.from(), order.to()) > 0;
    }

    Set<UUID> after(UUID node) {
        Map<UUID, Integer> to = next.get(node);
        return to == null ? Set.of() : to.keySet();
    }

    Set<UUID> before(UUID node) {
        Map<UUID, Integer> from = previous.get(node);
        return from == null ? Set.of() : from.keySet();
    }

    // Every piece of work bound to `first` through any chain of sets, whether on the graph or not; the caller keeps
    // what it wants of it.
    Set<UUID> fellows(UUID first) {
        return fellows(first, member -> true);
    }

    // The work bound to `first` through chains of sets that pass only through work `through` admits; `first` is
    // always among it.
    Set<UUID> fellows(UUID first, Predicate<UUID> through) {
        Set<UUID> group = new LinkedHashSet<>();
        group.add(first);
        Deque<UUID> pending = new ArrayDeque<>();
        pending.add(first);
        while (!pending.isEmpty()) {
            for (Set<UUID> set : sets.getOrDefault(pending.poll(), List.of())) {
                for (UUID member : set) {
                    if (through.test(member) && group.add(member)) {
                        pending.add(member);
                    }
                }
            }
        }
        return group;
    }

    // Whether any of `targets` can be reached from `from` along orders.
    boolean reaches(UUID from, Set<UUID> targets) {
        if (targets.isEmpty()) {
            return false;
        }
        Set<UUID> seen = new LinkedHashSet<>();
        Deque<UUID> pending = new ArrayDeque<>();
        pending.add(from);
        while (!pending.isEmpty()) {
            UUID id = pending.pop();
            if (targets.contains(id)) {
                return true;
            }
            if (seen.add(id)) {
                pending.addAll(after(id));
            }
        }
        return false;
    }

    void forEachOrderOf(UUID node, Consumer<Before> each) {
        for (UUID to : List.copyOf(after(node))) {
            each.accept(new Before(node, to));
        }
        for (UUID from : List.copyOf(before(node))) {
            each.accept(new Before(from, node));
        }
    }

    void clear() {
        next.clear();
        previous.clear();
        sets.clear();
    }

    private static int count(Map<UUID, Map<UUID, Integer>> index, UUID from, UUID to) {
        Map<UUID, Integer> out = index.get(from);
        return out == null ? 0 : out.getOrDefault(to, 0);
    }

    private static void count(Map<UUID, Map<UUID, Integer>> index, UUID from, UUID to, int by) {
        Map<UUID, Integer> out = index.computeIfAbsent(from, key -> new HashMap<>());
        int now = out.getOrDefault(to, 0) + by;
        if (now > 0) {
            out.put(to, now);
        } else {
            out.remove(to);
            if (out.isEmpty()) {
                index.remove(from);
            }
        }
    }
}
