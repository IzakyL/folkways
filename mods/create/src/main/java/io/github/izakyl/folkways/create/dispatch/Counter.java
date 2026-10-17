package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * The colony's own post: goods a resident carries to a packager no network packs at, packs there by hand, and sends
 * from the port beside it to the pick-up point nearest where they are wanted, for another resident to collect. Every
 * packager the colony's ports stand by is one way to the same goods, holding nothing, so they are offered alike.
 */
record Counter(UUID key, WorkSite site, List<Desk> desks, Map<UUID, CollectNode> collecting, Eta eta,
               PackNode.Posting posting, int reachTicks) implements Workshop {

    // A packager to pack at, the port it sends from, and every pick-up point that port reaches.
    record Desk(WorldPos packager, Stances stances, WorldPos port, List<Depot.Dock> reaches) {

        Desk {
            reaches = List.copyOf(reaches);
        }

        UUID route() {
            return UUID.nameUUIDFromBytes(("folkways:dispatch/hand/" + port).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Override
    public ResourceLocation id() {
        return DispatchContent.ID;
    }

    @Override
    public boolean alike() {
        return true;
    }

    @Override
    public boolean carries() {
        return true;
    }

    @Override
    public List<Refinement.Change> options(Intent intent, Grown graph) {
        if (!(intent instanceof Produce produce)) {
            return List.of();
        }
        ItemSpec spec = produce.wanted();
        int stack = Goods.stackSize(spec);
        if (stack <= 0) {
            return List.of();
        }
        WorkSite near = produce.spec().site();
        synchronized (collecting) {
            Optional<CollectNode> adrift = adrift(produce);
            if (adrift.isPresent()) {
                CollectNode sent = adrift.get();
                Desk desk = desks.stream().filter(one -> sent.network().filter(one.route()::equals).isPresent())
                    .findFirst().orElse(null);
                Depot.Dock dock = desk == null ? null : desk.reaches().stream()
                    .filter(one -> one.at().equals(sent.port())).findFirst().orElse(null);
                if (dock != null) {
                    CollectNode node = CollectNode.sentByHand(dock.at(), dock.stances(), spec, sent.count(),
                        reachTicks, estimate(dock), desk.route(), dock.port().route(), eta, collecting,
                        sent.handOrder()).adopting(sent);
                    return List.of(Refinement.Change.of(Grown.of(node)).holding(new Waited(node, adrift)));
                }
            }
            long granted = produce.runs(site, List.of(), 1, Optional.empty(), (long) Depot.PARCEL_SLOTS * stack);
            List<Refinement.Change> found = new ArrayList<>();
            for (Desk desk : desks) {
                Optional<Depot.Dock> to = desk.reaches().stream()
                    .filter(dock -> dock.sends() && Depot.PORT_SLOTS > dock.port().parcels().size())
                    .min(Comparator.comparingDouble(dock -> apart(dock.at(), near)));
                if (to.isEmpty()) {
                    continue;
                }
                Depot.Dock dock = to.get();
                CollectNode collect = CollectNode.sentByHand(dock.at(), dock.stances(), spec, granted, reachTicks,
                    estimate(dock), desk.route(), dock.port().route(), eta, collecting, order());
                PackNode pack = new PackNode(desk.packager(), desk.stances(), spec, granted, reachTicks, desk.port(),
                    dock.port().route(), collect, posting);
                found.add(new Refinement.Change(new Grown(List.of(pack, collect),
                    List.of(new Before(pack.id(), collect.id()))), List.of(), List.of())
                    .holding(new Waited(collect, Optional.empty())));
            }
            return List.copyOf(found);
        }
    }

    private static double apart(WorldPos one, WorkSite near) {
        WorldPos to = near.where();
        return one.sameRealm(to) ? one.cell().distSqr(to.cell()) : Double.MAX_VALUE;
    }

    private int estimate(Depot.Dock dock) {
        return (int) Math.min(Integer.MAX_VALUE, (long) reachTicks + dock.eta());
    }

    private Optional<CollectNode> adrift(Produce produce) {
        Set<Item> wanted = Goods.members(produce.wanted());
        return collecting.values().stream()
            .filter(node -> node.byHand() && node.adrift() && wanted.containsAll(Goods.members(node.goods()))
                && produce.batching().runs(site, List.of(), node.count(), Optional.empty(), 1) >= 1)
            .min(Comparator.comparingLong(CollectNode::placedAt));
    }

    // The colony's own order numbers run below Create's, which count up from nought.
    private static int order() {
        return ThreadLocalRandom.current().nextInt(Integer.MIN_VALUE, -1);
    }

    // The collection waits on the colony's books for its parcel: dropped once the parcel is sent, it is left adrift
    // for another collection to adopt.
    private final class Waited implements Hold {

        private final CollectNode node;
        private final Optional<CollectNode> adopted;

        Waited(CollectNode node, Optional<CollectNode> adopted) {
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
}
