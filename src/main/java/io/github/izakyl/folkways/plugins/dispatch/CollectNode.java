package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class CollectNode implements Node {

    static final int ADRIFT_TICKS = 2400;

    private final Map<UUID, CollectNode> reservations;
    private final NodeSpec spec;
    private final Vocation hauling = Vocations.required(Vocations.HAULING);
    private final WorldPos at;
    private final ItemSpec want;
    private final long count;
    private final Optional<UUID> networkId;
    private final String address;
    private final Eta eta;
    // A parcel a resident packs and sends by hand carries the colony's own order number, not one of the network's: it
    // is placed when it is sent, and nothing is asked of any network.
    private final OptionalInt byHand;
    private volatile long placedAt = -1;
    private volatile OptionalInt order = OptionalInt.empty();
    private volatile boolean arrived;
    private volatile boolean deferred;
    private volatile boolean adrift;
    private long adriftSince = -1;
    private boolean submitted;
    private volatile Optional<RefusalKind> refused = Optional.empty();

    CollectNode(WorldPos at, Stances stances, ItemSpec want, long count, int reachTicks,
            int estimate, Optional<UUID> networkId, String address, Eta eta,
            Map<UUID, CollectNode> reservations) {
        this(at, stances, want, count, reachTicks, estimate, networkId, address, eta, reservations,
            OptionalInt.empty());
    }

    // The collection of a parcel sent by hand along `route` under the colony's own order number.
    static CollectNode sentByHand(WorldPos at, Stances stances, ItemSpec want, long count, int reachTicks,
                                  int estimate, UUID route, String address, Eta eta,
                                  Map<UUID, CollectNode> reservations, int order) {
        return new CollectNode(at, stances, want, count, reachTicks, estimate, Optional.of(route), address, eta,
            reservations, OptionalInt.of(order));
    }

    private CollectNode(WorldPos at, Stances stances, ItemSpec want, long count, int reachTicks,
            int estimate, Optional<UUID> networkId, String address, Eta eta,
            Map<UUID, CollectNode> reservations, OptionalInt byHand) {
        this.byHand = byHand;
        this.at = at;
        this.reservations = reservations;
        this.want = want;
        this.count = count;
        this.networkId = networkId;
        this.address = address;
        this.eta = eta;
        this.spec = NodeSpec.of(UUID.randomUUID(), DispatchContent.ID, new WorkSite.AtBlock(at),
                stances, Workload.Once.of(reachTicks, DispatchContent::haste))
            .gives(new Amount(want, count))
            .doing(DispatchContent.COLLECTING, want)
            .vocation(hauling)
            .gesture(WorkGesture.SWING)
            .focus(at.cell())
            .estimate(estimate)
            .done();
    }

    static int reachTicks() {
        return FolkwaysConfig.dispatchReachTicks();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    WorldPos port() {
        return at;
    }

    Optional<UUID> network() {
        return networkId;
    }

    ItemSpec goods() {
        return want;
    }

    long count() {
        return count;
    }

    long placedAt() {
        return placedAt;
    }

    boolean waiting() {
        return placedAt >= 0 && !arrived;
    }

    boolean placed() {
        return order.isPresent();
    }

    // Whether this is the order the network packed under that number, its parcels not yet all arrived.
    boolean ordered(int id) {
        return order.isPresent() && order.getAsInt() == id && !arrived;
    }

    boolean owns(PackageNetwork.Parcel parcel) {
        return order.isPresent() && order.equals(parcel.order());
    }

    boolean deferred() {
        return deferred;
    }

    // Its work was dropped after the order left; the parcels stay its own until another collection adopts them.
    boolean adrift() {
        return adrift;
    }

    void setAdrift() {
        adrift = true;
    }

    boolean expired(long now) {
        if (!adrift) {
            return false;
        }
        if (adriftSince < 0) {
            adriftSince = now;
        }
        return now - adriftSince > ADRIFT_TICKS;
    }

    // Takes over an order already on its way, so what it sent is collected instead of ordered again.
    CollectNode adopting(CollectNode sent) {
        order = sent.order;
        placedAt = sent.placedAt;
        submitted = true;
        return this;
    }

    boolean byHand() {
        return byHand.isPresent();
    }

    // A parcel still to be packed by hand may yet go some other way; an order placed or a parcel sent may not.
    @Override
    public boolean replannable() {
        return byHand.isPresent() && !placed();
    }

    // The order number a parcel sent by hand for it is to carry.
    int handOrder() {
        return byHand.orElse(-1);
    }

    // Its parcel was sent by hand: from now it waits on it as on an order placed.
    void sent(long tick) {
        if (byHand.isPresent() && order.isEmpty()) {
            order = byHand;
            placedAt = tick;
        }
    }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level) {
        if (byHand.isPresent()) {
            submitted = true;
            return refused;
        }
        if (submitted) {
            return refused;
        }
        submitted = true;
        return refused = place(level);
    }

    void retry(ServerLevel level) {
        if (byHand.isEmpty() && deferred && refused.isEmpty() && !placed()) {
            refused = place(level);
        }
    }

    // An order long past its time was lost on the way: the route is shut for a while and the need looks elsewhere.
    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        if (refused.isEmpty() && placed() && !arrived && networkId.isPresent()
            && level.getGameTime() - placedAt > Eta.overdueAfter(spec.estimate())) {
            eta.lost(at, networkId.get(), level.getGameTime());
            refused = Optional.of(DispatchRefusal.NOTHING_ARRIVED);
        }
        return refused;
    }

    private Optional<RefusalKind> place(ServerLevel level) {
        PackageNetwork network = PackageNetworks.get();
        if (!at.in(level) || !level.isLoaded(at.block(level))
            || !network.isPort(level.getBlockState(at.block(level)))) {
            return Optional.of(DispatchRefusal.NO_PORT);
        }
        if (networkId.isEmpty()) {
            return Optional.of(DispatchRefusal.NO_SUPPLY);
        }
        List<PackageNetwork.Supply> supplies = new ArrayList<>();
        long left = count;
        for (PackageNetwork.Supply supply : network.summaryOf(level.getServer(), networkId.get())) {
            if (left <= 0) {
                break;
            }
            if (!Goods.matches(supply.item(), want) || supply.count() <= 0) {
                continue;
            }
            long take = Math.min(left, supply.count());
            supplies.add(new PackageNetwork.Supply(supply.item(), take));
            left -= take;
        }
        if (left > 0) {
            return Optional.of(DispatchRefusal.NO_SUPPLY);
        }
        if (network.busy(level.getServer(), networkId.get())) {
            deferred = true;
            return Optional.empty();
        }
        OptionalInt placed = network.request(level.getServer(), networkId.get(), address, supplies);
        if (placed.isEmpty()) {
            if (network.busy(level.getServer(), networkId.get())) {
                deferred = true;
                return Optional.empty();
            }
            return Optional.of(DispatchRefusal.REQUEST_REJECTED);
        }
        deferred = false;
        order = placed;
        placedAt = level.getGameTime();
        return Optional.empty();
    }

    @Override
    public boolean ready(ServerLevel level) {
        PackageNetwork network = PackageNetworks.get();
        if (!at.in(level) || !level.isLoaded(at.block(level))
            || !network.isPort(level.getBlockState(at.block(level)))) {
            return false;
        }
        boolean ready = placed() && present(level, network) >= count;
        if (ready && !arrived) {
            arrived = true;
            if (networkId.isPresent()) {
                eta.took(at, networkId.get(), (int) Math.min(Integer.MAX_VALUE,
                    level.getGameTime() - placedAt));
            }
        }
        return ready;
    }

    private long present(ServerLevel level, PackageNetwork network) {
        long here = 0;
        for (PackageNetwork.Parcel parcel : network.parcelsAt(level, at.block(level))) {
            if (!owns(parcel)) {
                continue;
            }
            for (ItemStack stack : parcel.contents()) {
                if (Goods.matches(stack, want)) {
                    here += stack.getCount();
                }
            }
        }
        return here;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        PackageNetwork network = PackageNetworks.get();
        if (!network.isPort(level.getBlockState(at.block(level)))) {
            return Outcome.failed(DispatchRefusal.NO_PORT);
        }
        if (!placed() || present(level, network) < count) {
            return Outcome.failed(DispatchRefusal.NOTHING_ARRIVED);
        }
        List<ItemStack> taken = network.collectFrom(level, at.block(level), order.getAsInt());
        if (taken.isEmpty()) {
            return Outcome.failed(DispatchRefusal.NOTHING_ARRIVED);
        }
        synchronized (reservations) {
            reservations.remove(id());
        }
        return new Outcome.Done(List.of(Rummaged.at(at.block(level))), taken,
            Optional.of(new Xp(hauling, 1)));
    }
}
