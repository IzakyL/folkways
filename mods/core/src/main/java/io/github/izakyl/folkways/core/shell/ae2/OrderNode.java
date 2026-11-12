package io.github.izakyl.folkways.core.shell.ae2;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.shell.crafting.NetworkRefusal;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;

final class OrderNode implements Node {

    private final NodeSpec spec;
    private final CraftOrder order;
    private final Predicate<ServerLevel> allowed;

    OrderNode(WorldPos at, Stances stances, ItemSpec want, CraftOrder order, Predicate<ServerLevel> allowed) {
        this.order = order;
        this.allowed = allowed;
        spec = NodeSpec.of(UUID.randomUUID(), Ae2Integration.OWNER, new WorkSite.AtBlock(at),
                stances, Workload.Once.of(10))
            .vocation(Vocations.required(Vocations.HAULING)).doing(Doings.WORKING, want)
            .gesture(WorkGesture.SWING).focus(at.cell()).done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        // The device's panel may have stopped orders, or filtered these goods out, since the plan was made.
        if (!allowed.test(level)) {
            return Outcome.failed(NetworkRefusal.NOT_ALLOWED);
        }
        return order.start(level).<Outcome>map(Outcome::failed).orElseGet(Outcome::done);
    }
}
