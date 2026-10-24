package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class VanillaSmother implements SuppressionMethod {
    @Override
    public double range() {
        return 2;
    }

    @Override
    public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
        return Optional.of(new SuppressionSession() {
            @Override
            public Outcome suppress(ServerLevel world, BlockPos at) {
                if (!FireSurvey.fire(world.getBlockState(at))) {
                    return Outcome.done();
                }
                world.removeBlock(at, false);
                world.levelEvent(1009, at, 0);
                return Outcome.done();
            }

            @Override
            public void close() {
            }
        });
    }
}
