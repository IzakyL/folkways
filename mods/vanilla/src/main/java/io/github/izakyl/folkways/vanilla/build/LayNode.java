package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Recourse;
import io.github.izakyl.folkways.core.work.api.Unmet;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerLevel;

// Maintenance submits a single placement to the plan; site placements use LayAction directly.
final class LayNode implements Node {
    private final LayAction action;
    private final NodeSpec spec;
    private final BiConsumer<Node, Ending> ends;

    LayNode(BuildJob job, BiConsumer<Node, Ending> ends) {
        action = new LayAction(job);
        this.ends = ends;
        BuildAction.Spec work = action.spec();
        spec = NodeSpec.of(UUID.randomUUID(), BuildContent.ID, work.site(), work.stances(), work.workload())
            .needs(work.needs()).leaves(work.leaves()).tools(work.tools()).vocation(BuildContent.trade())
            .gesture(work.gesture()).focus(job.focus().cell()).doing(job.doing(), job.block()).done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return action.ready(level);
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return action.commit(level, who);
    }

    @Override
    public Recourse failing(ServerLevel level, RefusalKind why) {
        return why == BuildRefusal.NO_WAY_TO_BUILD ? Recourse.FAIL
            : Recourse.waitFor(why == BuildRefusal.OCCUPIED ? 20 : 100);
    }

    @Override
    public Unmet unmet(UUID before, Ending how) {
        return how instanceof Ending.Revoked ? Unmet.GO_ON : Unmet.WAIT;
    }

    @Override
    public void ended(ServerLevel level, Ending how) {
        ends.accept(this, how);
    }
}
