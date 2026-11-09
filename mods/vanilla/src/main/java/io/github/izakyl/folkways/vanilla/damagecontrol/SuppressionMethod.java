package io.github.izakyl.folkways.vanilla.damagecontrol;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// Integrations choose equipment without teaching the colony engine about a particular mod's tools.
public interface SuppressionMethod {
    record Plan(Stances stances, Set<WorldPos> destinations, Supplier<Optional<SuppressionSession>> opening,
        Supplier<DamageControlRefusal> refusal) {
        public Plan(Stances stances, Set<WorldPos> destinations, Supplier<Optional<SuppressionSession>> opening) {
            this(stances, destinations, opening, () -> DamageControlRefusal.NO_REACH);
        }

        public Plan {
            destinations = Set.copyOf(destinations);
        }
    }

    default Optional<Plan> plan(ServerLevel level, Worker who, WorldPos target) {
        return Stances.of(level, footings(level, who, target))
            .map(stances -> new Plan(stances, Set.of(),
                () -> available(who) ? open(level, who, target) : Optional.empty(),
                () -> openingRefusal(who)));
    }

    default DamageControlRefusal openingRefusal(Worker who) {
        return DamageControlRefusal.NO_REACH;
    }

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
