package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
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
        if (!works.slept(who.resident().id(), ticks)) {
            return Outcome.failed(LivingRefusal.NOT_ON_THE_BOOKS);
        }
        return Outcome.done();
    }
}
