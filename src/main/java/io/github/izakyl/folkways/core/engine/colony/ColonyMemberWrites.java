package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.core.api.ground.BlockChanges;
import io.github.izakyl.folkways.core.engine.travel.local.LocalGrounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class ColonyMemberWrites {

    private ColonyMemberWrites() {
    }

    public static void blockChanged(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getServer().isSameThread()) {
            return;
        }
        LocalGrounds.blockChanged(serverLevel, pos);
        BlockChanges.changed(serverLevel, pos);
    }

    public static void observe(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)
                || !serverLevel.getServer().isSameThread()
                || !serverLevel.hasChunkAt(pos)) {
            return;
        }
        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }
        ColonyRegistry registry = ColonyRegistry.get(serverLevel.getServer());
        if (ColonyMembership.owner(blockEntity).filter(registry::isRazed).isPresent()) {
            ColonyMembership.leave(blockEntity);
        }
    }
}
