package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.StockCheck;
import io.github.izakyl.folkways.core.api.work.batch.BatchAction;
import io.github.izakyl.folkways.core.api.work.batch.BatchCommit;
import io.github.izakyl.folkways.core.engine.batch.BatchExecution;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

// A node's commit as the core runs it: take the goods the node needs from where the plan put them, let the
// node change the world with them, and settle what it did not use. A commit that fails, or throws, gets back
// everything it was handed.
public final class Commit {

    private Commit() {
    }

    public static Outcome run(ServerLevel level, Node node, Worker who) {
        return run(level, node, who, null);
    }

    static Outcome transfer(ServerLevel level, Node node, Worker who) {
        if (!BatchExecution.transfer(node)) return Outcome.failed(LaborRefusal.WORK_BROKE);
        // Transfers own their exact physical move and rollback; there are no consumable inputs to draw.
        // Validate before any normal settlement can turn a fabricated unused list into inventory.
        Outcome outcome = node.commit(level, new Supplied(who, List.of()));
        if (outcome instanceof Outcome.Done done && (!done.made().isEmpty() || !done.unused().isEmpty() || done.xp().isPresent())) {
            return Outcome.failed(LaborRefusal.WORK_BROKE);
        }
        return outcome;
    }

    public static Outcome run(ServerLevel level, Node node, Worker who, BatchExecution batch) {
        if (batch != null && !batch.active(node)) return Outcome.failed(LaborRefusal.WORK_BROKE);
        if (batch == null && node instanceof BatchAction) return Outcome.failed(LaborRefusal.WORK_BROKE);
        if (node instanceof StockCheck check) {
            if (batch != null || !StockCheck.valid(node)) return Outcome.failed(LaborRefusal.WORK_BROKE);
            return switch (check.draw(level, who)) {
                case Staged.Short(var why) -> Outcome.failed(why);
                case Staged.Taken(List<ItemStack> goods) -> {
                    if (!goods.isEmpty()) {
                        check.restore(level, who, goods);
                        yield Outcome.failed(LaborRefusal.WORK_BROKE);
                    }
                    Outcome outcome;
                    try {
                        outcome = node.commit(level, new Supplied(who, List.of()));
                    } catch (RuntimeException | LinkageError broken) {
                        check.restore(level, who, List.of());
                        throw broken;
                    }
                    if (outcome instanceof Outcome.Failed) check.restore(level, who, List.of());
                    if (outcome instanceof Outcome.Done done
                            && (!done.made().isEmpty() || !done.unused().isEmpty() || done.xp().isPresent())) {
                        yield Outcome.failed(LaborRefusal.WORK_BROKE);
                    }
                    yield outcome;
                }
            };
        }
        if (node instanceof Staged staged) {
            return switch (staged.draw(level, who)) {
                case Staged.Short(var why) -> Outcome.failed(why);
                case Staged.Taken(List<ItemStack> goods) -> batch == null
                    ? settle(level, node, who, goods, () -> staged.restore(level, who, goods))
                    : settleBatch(level, node, who, goods.stream().map(stack -> new Drawn(stack, null, null, null)).toList(),
                        batch, unused -> staged.restore(level, who, unused), () -> staged.restore(level, who, goods));
            };
        }
        List<Drawn> drawn = new ArrayList<>();
        List<Owed> owed = batch == null ? owed(who.placement())
            : batch.remainingDraws(who.placement().draws()).stream().map(Owed::new).toList();
        for (Need need : node.spec().needs()) {
            if (need.count() > 0 && !draw(who, need, owed, drawn, batch != null)) {
                putBack(who, drawn);
                return Outcome.failed(LaborRefusal.GOODS_GONE);
            }
        }
        List<ItemStack> goods = drawn.stream().map(Drawn::stack).toList();
        return batch == null ? settle(level, node, who, goods, () -> putBack(who, drawn))
            : settleBatch(level, node, who, drawn, batch,
                unused -> putBack(who, returned(drawn, unused)), () -> putBack(who, drawn));
    }

    private record Drawn(ItemStack stack, Container from, Placement.Draw reservation, WorldPos at) {
    }

    // One of the plan's draws for the work, and how much of it the needs drawn so far have left.
    private static final class Owed {
        final Placement.Draw draw;
        long left;
        Owed(Placement.Draw draw) { this.draw = draw; this.left = draw.count(); }
    }

    private static List<Owed> owed(Placement placed) {
        List<Owed> owed = new ArrayList<>();
        if (placed != null) {
            placed.draws().forEach(draw -> owed.add(new Owed(draw)));
        }
        return owed;
    }

    private static Outcome settle(ServerLevel level, Node node, Worker who, List<ItemStack> goods,
            Runnable undo) {
        Outcome outcome;
        try {
            outcome = node.commit(level, new Supplied(who, goods));
        } catch (RuntimeException | LinkageError broken) {
            undo.run();
            throw broken;
        }
        switch (outcome) {
            case Outcome.Done done -> done.unused().forEach(stack -> giveBack(who, stack));
            case Outcome.Failed ignored -> undo.run();
        }
        return outcome;
    }

    private static Outcome settleBatch(ServerLevel level, Node node, Worker who, List<Drawn> drawn,
            BatchExecution batch, Consumer<List<ItemStack>> restoreUnused, Runnable undo) {
        List<ItemStack> goods = drawn.stream().map(Drawn::stack).toList();
        if (!batch.permits(goods) || node instanceof Staged && !batch.permitsPlanned(goods, who.placement().draws())) {
            undo.run();
            return Outcome.failed(LaborRefusal.GOODS_GONE);
        }
        BatchCommit committed;
        try {
            Worker supplied = new Supplied(who, goods);
            committed = node instanceof BatchAction action ? action.execute(level, supplied)
                : BatchCommit.of(node.commit(level, supplied));
        } catch (RuntimeException | LinkageError broken) {
            undo.run();
            throw broken;
        }
        if (committed.outcome() instanceof Outcome.Failed) {
            undo.run();
            return committed.outcome();
        }
        Outcome.Done done = (Outcome.Done) committed.outcome();
        List<Drawn> returned;
        try {
            returned = returned(drawn, done.unused());
        } catch (IllegalArgumentException invalid) {
            // The world has already changed. Never turn a bad unused declaration into a full refund.
            done = new Outcome.Done(done.noises(), done.made(), done.xp(), List.of());
            committed = BatchCommit.accepted(done, LaborRefusal.WORK_BROKE);
            returned = List.of();
        }
        List<BatchExecution.Debit> consumed = new ArrayList<>();
        List<ItemStack> unused = returned.stream().map(piece -> piece.stack().copy()).toList();
        for (Drawn piece : drawn) {
            ItemStack left = piece.stack().copy();
            for (ItemStack back : unused) {
                if (!ItemStack.isSameItemSameComponents(left, back)) continue;
                int count = Math.min(left.getCount(), back.getCount());
                left.shrink(count);
                back.shrink(count);
            }
            if (!left.isEmpty()) {
                Optional<WorldPos> store = Optional.ofNullable(piece.at());
                Optional<java.util.UUID> resident = piece.from() == who.pack() && who.resident() != null
                    ? Optional.of(who.resident().id()) : Optional.empty();
                consumed.add(new BatchExecution.Debit(piece.reservation(), left, store, resident));
            }
        }
        // Record irreversible consumption before calling restoration code or domain notification.
        batch.record(level, node, consumed, done, committed.stop());
        try {
            restoreUnused.accept(done.unused());
        } catch (RuntimeException | LinkageError broken) {
            batch.stop(LaborRefusal.WORK_BROKE);
            Labor.LOGGER.error("batch {} could not restore unused goods after commit", batch.parent(), broken);
        }
        return done;
    }

    private static List<Drawn> returned(List<Drawn> drawn, List<ItemStack> unused) {
        List<ItemStack> available = drawn.stream().map(piece -> piece.stack().copy()).toList();
        List<Drawn> returned = new ArrayList<>();
        for (ItemStack back : unused) {
            if (back.isEmpty()) continue;
            int left = back.getCount();
            for (int i = 0; i < drawn.size() && left > 0; i++) {
                ItemStack held = available.get(i);
                if (!ItemStack.isSameItemSameComponents(back, held)) continue;
                int count = Math.min(left, held.getCount());
                if (count > 0) returned.add(new Drawn(back.copyWithCount(count), drawn.get(i).from(), drawn.get(i).reservation(), drawn.get(i).at()));
                held.shrink(count);
                left -= count;
            }
            if (left > 0) throw new IllegalArgumentException("unused goods must be part of the supplied stacks");
        }
        return returned;
    }

    // A need the plan set goods aside for is drawn from those, where the plan put them, and nowhere else: the rest
    // of the pack and the stores are other work's, and what is short of them fails the work for the plan to see.
    // A need the plan set nothing aside for takes what matches, carried goods from the pack and anything else
    // also from the stores in reach.
    private static boolean draw(Worker who, Need need, List<Owed> owed, List<Drawn> drawn, boolean scoped) {
        long left = need.count();
        boolean planned = false;
        for (Owed one : owed) {
            if (!Goods.overlap(one.draw.spec(), need.spec())
                    || need.carried() && one.draw.from().isPresent()) {
                continue;
            }
            planned = true;
            if (one.left <= 0) continue;
            if (scoped && one.draw.from().isEmpty() && one.draw.resident().isPresent()
                    && !one.draw.resident().get().equals(who.resident().id())) continue;
            Container source = one.draw.from().map(stash -> storeAt(who, stash)).orElse(who.pack());
            if (source == null || left <= 0) {
                continue;
            }
            long want = Math.min(left, one.left);
            long got = want - drawFrom(source,
                stack -> Goods.matches(stack, need.spec()) && Goods.matches(stack, one.draw.spec()), want, drawn, one.draw,
                one.draw.from().map(Stash::pos).orElse(null));
            one.left -= got;
            left -= got;
        }
        if (planned) {
            return left <= 0;
        }
        Predicate<ItemStack> matching = stack -> Goods.matches(stack, need.spec());
        left = drawFrom(who.pack(), matching, left, drawn);
        if (!need.carried()) {
            for (Worker.Store store : who.within()) {
                if (StoreRule.takes(Stores.rulesAt(store.at()))) {
                    left = drawFrom(store.contents(), matching, left, drawn, null, store.at());
                }
            }
        }
        return left <= 0;
    }

    private static Container storeAt(Worker who, Stash stash) {
        for (Worker.Store store : who.within()) {
            if (store.at().equals(stash.pos())
                    && StoreRule.takes(Stores.rulesAt(store.at()))) {
                return store.contents();
            }
        }
        return null;
    }

    // Draws up to `left` matching goods from one container; answers what is still to draw.
    private static long drawFrom(Container source, Predicate<ItemStack> matching, long left, List<Drawn> drawn) {
        return drawFrom(source, matching, left, drawn, null, null);
    }

    private static long drawFrom(Container source, Predicate<ItemStack> matching, long left, List<Drawn> drawn,
                                 Placement.Draw reservation, WorldPos at) {
        while (left > 0) {
            ItemStack taken = Containers.extract(source, matching, (int) Math.min(left, Integer.MAX_VALUE));
            if (taken.isEmpty()) {
                break;
            }
            left -= taken.getCount();
            drawn.add(new Drawn(taken, source, reservation, at));
        }
        return left;
    }

    private static void putBack(Worker who, List<Drawn> drawn) {
        for (Drawn piece : drawn) {
            ItemStack left = Containers.insert(piece.from(), piece.stack());
            if (!left.isEmpty()) {
                giveBack(who, left);
            }
        }
    }

    private static void giveBack(Worker who, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack left = Containers.insert(who.pack(), stack.copy());
        for (Worker.Store store : who.within()) {
            if (left.isEmpty()) {
                break;
            }
            if (StoreRule.stores(Stores.rulesAt(store.at()))) {
                left = Containers.insert(store.contents(), left);
            }
        }
        who.spill(left);
    }

    private record Supplied(Worker who, List<ItemStack> supplied) implements Worker {

        Supplied {
            supplied = supplied.stream().map(ItemStack::copy).toList();
        }

        @Override
        public List<ItemStack> supplied() {
            return supplied.stream().map(ItemStack::copy).toList();
        }

        @Override
        public Resident resident() {
            return who.resident();
        }

        @Override
        public Mob body() {
            return who.body();
        }

        @Override
        public Container pack() {
            return who.pack();
        }

        @Override
        public List<Store> within() {
            return who.within();
        }

        @Override
        public ItemStack held(ToolNeed need) {
            return who.held(need);
        }

        @Override
        public void spill(ItemStack stack) {
            who.spill(stack);
        }

        @Override
        public Effects effects() {
            return who.effects();
        }

        @Override
        public List<Node> route() {
            return who.route();
        }

        @Override
        public Placement placement() {
            return who.placement();
        }
    }
}
