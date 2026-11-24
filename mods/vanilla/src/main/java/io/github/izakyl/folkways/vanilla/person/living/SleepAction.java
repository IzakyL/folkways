package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

final class SleepAction implements Node {

    private static final int RESTORATIVE_SLEEP_DIVISOR = 4;

    private final LivingPresence works;
    private final NodeSpec spec;
    private final int ticks;
    private int sleptTicks;
    private LivingEntity sleeper;
    private boolean credited;

    SleepAction(LivingPresence works, ServerLevel level, BlockPos bed, int ticks) {
        this.works = works;
        this.ticks = ticks;
        this.spec = NodeSpec.of(UUID.randomUUID(), LivingContent.ID, WorkSite.at(level, bed),
                Stances.of(level, Reach.workableCells(level, bed))
                    .orElseGet(() -> Stances.at(level, bed)),
                Workload.Once.of(ticks))
            .gesture(new WorkGesture() {
                @Override
                public void progress(LivingEntity body, int tick, int duration, BlockPos at) {
                    if (sleeper == null) {
                        sleeper = body;
                        if (bedExists(level)) {
                            WorkGesture.LIE.perform(body, 0, at);
                        }
                    }
                    if (tick < duration && sleeper == body && inBed(level)) {
                        sleptTicks++;
                    }
                }

                @Override
                public void release(LivingEntity body) {
                    if (sleeper != null) {
                        WorkGesture.LIE.release(sleeper);
                        sleeper = null;
                    }
                }
            })
            .effort(WorkEffort.NONE)
            .doing(LivingContent.SLEEPING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean resumesWindup() {
        return true;
    }

    @Override
    public Optional<RefusalKind> refusal(ServerLevel level) {
        return sleeper != null && !inBed(level) ? Optional.of(LaborRefusal.WORK_BROKE) : Optional.empty();
    }

    private boolean bedExists(ServerLevel level) {
        return WorldSpaces.storage(level, spec.site().where())
            .filter(level::isLoaded).filter(at -> Housing.isBed(level.getBlockState(at))).isPresent();
    }

    private boolean inBed(ServerLevel level) {
        return sleeper != null && sleeper.level() == level && sleeper.isAlive() && bedExists(level)
            && sleeper.getSleepingPos().filter(at -> Housing.isBed(level.getBlockState(at))).isPresent();
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        // The workload ran out in bed: credit what the gesture counted, so a tick missed at the edges never costs
        // the whole night.
        if (sleptTicks == 0 || !inBed(level)) {
            return Outcome.failed(LaborRefusal.WORK_BROKE);
        }
        if (!credited) {
            credited = true;
            if (!works.slept(who.resident().id(), sleptTicks, level)) {
                return Outcome.failed(LivingRefusal.NOT_ON_THE_BOOKS);
            }
        }
        return Outcome.done();
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        if (!credited && sleptTicks > 0) {
            credited = true;
            boolean restorative = (long) sleptTicks * RESTORATIVE_SLEEP_DIVISOR >= ticks;
            works.slept(who.resident().id(), sleptTicks, level, restorative);
        }
    }
}
