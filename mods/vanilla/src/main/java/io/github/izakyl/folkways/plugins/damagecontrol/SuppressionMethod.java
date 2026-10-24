package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.state.BlockState;

// Integrations choose equipment without teaching the colony engine about a particular mod's tools.
public interface SuppressionMethod {
    double range();

    default boolean available(Worker who) {
        return true;
    }

    Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target);

    // Equipment supply discovery shares the zone's bounded scan and never changes the world here.
    default void inspect(Colony colony, ZoneView zone, ServerLevel level, BlockPos pos, BlockState state) {
    }

    default int fluid(Container pack) {
        return 0;
    }
}
