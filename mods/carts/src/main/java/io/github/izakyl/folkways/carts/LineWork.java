package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.properties.WoodType;

// The work a cart line is run by. Each is ordinary work the plan gives to a hauler: setting a new cart down, taking
// a hand cart in hand, drawing it on to the next mark, and putting goods aboard or taking them off.
final class LineWork {

    private static final Vocation HAULING = Vocations.required(Vocations.HAULING);

    private LineWork() {
    }

    private static NodeSpec.Builder work(UUID id, WorkSite site, Stances stances, Workload work) {
        return NodeSpec.of(id, FolkwaysCartsMod.OWNER, site, stances, work).vocation(HAULING);
    }

    private static Stances around(ServerLevel level, Entity entity) {
        BlockPos at = entity.blockPosition();
        return Stances.of(level, Reach.workableCells(level, at)).orElse(Stances.at(level, at));
    }

    private static int reach() {
        return FolkwaysConfig.haulReachTicks();
    }

    // Whether this worker has the hand cart hitched.
    static boolean drawnBy(ServerLevel level, LineState state, Worker who) {
        Optional<AbstractCargoCart> cart = state.cart(level);
        if (cart.isEmpty()) {
            return false;
        }
        return cart.get().getPulling() == who.body()
            && CartLinks.pulling(who.body()).filter(cart.get()::equals).isPresent();
    }

    // A new cart of the line's kind, made or taken from stock, set down at its first station for the colony.
    static final class Place implements Node {
        private final NodeSpec spec;
        private final CartLines lines;
        private final Line line;
        private final Line.Mark at;

        Place(CartLines lines, Line line, Line.Mark at) {
            this.lines = lines;
            this.line = line;
            this.at = at;
            this.spec = work(UUID.nameUUIDFromBytes(("folkways:carts/place/" + line.id()).getBytes()),
                new WorkSite.AtBlock(at.at()), at.stances(), Workload.Once.of(reach()))
                .needs(new Need(cartSpec(line.kind()), 1))
                .done();
        }

        static ItemSpec cartSpec(Line.Kind kind) {
            List<ItemSpec> each = kind.items().stream().map(ItemSpec::of).toList();
            return each.size() == 1 ? each.getFirst()
                : new ItemSpec(Optional.empty(), Optional.empty(), java.util.Set.copyOf(each));
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return at.at().in(level) && level.isLoaded(at.at().block(level));
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            ItemStack item = who.supplied().stream()
                .filter(stack -> line.kind().items().contains(stack.getItem())).findFirst().orElse(ItemStack.EMPTY);
            if (item.isEmpty()) {
                return Outcome.failed(CartRefusal.NO_CART);
            }
            Entity made = BuiltInRegistries.ENTITY_TYPE.get(line.kind().entity()).create(level);
            if (!(made instanceof AbstractCargoCart cart)) {
                return Outcome.failed(CartRefusal.NO_CART);
            }
            wood(item).ifPresent(cart::setWoodType);
            cart.moveTo(at.at().cell().getBottomCenter());
            if (!level.addFreshEntity(cart)) {
                return Outcome.failed(CartRefusal.NOWHERE_TO_PLACE);
            }
            if (lines.colony().hold(FolkwaysCartsMod.OWNER, Held.Entity.of(cart)).isEmpty()) {
                cart.discard();
                return Outcome.failed(CartRefusal.NOWHERE_TO_PLACE);
            }
            lines.placed(line.id(), cart.getUUID());
            return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(HAULING, 1)));
        }

        // The wood a cart item is made of, named at the front of its id; the longest name that fits, as dark oak
        // is not oak.
        private static Optional<WoodType> wood(ItemStack item) {
            String path = BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
            return WoodType.values().filter(wood -> path.startsWith(wood.name() + "_"))
                .max(Comparator.comparingInt(wood -> wood.name().length()));
        }
    }

    // The cart taken in hand, to be drawn along the lap.
    static final class Hitch implements Node {
        private final NodeSpec spec;
        private final LineState state;

        Hitch(UUID id, ServerLevel level, LineState state, AbstractCargoCart cart) {
            this.state = state;
            this.spec = work(id, new WorkSite.AtEntity(cart.getUUID(), WorldPos.of(level, cart.blockPosition())),
                around(level, cart), Workload.Once.of(reach())).done();
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return state.cart(level).isPresent();
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            Optional<AbstractCargoCart> cart = state.cart(level);
            if (cart.isEmpty()) {
                return Outcome.failed(CartRefusal.NO_CART);
            }
            if (cart.get().getPulling() != who.body()) {
                cart.get().setPulling(who.body());
            }
            return drawnBy(level, state, who) ? Outcome.done() : Outcome.failed(CartRefusal.NOT_HITCHED);
        }
    }

    // The cart drawn on to the next mark of the lap; at a station it stands there a while for goods.
    static final class Drive implements Node {
        private final NodeSpec spec;
        private final LineState state;
        private final Line.Mark to;

        Drive(UUID id, LineState state, Line.Mark to) {
            this.state = state;
            this.to = to;
            this.spec = work(id, new WorkSite.AtBlock(to.at()), Stances.at(to.at()),
                Workload.Once.of(to.isStation() ? Line.DWELL_TICKS : 0)).done();
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public boolean directTravel() {
            // The line already supplies close waypoints; follow each with the team's live cart navigation.
            return true;
        }

        @Override
        public Recourse failing(ServerLevel level, RefusalKind why) {
            if (why == CartRefusal.NOT_HITCHED || state.cart(level).isEmpty() || !ready(level)) {
                return Recourse.FAIL;
            }
            // A transient blocked path must not complete this lap and reverse the line halfway to its station.
            return Recourse.waitFor(20);
        }

        @Override
        public boolean ready(ServerLevel level) {
            return to.at().in(level) && level.isLoaded(to.at().block(level));
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            if (!drawnBy(level, state, who)) {
                return Outcome.failed(CartRefusal.NOT_HITCHED);
            }
            if (to.isStation() && !(state.near(level, to, LineState.AT_STATION) && state.stopped(level))) {
                return Outcome.failed(CartRefusal.NOT_AT_STATION);
            }
            return Outcome.done();
        }
    }

    // A batch put aboard while the cart stands at the station it is sent from.
    static final class Load implements Node {
        private final NodeSpec spec;
        private final LineState state;
        private final Line line;
        private final LineState.Consignment sent;

        Load(LineState state, Line line, Line.Mark at, LineState.Consignment sent) {
            this.state = state;
            this.line = line;
            this.sent = sent;
            this.spec = work(UUID.randomUUID(), new WorkSite.AtBlock(at.at()), at.stances(),
                Workload.Once.of(reach())).needs(new Need(sent.goods, sent.count)).done();
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return state.aboard.get(sent.id) == sent && state.parkedAt(level, line, sent.from);
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            synchronized (state.aboard) {
                Optional<AbstractCargoCart> cart = state.cart(level);
                if (cart.isEmpty() || !state.parkedAt(level, line, sent.from)) {
                    return Outcome.failed(CartRefusal.NOT_AT_STATION);
                }
                if (state.aboard.get(sent.id) != sent) {
                    return Outcome.failed(CartRefusal.NOT_ABOARD);
                }
                List<ItemStack> goods = who.supplied().stream()
                    .filter(stack -> Goods.matches(stack, sent.goods)).toList();
                if (goods.stream().mapToLong(ItemStack::getCount).sum() < sent.count) {
                    return Outcome.failed(CartRefusal.NOT_ABOARD);
                }
                SimpleContainer trial = new SimpleContainer(cart.get().getContainerSize());
                for (int slot = 0; slot < trial.getContainerSize(); slot++) {
                    trial.setItem(slot, cart.get().getItem(slot).copy());
                }
                if (goods.stream().anyMatch(stack -> !Containers.insert(trial, stack).isEmpty())) {
                    return Outcome.failed(CartRefusal.NO_ROOM);
                }
                goods.forEach(stack -> Containers.insert(cart.get(), stack));
                sent.loaded = true;
                return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(HAULING, 1)));
            }
        }
    }

    // A batch taken off while the cart stands at the station it is sent to, into the pack of whoever carries it on.
    static final class Unload implements Node {
        private final NodeSpec spec;
        private final LineState state;
        private final Line line;
        private final LineState.Consignment sent;

        Unload(LineState state, Line line, Line.Mark at, LineState.Consignment sent) {
            this.state = state;
            this.line = line;
            this.sent = sent;
            this.spec = work(UUID.randomUUID(), new WorkSite.AtBlock(at.at()), at.stances(),
                Workload.Once.of(reach())).gives(new Amount(sent.goods, sent.count)).done();
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public boolean ready(ServerLevel level) {
            // Dispatch the handler while the batch travels. Waiting until the cart parks can leave a handler
            // a whole ride away, unable to reach the station during its dwell.
            return sent.loaded && state.aboard.get(sent.id) == sent && state.cart(level).isPresent()
                && line.station(sent.to).filter(mark -> mark.at().in(level)
                    && level.isLoaded(mark.at().block(level))).isPresent();
        }

        @Override
        public Recourse failing(ServerLevel level, RefusalKind why) {
            return why == CartRefusal.NOT_AT_STATION && ready(level) ? Recourse.waitFor(20) : Recourse.FAIL;
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            synchronized (state.aboard) {
                Optional<AbstractCargoCart> cart = state.cart(level);
                if (cart.isEmpty() || !state.parkedAt(level, line, sent.to)) {
                    return Outcome.failed(CartRefusal.NOT_AT_STATION);
                }
                if (!sent.loaded || state.aboard.get(sent.id) != sent
                        || Goods.countIn(cart.get(), sent.goods) < sent.count) {
                    return Outcome.failed(CartRefusal.NOT_ABOARD);
                }
                List<ItemStack> taken = take(cart.get(), stack -> Goods.matches(stack, sent.goods), sent.count);
                state.aboard.remove(sent.id);
                return new Outcome.Done(List.of(), taken, Optional.of(new Xp(HAULING, 1)));
            }
        }
    }

    // Goods aboard no batch accounts for, set down at whatever station the cart stands at, for the colony to store.
    static final class Clear implements Node {
        private final NodeSpec spec;
        private final LineState state;
        private final Line line;

        Clear(UUID id, ServerLevel level, LineState state, Line line, AbstractCargoCart cart) {
            this.state = state;
            this.line = line;
            this.spec = work(id, new WorkSite.AtEntity(cart.getUUID(), WorldPos.of(level, cart.blockPosition())),
                around(level, cart), Workload.Once.of(reach())).done();
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return state.parked(level, line).isPresent()
                && state.cart(level).map(cart -> !state.stray(cart).isEmpty()).orElse(false);
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            Optional<AbstractCargoCart> cart = state.cart(level);
            if (cart.isEmpty() || state.parked(level, line).isEmpty()) {
                return Outcome.failed(CartRefusal.NOT_AT_STATION);
            }
            int room = 0;
            for (int slot = 0; slot < who.pack().getContainerSize(); slot++) {
                if (who.pack().getItem(slot).isEmpty()) {
                    room++;
                }
            }
            List<ItemStack> taken = new ArrayList<>();
            for (ItemStack stray : state.stray(cart.get())) {
                if (taken.size() >= room) {
                    break;
                }
                taken.addAll(take(cart.get(), stack -> ItemStack.isSameItemSameComponents(stack, stray),
                    stray.getCount()));
            }
            if (taken.isEmpty()) {
                return Outcome.failed(CartRefusal.NOT_ABOARD);
            }
            return new Outcome.Done(List.of(), taken, Optional.empty());
        }
    }

    // Takes `count` goods out a stack at a time, so none comes out larger than it can be held.
    private static List<ItemStack> take(AbstractCargoCart cart, java.util.function.Predicate<ItemStack> which,
                                        long count) {
        List<ItemStack> taken = new ArrayList<>();
        long left = count;
        while (left > 0) {
            ItemStack one = Containers.extract(cart, which, (int) Math.min(left, 64));
            if (one.isEmpty()) {
                break;
            }
            left -= one.getCount();
            int most = one.getMaxStackSize();
            while (one.getCount() > most) {
                taken.add(one.split(most));
            }
            taken.add(one);
        }
        return taken;
    }
}
