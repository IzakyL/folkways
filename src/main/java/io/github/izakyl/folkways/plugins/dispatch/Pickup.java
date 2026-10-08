package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

record Pickup(UUID key, WorldPos at, Stances stances, Map<UUID, CollectNode> collecting, Eta eta,
              NetworkSnapshot.Port port, Optional<NetworkSnapshot.Reach> reach, int reachTicks)
    implements Workshop {

    static final int PORT_SLOTS = 18;

    static final int PARCEL_SLOTS = 9;

    @Override
    public ResourceLocation id() {
        return DispatchContent.ID;
    }

    @Override
    public WorkSite site() {
        return new WorkSite.AtBlock(at);
    }

    @Override
    public List<Refinement.Change> options(Intent intent, Grown graph) {
        if (!(intent instanceof Produce produce)) {
            return List.of();
        }
        ItemSpec spec = produce.wanted();
        if (Goods.stackSize(spec) <= 0) {
            return List.of();
        }
        int estimate = (int) Math.min(Integer.MAX_VALUE, (long) reachTicks + port.eta());
        synchronized (collecting) {
            Optional<CollectNode> sent = adrift(produce);
            if (sent.isPresent()) {
                CollectNode node = new CollectNode(at, stances, spec, sent.get().count(), reachTicks,
                    estimate, sent.get().network(), port.route(), eta, collecting).adopting(sent.get());
                return List.of(Refinement.Change.of(Grown.of(node)).holding(new Promised(node, sent)));
            }
            if (!port.open() || !port.addressable()) {
                return List.of();
            }
            long ceiling = available(spec);
            if (ceiling <= 0) {
                return List.of();
            }
            long granted = produce.runs(site(), List.of(), 1, Optional.empty(), ceiling);
            CollectNode node = new CollectNode(at, stances, spec, granted, reachTicks, estimate,
                port.from(), port.route(), eta, collecting);
            return List.of(Refinement.Change.of(Grown.of(node)).holding(new Promised(node, Optional.empty())));
        }
    }

    private Optional<CollectNode> adrift(Produce produce) {
        Set<Item> wanted = Goods.members(produce.wanted());
        return collecting.values().stream()
            .filter(node -> node.adrift() && node.port().equals(at) && wanted.containsAll(Goods.members(node.goods()))
                && produce.batching().runs(site(), List.of(), node.count(), Optional.empty(), 1) >= 1)
            .min(Comparator.comparingLong(CollectNode::placedAt));
    }

    private final class Promised implements Hold {

        private final CollectNode node;
        private final Optional<CollectNode> adopted;

        Promised(CollectNode node, Optional<CollectNode> adopted) {
            this.node = node;
            this.adopted = adopted;
        }

        @Override
        public Optional<RefusalKind> take() {
            synchronized (collecting) {
                if (adopted.isPresent()) {
                    CollectNode sent = adopted.get();
                    if (collecting.get(sent.id()) != sent || !sent.adrift()) {
                        return Optional.of(DispatchRefusal.NOTHING_ARRIVED);
                    }
                    collecting.remove(sent.id());
                } else if (available(node.goods()) < node.count()) {
                    return Optional.of(DispatchRefusal.NOTHING_ARRIVED);
                }
                collecting.put(node.id(), node);
                return Optional.empty();
            }
        }

        @Override
        public void release(Ending how) {
            synchronized (collecting) {
                if (node.placed() && collecting.get(node.id()) == node) {
                    node.setAdrift();
                } else {
                    collecting.remove(node.id());
                }
            }
        }
    }

    private long available(ItemSpec spec) {
        long supply = reach.filter(NetworkSnapshot.Reach::manned)
            .map(from -> from.supply().stream()
                .filter(lot -> Goods.matches(lot.item(), spec))
                .mapToLong(PackageNetwork.Supply::count).sum()).orElse(0L);
        return Math.max(0, Math.min(roomFor(spec), supply) - unplaced(spec));
    }

    // A placed order has already left the network's stock; only those still to be placed hold any of it back.
    private long unplaced(ItemSpec spec) {
        return collecting.values().stream()
            .filter(node -> !node.placed() && node.network().equals(port.from()) && Goods.overlap(node.goods(), spec))
            .mapToLong(CollectNode::count).sum();
    }

    private long roomFor(ItemSpec spec) {
        long freeParcels = Math.max(0, PORT_SLOTS - port.parcels().size());
        return freeParcels * PARCEL_SLOTS * Goods.stackSize(spec);
    }
}
