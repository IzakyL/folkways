package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.build.ConstructionNeeds;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

/** Adds ground campfires where their smoke can safely reach a hive. */
final class ApiaryCampfires {
    private final ConstructionNeeds construction;

    ApiaryCampfires(Colony colony) {
        construction = new ConstructionNeeds(colony, PastureContent.APIARY);
    }

    void refresh(ColonyView view, List<WorldPos> hives) {
        Map<WorldPos, BlockState> wanted = new LinkedHashMap<>();
        for (WorldPos hive : hives) {
            if (hive.in(view.level())) {
                site(view.level(), hive.block(view.level()))
                    .ifPresent(cell -> wanted.put(WorldPos.of(view.level(), cell), Blocks.CAMPFIRE.defaultBlockState()));
            }
        }
        construction.refresh(view.level(), wanted);
    }

    /** Empty when smoke already works, the ground is out of range, or building would replace an obstacle. */
    static Optional<BlockPos> site(ServerLevel level, BlockPos hive) {
        if (CampfireBlock.isSmokeyPos(level, hive)) {
            return Optional.empty();
        }
        for (int distance = 1; distance <= 5; distance++) {
            BlockPos cell = hive.below(distance);
            BlockPos ground = cell.below();
            if (!level.hasChunkAt(cell) || !level.hasChunkAt(ground)) {
                return Optional.empty();
            }
            BlockState state = level.getBlockState(cell);
            if (!level.getFluidState(cell).isEmpty()
                || !state.canBeReplaced() && !(state.getBlock() instanceof CarpetBlock)) {
                return Optional.empty();
            }
            if (state.canBeReplaced() && level.getFluidState(cell).isEmpty()
                && level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)
                && smokePath(level, hive, distance)) {
                return Optional.of(cell);
            }
        }
        return Optional.empty();
    }

    private static boolean smokePath(ServerLevel level, BlockPos hive, int distance) {
        for (int below = 1; below < distance; below++) {
            BlockPos cell = hive.below(below);
            // Vanilla permits one obstructing block directly above a lit campfire (including carpets).
            if (Shapes.joinIsNotEmpty(Block.box(6, 0, 6, 10, 16, 10),
                    level.getBlockState(cell).getCollisionShape(level, cell, CollisionContext.empty()), BooleanOp.AND)) {
                return below == distance - 1;
            }
        }
        return true;
    }

    void close() {
        construction.close();
    }
}
