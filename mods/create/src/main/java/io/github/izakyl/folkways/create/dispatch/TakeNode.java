package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Goods taken by hand out of one of a network's containers. The network's tally promised them out of its whole
// stock, not out of this container, so the container is read again only here: short of them, the work fails and the
// plan looks again.
final class TakeNode implements Node {

    private final NodeSpec spec;
    private final Vocation hauling = Vocations.required(Vocations.HAULING);
    private final WorldPos at;
    private final ItemSpec want;
    private final long count;
    private final UUID network;
    private volatile long takenAt = -1;

    TakeNode(WorldPos at, Stances stances, ItemSpec want, long count, int reachTicks, UUID network) {
        this.at = at;
        this.want = want;
        this.count = count;
        this.network = network;
        this.spec = NodeSpec.of(UUID.randomUUID(), DispatchContent.ID, new WorkSite.AtBlock(at), stances,
                Workload.Once.of(reachTicks, DispatchContent::haste))
            .gives(new Amount(want, count))
            .doing(Doings.FETCHING, want)
            .vocation(hauling)
            .gesture(WorkGesture.SWING)
            .focus(at.cell())
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    WorldPos container() {
        return at;
    }

    UUID network() {
        return network;
    }

    ItemSpec goods() {
        return want;
    }

    long count() {
        return count;
    }

    // When the goods left the container, or -1 while they are still there.
    long takenAt() {
        return takenAt;
    }

    // Until it is begun, another container or an order may serve in its place.
    @Override
    public boolean replannable() {
        return true;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return Outcome.failed(DispatchRefusal.NO_SUPPLY);
        }
        List<ItemStack> taken = PackageNetworks.get().takeOut(level, at.block(level),
            stack -> Goods.matches(stack, want), count);
        if (taken.isEmpty()) {
            return Outcome.failed(DispatchRefusal.NO_SUPPLY);
        }
        takenAt = level.getGameTime();
        return new Outcome.Done(List.of(Rummaged.at(at.block(level))), taken, Optional.of(new Xp(hauling, 1)));
    }
}
