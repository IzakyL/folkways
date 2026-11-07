package io.github.izakyl.folkways.plugins.person.fear;

import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
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
    public boolean directTravel() {
        return true;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return Outcome.done();
    }
}
