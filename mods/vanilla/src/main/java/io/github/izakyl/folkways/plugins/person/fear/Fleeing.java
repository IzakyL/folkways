package io.github.izakyl.folkways.plugins.person.fear;

import io.github.izakyl.folkways.core.api.terms.Footing;
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
        Vec3 to;
        if (from.isPresent()) {
            Vec3 source = from.get();
            to = null;
            double safest = body.position().distanceToSqr(source) + 16;
            for (int attempt = 0; attempt < 8; attempt++) {
                Vec3 candidate = DefaultRandomPos.getPosAway(walker, RANGE, RISE, source);
                if (candidate != null && body.position().distanceToSqr(candidate) >= 16
                        && candidate.distanceToSqr(source) > safest
                        && Footing.withFeetAt(body.level(), BlockPos.containing(candidate))) {
                    to = candidate;
                    safest = candidate.distanceToSqr(source);
                }
            }
        } else { to = LandRandomPos.getPos(walker, RANGE, RISE); }
        return Optional.ofNullable(to).map(BlockPos::containing);
    }
}
