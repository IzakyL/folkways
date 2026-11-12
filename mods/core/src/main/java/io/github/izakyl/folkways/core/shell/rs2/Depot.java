package io.github.izakyl.folkways.core.shell.rs2;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.shell.crafting.CraftQuotes;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

// Immutable server snapshots only: options and Hold.take may run on the planning thread.
record Depot(UUID key, WorkSite site, Devices.NetworkKey network, Tally tally, Colony colony,
             List<PortReader.Entry> stock, long readAt, Set<ItemSpec> craftables, List<Tally.Executor> executors,
             List<Device> devices, CraftQuotes.Snapshot<Devices.NetworkKey> quotes, int taskLimit)
        implements Workshop {

    // `filter` and `fuzzy` are the device's native configuration; `admit` is its panel filter.
    record Device(WorldPos at, Stances stances, boolean take, boolean craft,
                  Optional<ItemSpec> filter, boolean fuzzy, Optional<StoreRule.Admit> admit) {

        boolean admits(ItemSpec goods) {
            return admit.map(rule -> rule.admits(goods)).orElse(true);
        }

        boolean allows(ItemSpec item, boolean crafting) {
            return filter.isEmpty() || filter.get().equals(item)
                || !crafting && fuzzy && filter.get().item().equals(item.item());
        }
    }

    @Override
    public ResourceLocation id() {
        return Rs2Integration.OWNER;
    }

    @Override
    public boolean alike() {
        return true;
    }

    @Override
    public List<Refinement.Change> options(Intent intent, Grown graph) {
        if (!(intent instanceof Produce produce)) {
            return List.of();
        }
        long present = tally.available(network, stock, readAt, produce.wanted());
        List<Refinement.Change> options = new ArrayList<>();
        if (present > 0) {
            long count = produce.runs(site, List.of(), 1, Optional.empty(), present);
            for (Device device : devices) {
                if (device.take() && device.admits(produce.wanted()) && stock.stream()
                        .filter(entry -> Goods.matches(entry.prototype(), produce.wanted()))
                        .allMatch(entry -> device.allows(Goods.specOf(entry.prototype()), false))) {
                    Tally.Ticket ticket = tally.ticket(colony.id(), network, stock, readAt, produce.wanted(),
                        count, List.of(), false);
                    TakeNode take = new TakeNode(device.at(), device.stances(), ticket, Optional.empty(), colony);
                    options.add(Refinement.Change.of(Grown.of(take)).holding(ticket));
                }
            }
            return List.copyOf(options);
        }
        // RS2 has no crafting CPUs; the panel bounds how many tasks the colony's own orders may run at once.
        if (tally.crafting(colony.id(), network) >= taskLimit) {
            return List.of();
        }
        long count = produce.runs(site, List.of(), 1, Optional.empty(), Math.min(4096, produce.count()));
        for (ItemSpec item : craftables) {
            if (!Goods.includes(produce.wanted(), item)) {
                continue;
            }
            var quote = quotes.find(network, item, count);
            if (quote.isEmpty()) {
                continue;
            }
            List<Tally.Executor> candidates = executors.stream()
                .filter(executor -> executor.outputs().contains(item)).toList();
            if (candidates.stream().noneMatch(executor -> tally.free(network, executor))) {
                continue;
            }
            for (Device craft : devices) {
                if (!craft.craft() || !craft.allows(item, true) || !craft.admits(item)) {
                    continue;
                }
                for (Device take : devices) {
                    if (!take.take() || !take.allows(item, false) || !take.admits(produce.wanted())) {
                        continue;
                    }
                    Tally.Ticket ticket = tally.ticket(colony.id(), network, stock, readAt, produce.wanted(), count,
                        candidates, true);
                    ticket.estimate = quote.get().ticks();
                    ticket.patterns = quote.get().patterns();
                    ticket.limit = taskLimit;
                    CraftOrder order = new CraftOrder(craft.at(), item, ticket, colony);
                    OrderNode submit = new OrderNode(craft.at(), craft.stances(), produce.wanted(), order,
                        level -> NetworkPresence.orders(colony, craft.at(), item));
                    TakeNode collect = new TakeNode(take.at(), take.stances(), ticket, Optional.of(order), colony);
                    options.add(Refinement.Change.of(Grown.then(submit, collect)).holding(ticket));
                }
            }
        }
        return List.copyOf(options);
    }
}
