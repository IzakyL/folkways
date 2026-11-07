package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Goods packed by hand at a packager no network packs at, into a parcel for the collection waiting on them, and sent
// from the port beside it: thrown onto the chain, or taken onto the colony's books for the train booked for it. The
// packager itself only gives the work its place; what it faces is never touched.
final class PackNode implements Node {

    // Sends a packed parcel from one port to another, or says it could not.
    interface Posting {

        boolean post(ServerLevel level, WorldPos from, WorldPos to, ItemStack parcel, CollectNode collect);
    }

    private final NodeSpec spec;
    private final Vocation hauling = Vocations.required(Vocations.HAULING);
    private final ItemSpec want;
    private final long count;
    private final WorldPos from;
    private final String route;
    private final CollectNode collect;
    private final Posting posting;

    PackNode(WorldPos packager, Stances stances, ItemSpec want, long count, int reachTicks, WorldPos from,
             String route, CollectNode collect, Posting posting) {
        this.want = want;
        this.count = count;
        this.from = from;
        this.route = route;
        this.collect = collect;
        this.posting = posting;
        this.spec = NodeSpec.of(UUID.randomUUID(), DispatchContent.ID, new WorkSite.AtBlock(packager), stances,
                Workload.Once.of(reachTicks, DispatchContent::haste))
            .needs(new Need(want, count))
            .doing(DispatchContent.PACKING, want)
            .vocation(hauling)
            .gesture(WorkGesture.SWING)
            .focus(packager.cell())
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    WorldPos sender() {
        return from;
    }

    CollectNode collection() {
        return collect;
    }

    // Until it is begun, another packager or another way altogether may serve in its place.
    @Override
    public boolean replannable() {
        return true;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        List<ItemStack> goods = who.supplied();
        long held = goods.stream().filter(stack -> Goods.matches(stack, want)).mapToLong(ItemStack::getCount).sum();
        if (held < count) {
            return Outcome.failed(DispatchRefusal.NO_SUPPLY);
        }
        Optional<ItemStack> parcel = PackageNetworks.get().pack(goods, route, collect.handOrder());
        if (parcel.isEmpty()) {
            return Outcome.failed(DispatchRefusal.NOT_PACKED);
        }
        if (!posting.post(level, from, collect.port(), parcel.get(), collect)) {
            return Outcome.failed(DispatchRefusal.PORT_FULL);
        }
        collect.sent(level.getGameTime());
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(hauling, 1)));
    }
}
