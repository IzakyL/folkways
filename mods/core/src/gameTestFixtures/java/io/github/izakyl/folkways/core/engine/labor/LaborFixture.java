package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
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

    public void close() {
        labor.close();
        ColonyWays.forget(colony.colonyId(), level.dimension());
    }
}
