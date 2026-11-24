package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Immutable server snapshots: planning and reservation may run off the server thread. */
public interface NetworkDepot<K, E, N> extends Workshop {

    // `filter` and `fuzzy` are the device's native configuration; `admit` is its panel filter.
    record Device(WorldPos at, Stances stances, boolean take, boolean craft,
            Optional<ItemSpec> filter, boolean fuzzy, Optional<StoreRule.Admit> admit) {

        public Device(WorldPos at, Stances stances, boolean take, boolean craft, Optional<StoreRule.Admit> admit) {
            this(at, stances, take, craft, Optional.empty(), false, admit);
        }

        boolean admits(ItemSpec goods) {
            return admit.map(rule -> rule.admits(goods)).orElse(true);
        }

        boolean allows(ItemSpec item, boolean crafting) {
            return filter.isEmpty() || filter.get().equals(item)
                || !crafting && fuzzy && filter.get().item().equals(item.item());
        }
    }

    K network();

    NetworkTally<K, E, N> tally();

    Colony colony();

    Set<ItemSpec> craftables();

    List<Device> devices();

    CraftQuotes.Snapshot<K> quotes();

    int limit();

    TakeNode.Source<?> collection();

    List<E> candidates(ItemSpec item, CraftQuotes.Quote quote);

    boolean free(E executor);

    TakeNode.Order order(WorldPos at, ItemSpec item, NetworkTally<K, E, N>.Ticket ticket);

    boolean orders(WorldPos at, ItemSpec item);

    @Override
    default boolean alike() {
        return true;
    }

    @Override
    default List<Refinement.Change> options(Intent intent, Grown graph) {
        if (!(intent instanceof Produce produce)) {
            return List.of();
        }
        var tally = tally();
        var network = network();
        var colony = colony();
        List<Refinement.Change> options = new ArrayList<>();
        // The colony may use only its allotted share of the network.
        if (tally.crafting(colony.id(), network) >= limit()) {
            return List.of();
        }
        long count = produce.runs(site(), List.of(), 1, Optional.empty(), Math.min(4096, produce.count()));
        for (ItemSpec item : craftables()) {
            if (!Goods.includes(produce.wanted(), item)) {
                continue;
            }
            var quote = quotes().find(network, item, count);
            if (quote.isEmpty()) {
                continue;
            }
            List<E> candidates = candidates(item, quote.get());
            if (candidates.stream().noneMatch(this::free)) {
                continue;
            }
            for (Device craft : devices()) {
                if (!craft.craft() || !craft.allows(item, true) || !craft.admits(item)) {
                    continue;
                }
                for (Device take : devices()) {
                    if (!take.take() || !take.allows(item, false) || !take.admits(produce.wanted())) {
                        continue;
                    }
                    var ticket = tally.ticket(colony.id(), network, produce.wanted(), count,
                        candidates);
                    ticket.estimate(quote.get().ticks());
                    ticket.patterns(quote.get().patterns());
                    ticket.limit(limit());
                    var order = order(craft.at(), item, ticket);
                    OrderNode submit = new OrderNode(id(), craft.at(), craft.stances(), produce.wanted(), order::start,
                        level -> orders(craft.at(), item));
                    var collect = collection().take(take.at(), take.stances(), ticket, order, colony);
                    options.add(Refinement.Change.of(Grown.then(submit, collect)).holding(ticket));
                }
            }
        }
        return List.copyOf(options);
    }
}
