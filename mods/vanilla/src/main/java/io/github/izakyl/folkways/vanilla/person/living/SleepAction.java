package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Reach;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class SleepAction implements Node {

    private final LivingPresence works;
    private final NodeSpec spec;
    private final int ticks;

    SleepAction(LivingPresence works, ServerLevel level, BlockPos bed, int ticks) {
        this.works = works;
        this.ticks = ticks;
        this.spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID, WorkSite.at(level, bed),
                Stances.of(level, Reach.workableCells(level, bed))
                    .orElseGet(() -> Stances.at(level, bed)),
                Workload.Once.of(ticks))
            .gesture(WorkGesture.LIE)
            .effort(WorkEffort.NONE)
            .doing(LivingContent.SLEEPING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        if (!works.slept(who.resident().id(), ticks, level)) {
            return Outcome.failed(LivingRefusal.NOT_ON_THE_BOOKS);
        }
        return Outcome.done();
    }
}
