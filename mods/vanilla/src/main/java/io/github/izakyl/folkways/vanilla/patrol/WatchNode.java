package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Unmet;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
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
            .vocation(PatrolContent.trade())
            .doing(PatrolContent.PATROLLING)
            .done();
    }

    @Override
    public boolean mountedWork() {
        return true;
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
        Wards.ward(level, stop.block(level), now + Math.round(FolkwaysVanillaConfig.patrolWardTicks()
                * who.effects().value(PatrolContent.WARD_DURATION)),
            FolkwaysVanillaConfig.patrolWardRadius() + (int) who.effects().value(PatrolContent.WARD_RADIUS),
            (int) who.effects().value(PatrolContent.COMFORT));
        presence.watched(who.resident().id(), now, Math.round(FolkwaysVanillaConfig.patrolWardTicks()
            * who.effects().value(PatrolContent.WARD_DURATION)));
        return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(PatrolContent.trade(), 1)));
    }
}
