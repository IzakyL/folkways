package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class JoinNode extends BuildNode {

    JoinNode(BuildJob job, Ends ends) {
        super(job, ends);
    }

    @Override
    boolean finished(ServerLevel level) {
        return Joints.joiner().joined(level, from(level), to(level));
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        BlockPos from = from(level);
        BlockPos to = to(level);
        List<ItemStack> paid = spend(who);
        Joints.Joining joining = Joints.joiner().join(level, from, to, paid);
        if (!joining.joined()) {
            // Track the joiner laid before giving up is spent; the site sees the joint still open and asks again.
            return count(joining.left()) == count(paid) ? refuse(BuildRefusal.NO_WAY_TO_BUILD)
                : new Outcome.Done(List.of(), List.of(), Optional.empty(), joining.left());
        }
        return new Outcome.Done(List.of(WorkNoise.placed(from, level.getBlockState(from)),
            WorkNoise.placed(to, level.getBlockState(to))), List.of(), Optional.of(new Xp(BuildContent.trade(), 1)),
            joining.left());
    }

    private static long count(List<ItemStack> stacks) {
        return stacks.stream().mapToLong(ItemStack::getCount).sum();
    }

    private BlockPos from(ServerLevel level) {
        return job().joint().get(0).block(level);
    }

    private BlockPos to(ServerLevel level) {
        return job().joint().get(1).block(level);
    }
}
