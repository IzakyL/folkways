package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Unmet;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.front.api.PastDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

abstract class FarmNode implements Node {

    // One cell of a batch; every cell of a batch is worked from the batch's footing.
    record Chore(UUID id, Batch batch, Job job, FieldChores tending) {
    }

    private final NodeSpec spec;
    private final Chore chore;

    FarmNode(NodeSpec spec, Chore chore) {
        this.spec = spec;
        this.chore = chore;
    }

    static NodeSpec.Builder declare(Chore chore, Workload workload) {
        Batch batch = chore.batch();
        return NodeSpec.of(chore.id(), FarmingContent.ID, batch.at(), batch.stances(), workload)
            .vocation(FarmingContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(chore.job().cell());
    }

    @Override
    public final NodeSpec spec() {
        return spec;
    }

    @Override
    public final Optional<RefusalKind> planned(ServerLevel level) {
        chore.tending().planned(chore);
        return Optional.empty();
    }

    @Override
    public final void ended(ServerLevel level, Ending how) {
        chore.tending().ended(chore, how);
    }

    // The cells of a batch are worked in turn by one hand, not one for the next: a cell left undone leaves the rest.
    @Override
    public final Unmet unmet(UUID before, Ending how) {
        return Unmet.GO_ON;
    }

    @Override
    public final boolean ready(ServerLevel level) {
        return level.isLoaded(chore.job().cell());
    }

    @Override
    public final Outcome commit(ServerLevel level, Worker who) {
        List<ItemStack> supplied = who.supplied();
        Outcome done = work(level, who, chore.job());
        PastDay made = chore.tending().made();
        made.record(spec, done, level.getGameTime());
        if (done instanceof Outcome.Done finished) {
            made.spent(spec, spentOf(supplied, finished.unused()), level.getGameTime());
        }
        return done;
    }

    // What the core handed over and the work did not hand back: the seed a sowing put in the ground.
    private static List<ItemStack> spentOf(List<ItemStack> supplied, List<ItemStack> unused) {
        List<ItemStack> spent = new ArrayList<>(supplied.size());
        supplied.forEach(stack -> spent.add(stack.copy()));
        for (ItemStack back : unused) {
            int left = back.getCount();
            for (ItemStack stack : spent) {
                if (left <= 0) {
                    break;
                }
                if (ItemStack.isSameItemSameComponents(stack, back)) {
                    int taken = Math.min(left, stack.getCount());
                    stack.shrink(taken);
                    left -= taken;
                }
            }
        }
        return spent;
    }

    abstract Outcome work(ServerLevel level, Worker who, Job job);

    final Outcome refuse(FarmRefusal why) {
        return Outcome.failed(why);
    }
}
