package io.github.izakyl.folkways.plugins.person.fear;

import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class FleeAction implements Node {

    private static final double SPRINT = 1.3;

    private final NodeSpec spec;

    FleeAction(ServerLevel level, UUID fleeing, BlockPos to) {
        this.spec = NodeSpec.of(UUID.randomUUID(), FearContent.ID,
                WorkSite.on(level, fleeing, to), Stances.at(level, to), Workload.Once.of(0))
            .pace(SPRINT)
            .doing(FearContent.FLEEING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return Outcome.done();
    }
}
