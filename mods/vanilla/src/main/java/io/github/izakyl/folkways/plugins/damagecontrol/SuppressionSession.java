package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.work.Outcome;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public interface SuppressionSession extends AutoCloseable {
    Outcome suppress(ServerLevel level, BlockPos target);

    @Override
    void close();
}
