package io.github.izakyl.folkways.core.engine.plan.haul;

import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;
import net.minecraft.server.level.ServerLevel;

/**
 * A delivery into a resident's own pack: the resident takes the goods it needs and keeps them. Its need is fed like
 * any other, by the resident alone, since the goods are to end up in their pack; doing it takes no time.
 */
public final class Receipt implements Node {

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
        NodeSpec spec = NodeSpec.of(asked.id(), Haul.DOMAIN, into, asked.stances(), Workload.Once.of(0))
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
