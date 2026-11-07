package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.plan.internal.Diagnosis;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** Explicit simulation for integration tests: the normal server hook skips labour in GameTestServer. */
public final class LaborFixture implements AutoCloseable {
    private final ColonyData colony;
    private final ServerLevel level;
    private final ColonyLabor labor;

    public LaborFixture(Colony colony, ServerLevel level) {
        this.colony = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
        this.level = level;
        labor = new ColonyLabor(this.colony, level.dimension());
    }

    public void tick() {
        ColonyWays.advance(colony, level);
        labor.run(level, Lanes.INLINE);
    }

    // Why the last plan left work unassigned or short.
    public Diagnosis diagnosis() {
        return labor.diagnosis();
    }

    public LaborCensus census() {
        return labor.census(level);
    }

    public List<Node> route(Body body) {
        return labor.hands(body, Placement.NOWHERE).route();
    }

    public void close() {
        labor.close();
        ColonyWays.forget(colony.colonyId(), level.dimension());
    }
}
