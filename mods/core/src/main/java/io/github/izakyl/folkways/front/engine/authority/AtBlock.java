package io.github.izakyl.folkways.front.engine.authority;

import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

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
        return nearby(player.serverLevel(), player.position(), pos) ? Optional.of(new AtBlock(pos.immutable())) : Optional.empty();
    }

    // A packet names the stored block; the player interacts with its visible, possibly moving world position.
    static boolean nearby(Level level, Vec3 player, BlockPos storage) {
        Vec3 target = WorldSpaces.containing(level, storage)
            .map(frame -> frame.toWorld(Vec3.atCenterOf(frame.local(storage))))
            .orElseGet(() -> Vec3.atCenterOf(storage));
        return player.distanceToSqr(target) <= AT_BLOCK_SQR;
    }

    public BlockPos pos() {
        return pos;
    }
}
