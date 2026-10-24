package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * One package network as the colony draws on it, answering for everything the network holds: its goods taken by hand
 * out of one of its containers, which wants no one at the desk, or ordered to one of its pick-up points, which does.
 * Every answer is counted against the network's one {@link Tally}.
 */
record Depot(UUID key, WorkSite site, Tally tally, List<Dock> docks, List<Shelf> shelves, Eta eta, int reachTicks)
    implements Workshop {

    static final int PORT_SLOTS = 18;

    static final int PARCEL_SLOTS = 9;

    // A pick-up point of the network: how soon an order lands there, and whether one may be sent there now.
    record Dock(WorldPos at, Stances stances, NetworkSnapshot.Port port, int eta, boolean sends) {
    }

    // A container of the network, and what it held when last read.
    record Shelf(WorldPos at, Stances stances, Map<ItemSpec, Long> held) {

        Shelf {
            held = Map.copyOf(held);
        }

        long heldOf(ItemSpec spec) {
            return held.entrySet().stream().filter(lot -> Goods.overlap(spec, lot.getKey()))
                .mapToLong(Map.Entry::getValue).sum();
        }
    }

    UUID network() {
        return tally.network();
    }

    @Override
    public ResourceLocation id() {
        return DispatchContent.ID;
    }

    // Every answer for one ask is counted on the one tally, whichever container or pick-up point it is from.
    @Override
    public boolean alike() {
        return true;
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
        synchronized (tally.collecting()) {
            for (Dock dock : docks) {
                Optional<CollectNode> sent = adrift(produce, dock);
                if (sent.isPresent()) {
                    CollectNode node = new CollectNode(dock.at(), dock.stances(), spec, sent.get().count(),
                        reachTicks, estimate(dock), sent.get().network(), dock.port().route(), eta,
                        tally.collecting()).adopting(sent.get());
                    return List.of(Refinement.Change.of(Grown.of(node)).holding(new Ordered(node, sent)));
                }
            }
            long ceiling = tally.available(spec);
            if (ceiling <= 0) {
                return List.of();
            }
            long granted = produce.runs(site, List.of(), 1, Optional.empty(), ceiling);
            List<Refinement.Change> found = new ArrayList<>();
            for (Shelf shelf : shelves) {
                long count = Math.min(granted, shelf.heldOf(spec));
                if (count > 0) {
                    TakeNode node = new TakeNode(shelf.at(), shelf.stances(), spec, count, reachTicks, network());
                    found.add(Refinement.Change.of(Grown.of(node)).holding(new Taken(node)));
                }
            }
            if (tally.manned()) {
                for (Dock dock : docks) {
                    long count = Math.min(granted, roomFor(dock, spec));
                    if (dock.sends() && count > 0) {
                        CollectNode node = new CollectNode(dock.at(), dock.stances(), spec, count, reachTicks,
                            estimate(dock), Optional.of(network()), dock.port().route(), eta, tally.collecting());
                        found.add(Refinement.Change.of(Grown.of(node)).holding(new Ordered(node, Optional.empty())));
                    }
                }
            }
            return List.copyOf(found);
        }
    }

    private int estimate(Dock dock) {
        return (int) Math.min(Integer.MAX_VALUE, (long) reachTicks + dock.eta());
    }

    private Optional<CollectNode> adrift(Produce produce, Dock dock) {
        Set<Item> wanted = Goods.members(produce.wanted());
        return tally.collecting().values().stream()
            .filter(node -> node.adrift() && node.port().equals(dock.at())
                && node.network().filter(network()::equals).isPresent()
                && wanted.containsAll(Goods.members(node.goods())) && Goods.overlap(produce.wanted(), node.goods())
                && produce.batching().runs(site, List.of(), node.count(), Optional.empty(), 1) >= 1)
            .min(Comparator.comparingLong(CollectNode::placedAt));
    }

    private static long roomFor(Dock dock, ItemSpec spec) {
        long freeParcels = Math.max(0, PORT_SLOTS - dock.port().parcels().size());
        return freeParcels * PARCEL_SLOTS * Goods.stackSize(spec);
    }

    private final class Ordered implements Hold {

        private final CollectNode node;
        private final Optional<CollectNode> adopted;

        Ordered(CollectNode node, Optional<CollectNode> adopted) {
            this.node = node;
            this.adopted = adopted;
        }

        @Override
        public Optional<RefusalKind> take() {
            Map<UUID, CollectNode> collecting = tally.collecting();
            synchronized (collecting) {
                if (adopted.isPresent()) {
                    CollectNode sent = adopted.get();
                    if (collecting.get(sent.id()) != sent || !sent.adrift()) {
                        return Optional.of(DispatchRefusal.NOTHING_ARRIVED);
                    }
                    collecting.remove(sent.id());
                } else if (tally.available(node.goods()) < node.count()) {
                    return Optional.of(DispatchRefusal.NOTHING_ARRIVED);
                }
                collecting.put(node.id(), node);
                return Optional.empty();
            }
        }

        @Override
        public void release(Ending how) {
            Map<UUID, CollectNode> collecting = tally.collecting();
            synchronized (collecting) {
                if (node.placed() && collecting.get(node.id()) == node) {
                    node.setAdrift();
                } else {
                    collecting.remove(node.id());
                }
            }
        }
    }

    // A take stays on the tally once its goods are out, until the network's stock is read again without them.
    private final class Taken implements Hold {

        private final TakeNode node;

        Taken(TakeNode node) {
            this.node = node;
        }

        @Override
        public Optional<RefusalKind> take() {
            synchronized (tally.collecting()) {
                if (tally.available(node.goods()) < node.count()) {
                    return Optional.of(DispatchRefusal.NO_SUPPLY);
                }
                tally.taking().put(node.id(), node);
                return Optional.empty();
            }
        }

        @Override
        public void release(Ending how) {
            synchronized (tally.collecting()) {
                if (node.takenAt() < 0) {
                    tally.taking().remove(node.id());
                }
            }
        }
    }
}
