package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// Integrations choose equipment without teaching the colony engine about a particular mod's tools.
public interface SuppressionMethod {
    double range();

    default boolean available(Worker who) {
        return true;
    }

    default Set<BlockPos> footings(ServerLevel level, Worker who, WorldPos target) {
        if (!available(who)) {
            return Set.of();
        }
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos feet : Reach.workableCells(level, target.block(level))) {
            if (Vec3.atBottomCenterOf(feet).add(0,
                    Reach.EYE_HEIGHT, 0)
                    .distanceToSqr(Vec3.atCenterOf(target.block(level))) <= range() * range()
                    && !FireSurvey.fire(level.getBlockState(feet))) {
                cells.add(feet);
            }
        }
        return Set.copyOf(cells);
    }

    default boolean reaches(ServerLevel level, Worker who, WorldPos target) {
        return available(who) && who.body().getEyePosition()
            .distanceToSqr(Vec3.atCenterOf(target.block(level))) <= range() * range()
            && Reach.inSight(level, who.body().blockPosition(), target.block(level));
    }

    Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target);

    // Equipment supply discovery shares the zone's bounded scan and never changes the world here.
    default void inspect(Colony colony, ZoneView zone, ServerLevel level, BlockPos pos, BlockState state) {
    }

    default int fluid(Container pack) {
        return 0;
    }
}
