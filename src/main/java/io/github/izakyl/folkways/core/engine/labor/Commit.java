package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.plan.Staged;
import java.util.ArrayList;
import java.util.List;
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
        for (Need need : node.spec().needs()) {
            if (need.count() > 0 && !draw(who, need, owed, drawn)) {
                putBack(who, drawn);
                return Outcome.failed(LaborRefusal.GOODS_GONE);
            }
        }
        List<ItemStack> goods = drawn.stream().map(Drawn::stack).toList();
        return settle(level, node, who, goods, () -> putBack(who, drawn));
    }

    private record Drawn(ItemStack stack, Container from) {
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

    // A need the plan set goods aside for is drawn from those, where the plan put them, and nowhere else: the rest
    // of the pack and the stores are other work's, and what is short of them fails the work for the plan to see.
    // A need the plan set nothing aside for takes what matches, carried goods from the pack and anything else
    // also from the stores in reach.
    private static boolean draw(Worker who, Need need, List<Owed> owed, List<Drawn> drawn) {
        long left = need.count();
        boolean planned = false;
        for (Owed one : owed) {
            if (one.left <= 0 || !Goods.overlap(one.draw.spec(), need.spec())
                    || need.carried() && one.draw.from().isPresent()) {
                continue;
            }
            planned = true;
            Container source = one.draw.from().map(stash -> storeAt(who, stash)).orElse(who.pack());
            if (source == null || left <= 0) {
                continue;
            }
            long want = Math.min(left, one.left);
            long got = want - drawFrom(source,
                stack -> Goods.matches(stack, need.spec()) && Goods.matches(stack, one.draw.spec()), want, drawn);
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
                left = drawFrom(store.contents(), matching, left, drawn);
            }
        }
        return left <= 0;
    }

    private static Container storeAt(Worker who, Stash stash) {
        for (Worker.Store store : who.within()) {
            if (store.at().equals(stash.pos())) {
                return store.contents();
            }
        }
        return null;
    }

    // Draws up to `left` matching goods from one container; answers what is still to draw.
    private static long drawFrom(Container source, Predicate<ItemStack> matching, long left, List<Drawn> drawn) {
        while (left > 0) {
            ItemStack taken = Containers.extract(source, matching, (int) Math.min(left, Integer.MAX_VALUE));
            if (taken.isEmpty()) {
                break;
            }
            left -= taken.getCount();
            drawn.add(new Drawn(taken, source));
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
            left = Containers.insert(store.contents(), left);
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
        public int rankOf(String perk) {
            return who.rankOf(perk);
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
