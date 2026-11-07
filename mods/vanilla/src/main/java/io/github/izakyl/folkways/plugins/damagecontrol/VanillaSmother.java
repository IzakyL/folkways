package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

final class VanillaSmother implements SuppressionMethod {
    static void remove(ServerLevel level, BlockPos at) {
        if (FireSurvey.fire(level.getBlockState(at))) {
            level.removeBlock(at, false);
            level.levelEvent(1009, at, 0);
        }
    }

    @Override
    public double range() {
        return 2;
    }

    @Override
    public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
        if (!reaches(level, who, target)) {
            return Optional.empty();
        }
        return Optional.of(new SuppressionSession() {
            @Override
            public Outcome suppress(ServerLevel world, BlockPos at) {
                if (!FireSurvey.fire(world.getBlockState(at))) {
                    return Outcome.done();
                }
                if (!reaches(world, who, WorldPos.of(world, at))) {
                    return Outcome.failed(DamageControlRefusal.NO_REACH);
                }
                remove(world, at);
                return Outcome.done();
            }

            @Override
            public void close() {
            }
        });
    }
}
