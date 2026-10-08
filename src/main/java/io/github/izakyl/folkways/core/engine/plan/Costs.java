package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What growing a source would cost, before it is grown. Every node - an intent not yet expanded as much as plain
 * work - states its own cost in its spec: the ticks it takes once its inputs are at hand, and the inputs it needs.
 * Goods in stock cost nothing to have; goods a workshop makes cost its work plus the dearest of their inputs.
 * Workshops are asked through a Produce intent: the handshake that answers an open edge with offers.
 */
final class Costs {

    static final long UNREACHABLE = Long.MAX_VALUE;

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    record Offer(Workshop from, Refinement.Change change) {
    }

    private record Way(List<Need> inputs, List<Amount> gives, long ticks) {
    }

    private final List<Workshop> workshops;
    private final Predicate<ItemSpec> stocked;
    private final LongSupplier revision;

    private final Map<ItemSpec, List<Offer>> probed = new LinkedHashMap<>();
    private final Map<ItemSpec, List<Way>> discovered = new LinkedHashMap<>();
    private final Map<Set<ItemSpec>, Map<ItemSpec, Long>> settled = new HashMap<>();
    private final Map<ItemSpec, Map<ItemSpec, Boolean>> overlaps = new HashMap<>();
    private Set<ItemSpec> drawable = Set.of();
    private long settledAt = -1;
    private boolean stale = true;

    Costs(List<Workshop> workshops, Predicate<ItemSpec> stocked, LongSupplier revision) {
        this.workshops = workshops;
        this.stocked = stocked;
        this.revision = revision;
    }

    List<Offer> offers(Produce asked) {
        List<Offer> found = new ArrayList<>();
        Grown alone = Grown.of(asked);
        for (Workshop workshop : workshops) {
            try {
                for (Refinement.Change change : workshop.options(asked, alone)) {
                    found.add(new Offer(workshop, change));
                }
            } catch (RuntimeException | LinkageError broken) {
                LOGGER.error("workshop {} threw answering for {}", workshop.key(), asked.wanted(), broken);
            }
        }
        return List.copyOf(found);
    }

    boolean reachable(ItemSpec spec, WorkSite near, Set<ItemSpec> upstream) {
        if (!discovered.containsKey(spec)) {
            grow(List.of(spec), near);
        }
        return settle(upstream).getOrDefault(spec, UNREACHABLE) != UNREACHABLE;
    }

    // The cost of work not yet on the graph: its own ticks, then the dearest of the inputs it would open.
    long of(Grown made, WorkSite near, Set<ItemSpec> upstream) {
        List<ItemSpec> inputs = new ArrayList<>();
        long ticks = 0;
        for (Node node : made.nodes()) {
            ticks = plus(ticks, node.spec().estimate());
            for (Need need : node.spec().needs()) {
                inputs.add(need.spec());
            }
        }
        grow(inputs, near);
        Map<ItemSpec, Long> costs = settle(upstream);
        long slowest = 0;
        for (ItemSpec input : inputs) {
            slowest = Math.max(slowest, costs.getOrDefault(input, UNREACHABLE));
        }
        return plus(ticks, slowest);
    }

    private List<Offer> probe(ItemSpec wanted, WorkSite near) {
        return probed.computeIfAbsent(wanted,
            spec -> offers(Produce.of(Weaver.OWNER, near, spec, 1, Produce.UNBOUNDED)));
    }

    private void grow(Collection<ItemSpec> seeds, WorkSite near) {
        Deque<ItemSpec> pending = new ArrayDeque<>();
        Set<ItemSpec> queued = new LinkedHashSet<>();
        for (ItemSpec seed : seeds) {
            enqueue(seed, queued, pending);
        }
        if (pending.isEmpty()) {
            return;
        }
        while (!pending.isEmpty()) {
            ItemSpec spec = pending.removeFirst();
            List<Way> ways = new ArrayList<>();
            for (Offer offer : probe(spec, near)) {
                List<Need> inputs = new ArrayList<>();
                List<Amount> gives = new ArrayList<>();
                long ticks = 0;
                for (Node node : offer.change().replacement().nodes()) {
                    inputs.addAll(node.spec().needs());
                    gives.addAll(node.spec().gives());
                    ticks = plus(ticks, node.spec().estimate());
                }
                ways.add(new Way(List.copyOf(inputs), List.copyOf(gives), ticks));
                for (Need input : inputs) {
                    enqueue(input.spec(), queued, pending);
                }
            }
            discovered.put(spec, List.copyOf(ways));
        }
        stale = true;
    }

    private void enqueue(ItemSpec spec, Set<ItemSpec> queued, Deque<ItemSpec> pending) {
        if (!discovered.containsKey(spec) && queued.add(spec)) {
            pending.addLast(spec);
        }
    }

    private Map<ItemSpec, Long> settle(Set<ItemSpec> upstream) {
        if (stale || settledAt != revision.getAsLong()) {
            settledAt = revision.getAsLong();
            Set<ItemSpec> now = new HashSet<>();
            for (ItemSpec spec : discovered.keySet()) {
                if (stocked.test(spec)) {
                    now.add(spec);
                }
            }
            if (stale || !now.equals(drawable)) {
                drawable = now;
                settled.clear();
            }
            stale = false;
        }
        return settled.computeIfAbsent(Set.copyOf(upstream), this::relax);
    }

    // Goods on the way up to what is asked may not be made from what is asked: barred, they cost too much.
    private Map<ItemSpec, Long> relax(Set<ItemSpec> upstream) {
        Set<ItemSpec> barred = new HashSet<>();
        for (Map.Entry<ItemSpec, List<Way>> entry : discovered.entrySet()) {
            bar(entry.getKey(), upstream, barred);
            for (Way way : entry.getValue()) {
                way.gives().forEach(gives -> bar(gives.spec(), upstream, barred));
            }
        }
        Map<ItemSpec, Long> costs = new HashMap<>();
        for (ItemSpec spec : discovered.keySet()) {
            if (!barred.contains(spec) && drawable.contains(spec)) {
                costs.put(spec, 0L);
            }
        }
        boolean eased = true;
        for (int pass = 0; eased && pass <= discovered.size(); pass++) {
            eased = false;
            for (Map.Entry<ItemSpec, List<Way>> entry : discovered.entrySet()) {
                for (Way way : entry.getValue()) {
                    long cost = costOf(way, costs);
                    if (cost == UNREACHABLE) {
                        continue;
                    }
                    eased |= ease(costs, barred, entry.getKey(), cost);
                    for (Amount gives : way.gives()) {
                        eased |= ease(costs, barred, gives.spec(), cost);
                    }
                }
            }
        }
        return costs;
    }

    private void bar(ItemSpec spec, Set<ItemSpec> upstream, Set<ItemSpec> barred) {
        if (barred.contains(spec)) {
            return;
        }
        for (ItemSpec one : upstream) {
            if (overlaps.computeIfAbsent(one, key -> new HashMap<>())
                    .computeIfAbsent(spec, other -> Goods.overlap(one, other))) {
                barred.add(spec);
                return;
            }
        }
    }

    private static boolean ease(Map<ItemSpec, Long> costs, Set<ItemSpec> barred, ItemSpec spec, long cost) {
        if (!barred.contains(spec) && cost < costs.getOrDefault(spec, UNREACHABLE)) {
            costs.put(spec, cost);
            return true;
        }
        return false;
    }

    private static long costOf(Way way, Map<ItemSpec, Long> costs) {
        long slowest = 0;
        for (Need input : way.inputs()) {
            slowest = Math.max(slowest, costs.getOrDefault(input.spec(), UNREACHABLE));
        }
        return plus(way.ticks(), slowest);
    }

    static long plus(long one, long other) {
        return one == UNREACHABLE || other == UNREACHABLE || one + other < 0 ? UNREACHABLE : one + other;
    }
}
