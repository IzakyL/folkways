package io.github.izakyl.folkways.front.engine.authority;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

public final class AtBlock {
    private static final double AT_BLOCK_SQR = 64.0D;

    private final BlockPos pos;

    private AtBlock(BlockPos pos) {
        this.pos = pos;
    }

    public static Optional<AtBlock> of(ServerPlayer player, BlockPos pos) {
        if (!player.serverLevel().isLoaded(pos)) {
            return Optional.empty();
        }
        double distanceSqr = player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        return distanceSqr <= AT_BLOCK_SQR ? Optional.of(new AtBlock(pos.immutable())) : Optional.empty();
    }

    public BlockPos pos() {
        return pos;
    }
}
