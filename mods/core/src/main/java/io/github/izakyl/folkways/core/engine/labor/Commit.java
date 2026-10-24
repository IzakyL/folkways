package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.work.InputAllocation;
import io.github.izakyl.folkways.core.api.work.InputReceipt;
import io.github.izakyl.folkways.core.api.work.InputSubstitutions;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.engine.plan.Stores;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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
        if (node instanceof Staged staged) {
            return switch (staged.draw(level, who)) {
                case Staged.Short(var why) -> Outcome.failed(why);
                case Staged.Taken(List<ItemStack> goods) -> settle(level, node, who, goods,
                    () -> staged.restore(level, who, goods));
            };
        }
        List<Drawn> drawn = new ArrayList<>();
        List<Owed> owed = owed(who.placement());
        List<InputReceipt> inputs = new ArrayList<>();
        InputSubstitutions.Snapshot substitutions = InputSubstitutions.snapshot();
        Outcome outcome = Outcome.failed(LaborRefusal.GOODS_GONE);
        InputAllocation.Settlement settled = null;
        try {
            boolean supplied = true;
            for (Need need : node.spec().needs()) {
                if (need.count() > 0 && !draw(who, need, owed, drawn, inputs, substitutions)) {
                    supplied = false;
                    break;
                }
            }
            if (supplied) {
                InputAllocation allocation = new InputAllocation(inputs);
                outcome = node.commit(level, new Supplied(who, allocation.supplied(), allocation.receipts()));
                if (outcome instanceof Outcome.Done done) settled = allocation.settle(done.unused());
            }
        } catch (RuntimeException | LinkageError broken) {
            putBack(who, drawn);
            throw broken;
        }
        if (outcome instanceof Outcome.Done done) {
            settled.returned().forEach(stack -> giveBack(who, stack));
            return new Outcome.Done(done.noises(), done.made(), done.xp(), settled.returned(), settled.consumed());
        }
        putBack(who, drawn);
        return outcome;
    }

    private record Drawn(ItemStack stack, Container from) {
        Drawn { stack = stack.copy(); }
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
            for (Placement.Draw draw : placed.draws()) {
                Owed same = owed.stream().filter(one -> one.draw.from().equals(draw.from())
                    && one.draw.spec().equals(draw.spec()) && one.draw.input().equals(draw.input())).findFirst().orElse(null);
                if (same == null) owed.add(new Owed(draw));
                else same.left += draw.count();
            }
        }
        return owed;
    }

    private static Outcome settle(ServerLevel level, Node node, Worker who, List<ItemStack> goods,
            Runnable undo) {
        Outcome outcome;
        try {
            outcome = node.commit(level, new Supplied(who, goods, goods.stream().map(stack -> new InputReceipt(stack, stack)).toList()));
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

    // Declared draws form a closed budget, even if none can satisfy the input after a reload. Work without
    // planned draws may take carried goods from its pack and other inputs from the stores in reach.
    private static boolean draw(Worker who, Need need, List<Owed> owed, List<Drawn> drawn, List<InputReceipt> inputs,
            InputSubstitutions.Snapshot substitutions) {
        List<InputSubstitutions.Choice> choices = new ArrayList<>();
        choices.add(new InputSubstitutions.Choice(need.spec(), need.spec(), 1));
        choices.addAll(substitutions.choices(need));
        // A planned payment stays planned when its rule disappears; it may never borrow another job's goods.
        boolean planned = !owed.isEmpty();
        long left = need.count();
        for (InputSubstitutions.Choice choice : choices) {
            if (left <= 0) break;
            if (planned) {
                for (Owed one : owed) {
                    if (!valid(one.draw, choice, substitutions)) continue;
                    if (one.left <= 0 || !Goods.overlap(one.draw.spec(), choice.payment())
                            || need.carried() && one.draw.from().isPresent()) continue;
                    Container source = one.draw.from().map(stash -> storeAt(who, stash)).orElse(who.pack());
                    if (source == null) continue;
                    long want = Math.min(left, one.left / choice.cost());
                    OptionalLong remaining = drawFrom(source, stack -> Goods.matches(stack, choice.payment())
                        && Goods.matches(stack, one.draw.spec()), want, choice, drawn, inputs);
                    if (remaining.isEmpty()) return false;
                    long got = want - remaining.getAsLong();
                    one.left -= got * choice.cost();
                    left -= got;
                }
            } else {
                Predicate<ItemStack> matching = stack -> Goods.matches(stack, choice.payment());
                OptionalLong remaining = drawFrom(who.pack(), matching, left, choice, drawn, inputs);
                if (remaining.isEmpty()) return false;
                left = remaining.getAsLong();
                if (!need.carried()) {
                    for (Worker.Store store : who.within()) {
                        if (StoreRule.takes(Stores.rulesAt(store.at()))) {
                            remaining = drawFrom(store.contents(), matching, left, choice, drawn, inputs);
                            if (remaining.isEmpty()) return false;
                            left = remaining.getAsLong();
                        }
                    }
                }
            }
        }
        return left <= 0;
    }

    private static boolean valid(Placement.Draw draw, InputSubstitutions.Choice choice,
            InputSubstitutions.Snapshot substitutions) {
        if (draw.input().isEmpty()) return true;
        Placement.Input input = draw.input().get();
        if (input.cost() != choice.cost()) return false;
        if (input.cost() == 1 && Goods.overlap(input.requested(), draw.spec())) return true;
        // A batch can spend its resource on another suitable piece only while its original payment is valid.
        return substitutions.choices(new Need(input.requested(), 1).replacing()).stream().anyMatch(original ->
            original.cost() == input.cost() && Goods.overlap(original.payment(), draw.spec()));
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
    private static OptionalLong drawFrom(Container source, Predicate<ItemStack> matching, long left,
            InputSubstitutions.Choice choice, List<Drawn> drawn, List<InputReceipt> inputs) {
        Optional<InputAllocation> allocation = InputAllocation.draw(source, matching, left, choice,
            stack -> drawn.add(new Drawn(stack, source)));
        if (allocation.isEmpty()) return OptionalLong.empty();
        inputs.addAll(allocation.get().receipts());
        return OptionalLong.of(left - allocation.get().receipts().stream()
            .mapToLong(input -> input.requested().getCount()).sum());
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
        if (!left.isEmpty()) who.spill(left);
    }

    private record Supplied(Worker who, List<ItemStack> supplied, List<InputReceipt> inputs) implements Worker {

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
