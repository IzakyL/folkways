package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.front.api.PastDay;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

abstract class PenNode implements Node {

    record Chore(UUID id, Quarry beast, Runnable worked, PastDay made) {
    }

    private final NodeSpec spec;
    private final UUID subject;
    private final Runnable worked;
    private final PastDay made;

    PenNode(NodeSpec spec, UUID subject, PastDay made) {
        this(spec, subject, () -> { }, made);
    }

    PenNode(NodeSpec spec, Chore chore) {
        this(spec, chore.beast().subject(), chore.worked(), chore.made());
    }

    private PenNode(NodeSpec spec, UUID subject, Runnable worked, PastDay made) {
        this.spec = spec;
        this.subject = subject;
        this.worked = worked;
        this.made = made;
    }

    static NodeSpec.Builder declare(Chore chore, Herd.Job job) {
        return NodeSpec.of(chore.id(), PastureContent.ID, chore.beast().at(), chore.beast().stances(),
                Workload.Once.of(job.laborTicks(), PastureContent::haste))
            .vocation(PastureContent.trade())
            .gesture(WorkGesture.SWING)
            .focus(chore.beast().at().cell());
    }

    @Override
    public final NodeSpec spec() {
        return spec;
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        if (how instanceof Ending.Done) {
            worked.run();
        }
    }

    final UUID subject() {
        return subject;
    }

    // What the work made goes on the pasture's own count.
    final Outcome counted(ServerLevel level, Outcome outcome) {
        made.record(spec, outcome, level.getGameTime());
        return outcome;
    }

    final Outcome refuse(PastureRefusal why) {
        return Outcome.failed(why);
    }
}
