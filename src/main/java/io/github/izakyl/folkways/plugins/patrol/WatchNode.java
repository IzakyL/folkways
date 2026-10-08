package io.github.izakyl.folkways.plugins.patrol;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.Unmet;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

// Standing a while at one stop of a beat, which wards the ground round it and puts the patroller on duty.
final class WatchNode implements Node {

    static final int WATCH_TICKS = 40;

    private final NodeSpec spec;
    private final WorldPos stop;
    private final PatrolPresence presence;

    WatchNode(UUID id, Stop stop, PatrolPresence presence) {
        this.stop = stop.at();
        this.presence = presence;
        this.spec = NodeSpec.of(id, PatrolContent.ID, new WorkSite.AtBlock(this.stop), stop.stances(),
                Workload.Once.of(WATCH_TICKS))
            .tools(ToolUse.of(PatrolContent.WEAPON))
            .vocation(PatrolContent.trade())
            .doing(PatrolContent.PATROLLING)
            .done();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    // The stops of a lap are kept in turn, not one for the next: a stop not watched leaves the rest to watch.
    @Override
    public Unmet unmet(UUID before, Ending how) {
        return Unmet.GO_ON;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return stop.in(level) && level.isLoaded(stop.block(level));
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        long now = level.getGameTime();
        Wards.ward(level, stop.block(level), now + FolkwaysConfig.patrolWardTicks());
        presence.watched(who.resident().id(), now);
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(PatrolContent.trade(), 1)));
    }
}
