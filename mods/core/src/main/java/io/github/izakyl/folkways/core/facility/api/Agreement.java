package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

// The end of a run that writes signals: anyone, wherever they are, confirms that every signal shows what was written.
// What the world shows may lag the write (a gearshift turning over), so a check that finds it not yet there waits and
// looks again, until its patience runs out.
final class Agreement implements Node {

    private static final int RECHECK_TICKS = 10;

    private final List<Routines.Expect> expected;
    private final NodeSpec spec;
    private int patience;

    Agreement(UUID id, ResourceLocation owner, WorldPos site, List<Routines.Expect> expected, int patience) {
        this.expected = List.copyOf(expected);
        this.patience = patience;
        spec = NodeSpec.of(id, owner, new WorkSite.AtBlock(site), Stances.WHEREVER, Workload.Once.of(0)).done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    /** Whether every signal shows its target now; a signal without feedback is taken at its word. */
    boolean agreed(ServerLevel level) {
        for (Routines.Expect expect : expected) {
            Optional<Signal> signal = Controls.resolve(level, expect.control()).filter(Signal.class::isInstance)
                .map(Signal.class::cast);
            if (signal.isEmpty()) {
                return false;
            }
            Optional<Reading> feedback = signal.get().commandFeedback();
            if (feedback.isPresent() && feedback.get().read(level)
                    .filter(read -> expect.type().agrees(expect.target(), read)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return agreed(level) ? Outcome.done() : Outcome.failed(FacilityRefusal.UNSETTLED);
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        if (why == FacilityRefusal.UNSETTLED && patience > 0) {
            patience -= RECHECK_TICKS;
            return Recourse.waitFor(RECHECK_TICKS);
        }
        return Recourse.FAIL;
    }

    @Override
    public String toString() {
        return "Agreement[" + spec.id() + " " + expected.size() + " signals]";
    }
}
