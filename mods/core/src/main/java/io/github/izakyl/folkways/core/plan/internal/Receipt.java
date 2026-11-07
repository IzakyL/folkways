package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.HaulWork;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;
import net.minecraft.server.level.ServerLevel;

/**
 * A delivery into a resident's own pack: the resident takes the goods it needs and keeps them. Its need is fed like
 * any other, by the resident alone, since the goods are to end up in their pack; doing it takes no time.
 */
final class Receipt implements Node {

    private final NodeSpec spec;
    private final UUID resident;
    private final LongConsumer arrived;

    private Receipt(NodeSpec spec, UUID resident, LongConsumer arrived) {
        this.spec = spec;
        this.resident = resident;
        this.arrived = arrived;
    }

    // `count` of a delivery's goods, into the pack of the resident its site names.
    static Receipt of(Delivery delivery, WorkSite.AtEntity into, long count) {
        NodeSpec asked = delivery.spec();
        Need goods = delivery.goods();
        NodeSpec spec = NodeSpec.of(asked.id(), HaulWork.DOMAIN, into, asked.stances(), Workload.Once.of(0))
            .needs(new Need(goods.spec(), Math.min(count, goods.count()), true))
            .doing(Doings.STOWING, goods.spec()).done();
        return new Receipt(spec, into.entity(), delivery.arrived());
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Optional<UUID> worker() {
        return Optional.of(resident);
    }

    @Override
    public boolean replannable() {
        return true;
    }

    // What it was handed stays in the pack it was drawn from: its need is drawn whole, or the work fails.
    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return new Outcome.Done(List.of(), List.of(), Optional.empty(), who.supplied());
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        if (how instanceof Ending.Done) {
            arrived.accept(spec.needs().getFirst().count());
        }
    }
}
