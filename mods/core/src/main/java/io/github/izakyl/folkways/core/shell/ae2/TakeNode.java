package io.github.izakyl.folkways.core.shell.ae2;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkNoise;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

final class TakeNode implements Node {

    private final NodeSpec spec;
    private final WorldPos at;
    private final Tally.Ticket ticket;
    private final Optional<CraftOrder> order;
    private final Colony colony;

    TakeNode(WorldPos at, Stances stances, Tally.Ticket ticket, Optional<CraftOrder> order,
             Colony colony) {
        this.at = at;
        this.ticket = ticket;
        this.order = order;
        this.colony = colony;
        spec = NodeSpec.of(UUID.randomUUID(), Ae2Integration.OWNER, new WorkSite.AtBlock(at),
                stances, Workload.Once.of(10))
            .vocation(Vocations.required(Vocations.HAULING)).gives(new Amount(ticket.want, ticket.count))
            .doing(Doings.FETCHING, ticket.want)
            .gesture(WorkGesture.SWING).focus(at.cell())
            .estimate(order.isPresent() ? (int) ticket.estimatedWaitTicks() : 10).done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean replannable() {
        return order.isEmpty();
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        // Finished feedback can arrive after another server poll; the missing stock is now in the resident's pack.
        if (ticket.takenAt >= 0) {
            return Optional.empty();
        }
        Optional<RefusalKind> refused = order.flatMap(value -> value.poll(level));
        if (refused.isPresent()) {
            return refused;
        }
        if (port(level).isEmpty()) {
            return Optional.of(Ae2Refusal.NO_DEVICE);
        }
        if (order.isEmpty() && !ready(level)) {
            return Optional.of(Ae2Refusal.NO_STOCK);
        }
        return Optional.empty();
    }

    @Override
    public boolean ready(ServerLevel level) {
        if (order.isPresent() && !order.get().done(level)) {
            return false;
        }
        return port(level).filter(port -> {
            long amount = 0;
            for (var entry : port.storage().getAvailableStacks()) {
                if (entry.getKey() instanceof appeng.api.stacks.AEItemKey item
                        && Goods.matches(item.toStack(), ticket.want)) {
                    amount += entry.getLongValue();
                }
            }
            return amount >= ticket.count;
        }).isPresent();
    }

    private Optional<NetworkPort> port(ServerLevel level) {
        if (!at.in(level) || !level.isLoaded(at.block(level))
                || !colony.storesIn(level.dimension()).contains(at)) {
            return Optional.empty();
        }
        return Devices.at(level, at.block(level)).filter(port -> port.usability().usable()
            && port.operations().contains(StorePort.Operation.TAKE) && port.identity().key().equals(ticket.network));
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        Optional<NetworkPort> port = port(level);
        if (port.isEmpty() || order.isPresent() && !order.get().done(level)) {
            return Outcome.failed(Ae2Refusal.NO_DEVICE);
        }
        TransferResult result = port.get().take(level, List.of(new Pull.Matching(ticket.want, ticket.count)),
            colony.storeRules(at), false);
        if (!(result instanceof TransferResult.Done done)) {
            return Outcome.failed(Ae2Refusal.NO_STOCK);
        }
        ticket.takenAt = level.getGameTime();
        return new Outcome.Done(List.of(WorkNoise.stowed()), done.moved(), Optional.empty());
    }
}
