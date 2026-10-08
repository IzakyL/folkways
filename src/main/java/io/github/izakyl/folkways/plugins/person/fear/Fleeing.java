package io.github.izakyl.folkways.plugins.person.fear;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

public final class Fleeing {

    private static final int RANGE = 10;
    private static final int RISE = 4;

    private Fleeing() {
    }

    public static Optional<BlockPos> awayFrom(Mob body, Optional<Vec3> from) {
        if (!(body instanceof PathfinderMob walker)) {
            return Optional.empty();
        }
        Vec3 to = from
            .map(source -> DefaultRandomPos.getPosAway(walker, RANGE, RISE, source))
            .orElseGet(() -> LandRandomPos.getPos(walker, RANGE, RISE));
        return Optional.ofNullable(to).map(BlockPos::containing);
    }
}
