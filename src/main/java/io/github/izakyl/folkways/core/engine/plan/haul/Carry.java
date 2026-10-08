package io.github.izakyl.folkways.core.engine.plan.haul;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.plan.Carrying;
import io.github.izakyl.folkways.core.engine.plan.Edge;
import io.github.izakyl.folkways.core.engine.plan.Flow;
import io.github.izakyl.folkways.core.engine.plan.Laying;
import io.github.izakyl.folkways.core.engine.plan.Manifest;
import io.github.izakyl.folkways.core.engine.plan.Resource;
import io.github.izakyl.folkways.core.engine.plan.Transfer;
import io.github.izakyl.folkways.core.engine.plan.Transfer.From;
import io.github.izakyl.folkways.core.engine.plan.Transfer.To;
import io.github.izakyl.folkways.core.engine.plan.Why;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode.Endpoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/**
 * How goods move on the plan: the core's rule for a transfer. Goods within reach of the work as they lie, already in
 * the pack of whoever does it, or handed on as they are made take no work to move; everything else is moved by takes
 * into a pack and puts out of one, as few as one pack's carries allow.
 */
final class Carry implements Carrying {

    @Override
    public long lay(Transfer transfer, Laying plan) {
        return switch (transfer.to()) {
            case To.Feeding(Edge edge) -> switch (transfer.from()) {
                case From.Stocked(Stash where) ->
                    fromStash(plan, where, edge, transfer.goods(), transfer.count(), false);
                case From.Made(UUID maker, long yield) ->
                    fromMaker(plan, maker, yield, edge, transfer.goods(), transfer.count());
                case From.Carried(UUID hand, List<ItemStack> stacks) -> fromHand(plan, hand, stacks, edge.consumer());
            };
            case To.Stored(Stash into) -> stow(plan, transfer.from(), into);
        };
    }

    // The consumer's work takes the goods it is fed out of the pack they came in, unless it is a receipt, which keeps
    // them there.
    private static void spend(Laying plan, UUID consumer, ItemSpec concrete, long count) {
        if (!(plan.node(consumer) instanceof Receipt)) {
            plan.carry(consumer, new Amount(concrete, -count));
        }
    }

    // Goods lying in a store go into the work that takes them out; goods still to be made there come from their maker.
    private static void lies(Laying plan, Stash where, UUID to, ItemSpec concrete, long count, boolean made) {
        if (!made) {
            plan.flow(new Flow(new Flow.From.Lying(where), to, concrete, count));
        }
    }

    // These stacks stay in the pack they are in, and the one carrying them does the consumer's work with them.
    private static long fromHand(Laying plan, UUID hand, List<ItemStack> stacks, UUID consumer) {
        long served = 0;
        for (ItemStack stack : stacks) {
            ItemSpec concrete = Goods.specOf(stack);
            spend(plan, consumer, concrete, stack.getCount());
            plan.book(new Resource.Cargo(hand, concrete), consumer, stack.getCount());
            plan.flow(new Flow(new Flow.From.Carried(hand), consumer, concrete, stack.getCount()));
            served += stack.getCount();
        }
        plan.hand(consumer, new Manifest(stacks, List.of()));
        plan.by(consumer, hand);
        return served;
    }

    // Carried goods put away into a store, by whoever carries them.
    private static long stow(Laying plan, From from, Stash into) {
        if (!(from instanceof From.Carried(UUID hand, List<ItemStack> stacks))) {
            throw new IllegalArgumentException("only carried goods are put away: " + from);
        }
        TransferNode put = TransferNode.at(Endpoint.pack(hand), Endpoint.store(into),
            new Manifest(stacks, List.of()), plan.around(into)).orElseThrow();
        Laying.Mark mark = plan.mark();
        plan.add(put);
        long moved = 0;
        for (ItemStack stack : stacks) {
            ItemSpec spec = Goods.specOf(stack);
            plan.book(new Resource.Space(into, spec), put.id(), stack.getCount());
            plan.book(new Resource.Cargo(hand, spec), put.id(), stack.getCount());
            plan.flow(new Flow(new Flow.From.Carried(hand), put.id(), spec, stack.getCount()));
            plan.carry(put.id(), new Amount(spec, -stack.getCount()));
            moved += stack.getCount();
        }
        plan.into(put.id(), into);
        plan.by(put.id(), hand);
        if (!plan.fits(put.id())) {
            plan.rollback(mark);
            plan.because(Why.NO_HAND);
            return 0;
        }
        return moved;
    }

    // From a store: reached into where the consumer stands, relayed into a store it reaches, or fetched into the
    // pack of whoever does it. `made` goods are not there yet: their maker puts them there first.
    private static long fromStash(Laying plan, Stash where, Edge edge, ItemSpec concrete, long count, boolean made) {
        UUID consumer = edge.consumer();
        if (!edge.carried()) {
            Set<Stand> athand = plan.reaching(consumer, where);
            if (!athand.isEmpty()) {
                plan.book(new Resource.Lot(where, concrete), consumer, count);
                lies(plan, where, consumer, concrete, count, made);
                plan.stand(consumer, athand);
                plan.from(consumer, where);
                return count;
            }
            Stash near = landing(plan, where, plan.closure(consumer), consumer, concrete, count);
            if (near != null && relay(plan, consumer, where, near, concrete, count, made)) {
                plan.book(new Resource.Lot(near, concrete), consumer, count);
                plan.stand(consumer, plan.reaching(consumer, near));
                plan.from(consumer, near);
                return count;
            }
        }
        return fetch(plan, where, consumer, concrete, count, made);
    }

    private static long fetch(Laying plan, Stash where, UUID consumer, ItemSpec concrete, long count, boolean made) {
        Set<Stand> loading = plan.around(where);
        if (loading.isEmpty()) {
            plan.because(Why.NO_ROOM);
            return 0;
        }
        if (plan.cutOff(where, loading)) {
            plan.because(Why.NO_WAY);
            return 0;
        }
        long riding = Math.min(count, plan.perTrip(concrete));
        Manifest goods = manifest(plan, where, concrete, riding, made);
        Optional<UUID> recipient = plan.node(consumer).worker();
        TransferNode fetch = TransferNode.at(Endpoint.store(where), new Endpoint.Pack(recipient), goods, loading)
            .orElse(null);
        if (fetch == null) {
            plan.because(Why.NO_ROOM);
            return 0;
        }
        Laying.Mark mark = plan.mark();
        plan.add(fetch);
        plan.book(new Resource.Lot(where, concrete), fetch.id(), riding);
        plan.carry(fetch.id(), new Amount(concrete, riding));
        plan.from(fetch.id(), where);
        spend(plan, consumer, concrete, riding);
        plan.book(new Resource.Handed(fetch.id(), concrete), consumer, riding);
        plan.hand(consumer, goods);
        plan.from(consumer, where);
        plan.together(fetch.id(), consumer);
        lies(plan, where, fetch.id(), concrete, riding, made);
        plan.flow(Flow.from(fetch.id(), consumer, concrete, riding));
        if (!plan.fits(consumer)) {
            plan.rollback(mark);
            plan.because(Why.NO_HAND);
            return 0;
        }
        return riding;
    }

    // Whether the goods could be relayed at all: every carry of them is one hauler's, and someone must be fit for it.
    private static boolean relay(Laying plan, UUID consumer, Stash out, Stash into, ItemSpec concrete, long count,
                                 boolean made) {
        Laying.Mark mark = plan.mark();
        for (long moved = 0; moved < count; ) {
            long load = Math.min(plan.perTrip(concrete), count - moved);
            Manifest goods = manifest(plan, out, concrete, load, made);
            TransferNode take = TransferNode.at(Endpoint.store(out), Endpoint.pack(), goods,
                plan.around(out)).orElseThrow();
            TransferNode put = TransferNode.at(Endpoint.pack(), Endpoint.store(into), goods,
                plan.around(into)).orElseThrow();
            plan.add(take);
            plan.add(put);
            plan.book(new Resource.Lot(out, concrete), take.id(), load);
            plan.book(new Resource.Space(into, concrete), put.id(), load);
            plan.carry(take.id(), new Amount(concrete, load));
            plan.from(take.id(), out);
            plan.carry(put.id(), new Amount(concrete, -load));
            plan.book(new Resource.Handed(take.id(), concrete), put.id(), load);
            plan.into(put.id(), into);
            plan.together(take.id(), put.id());
            lies(plan, out, take.id(), concrete, load, made);
            plan.flow(Flow.from(take.id(), put.id(), concrete, load));
            plan.flow(Flow.from(put.id(), consumer, concrete, load));
            if (!plan.fits(take.id())) {
                plan.rollback(mark);
                plan.because(Why.NO_HAND);
                return false;
            }
            moved += load;
        }
        return true;
    }

    // From its maker: handed over when one worker can carry what is made on to the consumer, or else put into a
    // store the maker reaches and carried on from there.
    private static long fromMaker(Laying plan, UUID maker, long yield, Edge edge, ItemSpec concrete, long count) {
        UUID consumer = edge.consumer();
        if (plan.putsInto(maker).isEmpty()) {
            Laying.Mark mark = plan.mark();
            plan.carry(maker, new Amount(concrete, yield));
            spend(plan, consumer, concrete, count);
            plan.book(new Resource.Handed(maker, concrete), consumer, count);
            plan.hand(consumer, new Manifest(List.of(), List.of(new Need(concrete, count))));
            plan.together(maker, consumer);
            plan.flow(Flow.from(maker, consumer, concrete, count));
            // Handed over only by someone with room for it now; a pack still to be emptied relays it instead.
            if (plan.fitsNow(maker)) {
                return count;
            }
            plan.rollback(mark);
        }
        for (Stash out : plan.closure(maker)) {
            Set<Stand> at = plan.reaching(maker, out);
            if (at.isEmpty() || plan.roomFor(out, concrete) < yield || !plan.feeds(out, consumer, concrete)
                    || !leaves(plan, out, edge, concrete, count)) {
                continue;
            }
            plan.book(new Resource.Space(out, concrete), maker, yield);
            plan.stand(maker, at);
            plan.into(maker, out);
            Laying.Mark before = plan.mark();
            long moved = 0;
            while (moved < count) {
                long step = fromStash(plan, out, edge, concrete, count - moved, true);
                if (step <= 0) {
                    break;
                }
                moved += step;
            }
            boolean picked = false;
            for (UUID id : plan.laidSince(before)) {
                if (plan.node(id) instanceof TransferNode transfer
                        && transfer.spec().site().equals(new WorkSite.AtBlock(out.pos()))) {
                    plan.flow(Flow.from(maker, id, concrete, transfer.amount()));
                    picked = true;
                }
            }
            if (!picked) {
                plan.flow(Flow.from(maker, consumer, concrete, moved));
            }
            return moved;
        }
        if (plan.putsInto(maker).isEmpty()) {
            return carriedOn(plan, maker, yield, edge, concrete, count);
        }
        plan.because(Why.NO_ROOM);
        return 0;
    }

    // No store beside the maker, and no one pack for the maker's work and the consumer's together: whoever makes
    // the goods carries them on to a store the consumer draws from, nearest the consumer first, and the consumer
    // takes them from there as from any store.
    private static long carriedOn(Laying plan, UUID maker, long yield, Edge edge, ItemSpec concrete, long count) {
        UUID consumer = edge.consumer();
        WorkSite near = plan.node(consumer).spec().site();
        List<Stash> stores = new ArrayList<>(plan.stores());
        stores.sort(Comparator.comparingLong((Stash one) ->
                edge.carried() || plan.reaching(consumer, one).isEmpty() ? 1 : 0)
            .thenComparingLong(one -> Transfer.walk(one.pos(), near)));
        for (Stash into : stores) {
            Set<Stand> unloading = plan.around(into);
            if (unloading.isEmpty() || plan.cutOff(into, unloading) || plan.roomFor(into, concrete) < yield
                    || !plan.feeds(into, consumer, concrete) || !leaves(plan, into, edge, concrete, count)) {
                continue;
            }
            List<TransferNode> puts = new ArrayList<>();
            List<Long> loads = new ArrayList<>();
            for (long moved = 0; moved < yield; ) {
                long load = Math.min(plan.perTrip(concrete), yield - moved);
                TransferNode put = TransferNode.at(Endpoint.pack(), Endpoint.store(into),
                    new Manifest(List.of(), List.of(new Need(concrete, load))), unloading).orElse(null);
                if (put == null) {
                    puts.clear();
                    break;
                }
                puts.add(put);
                loads.add(load);
                moved += load;
            }
            if (puts.isEmpty()) {
                continue;
            }
            Laying.Mark mark = plan.mark();
            plan.carry(maker, new Amount(concrete, yield));
            for (int at = 0; at < puts.size(); at++) {
                TransferNode put = puts.get(at);
                long load = loads.get(at);
                plan.add(put);
                plan.book(new Resource.Space(into, concrete), put.id(), load);
                plan.carry(put.id(), new Amount(concrete, -load));
                plan.book(new Resource.Handed(maker, concrete), put.id(), load);
                plan.into(put.id(), into);
                plan.together(maker, put.id());
                plan.flow(Flow.from(maker, put.id(), concrete, load));
            }
            if (!plan.fits(maker)) {
                plan.rollback(mark);
                plan.because(Why.NO_HAND);
                return 0;
            }
            Laying.Mark before = plan.mark();
            long moved = 0;
            while (moved < count) {
                long step = fromStash(plan, into, edge, concrete, count - moved, true);
                if (step <= 0) {
                    break;
                }
                moved += step;
            }
            boolean picked = false;
            for (UUID id : plan.laidSince(before)) {
                if (plan.node(id) instanceof TransferNode transfer
                        && transfer.spec().site().equals(new WorkSite.AtBlock(into.pos()))) {
                    puts.forEach(put -> plan.flow(Flow.from(put.id(), id, concrete, put.amount())));
                    picked = true;
                }
            }
            if (!picked) {
                puts.forEach(put -> plan.flow(Flow.from(put.id(), consumer, concrete, put.amount())));
            }
            return moved;
        }
        plan.because(Why.NO_ROOM);
        return 0;
    }

    // Whether goods put into `out` could go on to the consumer at all, checked before anything is booked there.
    private static boolean leaves(Laying plan, Stash out, Edge edge, ItemSpec concrete, long count) {
        UUID consumer = edge.consumer();
        if (!edge.carried() && (!plan.reaching(consumer, out).isEmpty()
                || landing(plan, out, plan.closure(consumer), consumer, concrete, count) != null)) {
            return true;
        }
        Set<Stand> loading = plan.around(out);
        return !loading.isEmpty() && !plan.cutOff(out, loading);
    }

    // A store among `ins` the goods could be relayed into from `out`, with room for them, that `eater` reaches.
    private static Stash landing(Laying plan, Stash out, Set<Stash> ins, UUID eater, ItemSpec concrete, long count) {
        for (Stash in : ins) {
            if (!in.equals(out) && plan.roomFor(in, concrete) >= count
                && (eater == null || !plan.reaching(eater, in).isEmpty()) && passable(plan, out, in)) {
                return in;
            }
        }
        return null;
    }

    private static boolean passable(Laying plan, Stash out, Stash into) {
        Set<Stand> loading = plan.around(out);
        Set<Stand> unloading = plan.around(into);
        if (loading.isEmpty() || unloading.isEmpty()) {
            plan.because(Why.NO_ROOM);
            return false;
        }
        boolean can = !plan.cutOff(out, loading) && !plan.cutOff(into, unloading);
        if (!can) {
            plan.because(Why.NO_WAY);
        }
        return can;
    }

    private static Manifest manifest(Laying plan, Stash from, ItemSpec spec, long count, boolean made) {
        return made ? new Manifest(List.of(), List.of(new Need(spec, count))) : plan.lying(from, spec, count);
    }
}
