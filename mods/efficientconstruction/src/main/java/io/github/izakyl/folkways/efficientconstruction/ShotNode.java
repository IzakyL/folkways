package io.github.izakyl.folkways.efficientconstruction;

import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.build.BuildJob;
import io.github.izakyl.folkways.plugins.build.EfficientConstruction;
import io.github.izakyl.folkways.plugins.build.Layering;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * One shot of an order, done by a resident already standing on the site.
 *
 * <p>What it costs is taken by the shot itself, not by the core: its own pack first, then the packs of the other
 * hands standing on the same site. A hand that arrived with the wrong goods is not left idle while another hand
 * standing beside it holds what the block needs, and nothing is moved from pack to pack in advance.
 *
 * <p>Nothing is asked of the colony for the shot ({@code needs} is left empty on purpose): the material already came
 * to the site with the site's own list, and a second reservation for every single block would only compete with it.
 */
final class ShotNode implements Node, Staged {

    private final EfficientBuilder site;
    private final PresenceNode hand;
    private final Layering.Work work;
    private final BuildJob job;
    private final List<Need> cost;
    private final NodeSpec spec;

    ShotNode(EfficientBuilder site, PresenceNode hand, Layering.Work work, BuildJob job, BlockPos stance,
            List<Need> cost, ServerLevel level) {
        this.site = site;
        this.hand = hand;
        this.work = work;
        this.job = job;
        this.cost = List.copyOf(cost);
        this.spec = NodeSpec.of(UUID.randomUUID(), BuildContent.ID,
                new WorkSite.AtBlock(site.target().cell(stance)),
                Stances.at(level, stance),
                Workload.Once.of(EfficientConstruction.settings().ticksPerBlock()))
            .vocation(BuildContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(job.focus().cell())
            .doing(BuildContent.BUILDING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return true;
    }

    // What the block costs, taken from the packs of the hands on the site. A shot whose goods are not there fails
    // rather than laying a block nothing paid for.
    @Override
    public Staged.Drawn draw(ServerLevel level, Worker who) {
        List<ItemStack> goods = new ArrayList<>();
        Container own = who.pack();
        List<Container> pool = new ArrayList<>();
        pool.add(own);
        for (PresenceNode other : site.onSite()) {
            Container pack = other.pack();
            if (pack != null && pack != own) {
                pool.add(pack);
            }
        }
        for (Need need : cost) {
            long left = need.count();
            for (Container pack : pool) {
                if (left <= 0) {
                    break;
                }
                left = take(pack, need, left, goods);
            }
            if (left > 0) {
                giveBack(who, goods);
                return new Staged.Short(LaborRefusal.GOODS_GONE);
            }
        }
        return new Staged.Taken(goods);
    }

    // A shot that failed is paid back into the hand's own pack: the goods stay at the site, and nothing is lost.
    @Override
    public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
        giveBack(who, goods);
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return site.shoot(hand, work, job, level, who);
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        // Someone standing where the shot goes is a moment's wait, not a reason to give the piece up.
        return Recourse.waitFor(20);
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        site.release(work);
    }

    // Takes what is still wanted from one pack, into `goods`: what is still missing when the pack runs out.
    private static long take(Container pack, Need need, long left, List<ItemStack> goods) {
        long wanted = left;
        while (wanted > 0) {
            ItemStack taken = Containers.extract(pack,
                stack -> Goods.matches(stack, need.spec()), (int) Math.min(wanted, Integer.MAX_VALUE));
            if (taken.isEmpty()) {
                break;
            }
            goods.add(taken);
            wanted -= taken.getCount();
        }
        return wanted;
    }

    private static void giveBack(Worker who, List<ItemStack> goods) {
        for (ItemStack stack : goods) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack left = Containers.insert(who.pack(), stack.copy());
            if (!left.isEmpty()) {
                who.spill(left);
            }
        }
    }

    Layering.Work work() {
        return work;
    }

    PresenceNode hand() {
        return hand;
    }

    BlockPos focus() {
        return job.focus().cell();
    }
}
