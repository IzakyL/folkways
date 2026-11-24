package io.github.izakyl.folkways.core.shell.create.elevator;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.NodeEvaluator;

/** Cabin travel uses the resident kind's navigation when it supplies one. */
public interface CabinMovement {
    static void install(CabinMovement movement) {
        Holder.movement = movement;
    }

    static CabinMovement navigation(Function<BlockPos, NodeEvaluator> evaluators) {
        return new CabinNavigation(evaluators);
    }

    static CabinMovement get() {
        return Holder.movement;
    }

    default void stop(Mob body) {
        body.getNavigation().stop();
        body.getMoveControl().setWantedPosition(body.getX(), body.getY(), body.getZ(), 0);
    }

    default void walk(ServerLevel level, AbstractContraptionEntity vehicle, Mob body, Set<BlockPos> goals) {
    }

    final class Holder {
        private static CabinMovement movement = new CabinMovement() {
        };

        private Holder() {
        }
    }
}
