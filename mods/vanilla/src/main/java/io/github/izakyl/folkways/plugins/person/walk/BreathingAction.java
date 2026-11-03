package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

final class BreathingAction implements Node, Workload.Continuous {
    private final NodeSpec spec;
    private final Breathing breathing;

    BreathingAction(Mob body, Breathing breathing) {
        this.breathing = breathing;
        spec = NodeSpec.of(UUID.randomUUID(), BreathingPresence.ID,
            new WorkSite.AtEntity(body.getUUID(), WorldSpaces.at(body)), Stances.WHEREVER, this)
            .doing(BreathingPresence.ID).done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean holds(ServerLevel level, Worker who) {
        breathing.tick(who.body());
        return breathing.escaping();
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        return Outcome.done();
    }

    @Override
    public void released(ServerLevel level, Worker who, Ending how) {
        who.body().getNavigation().stop();
    }
}
