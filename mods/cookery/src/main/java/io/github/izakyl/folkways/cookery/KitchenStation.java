package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

// A kitchen appliance supplies its own work; the shared presence owns its registration and lifecycle.
public interface KitchenStation {
    boolean matches(BlockState state);

    default BlockPos anchor(Level level, BlockPos clicked) {
        return clicked.immutable();
    }

    default Optional<Stances> stances(ServerLevel level, WorldPos at) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) { return Optional.empty(); }
        BlockState state = level.getBlockState(at.block(level));
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction side = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            // The stockpot and steamer have their handles across the model's facing axis.
            if (state.is(ModBlocks.STOCKPOT.get()) || state.is(ModBlocks.STEAMER.get())) {
                side = side.getClockWise();
            }
            return operating(level, at, side);
        }
        return Stances.of(level, Reach.workableCells(level, at.block(level)));
    }

    // A counter-top appliance is worked from the handle's adjacent column. The same
    // column also supports appliances placed directly on the floor; never borrow another side.
    static Optional<Stances> operating(ServerLevel level, WorldPos at, Direction side) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) { return Optional.empty(); }
        BlockPos target = at.block(level);
        BlockPos adjacent = target.relative(side);
        return Stances.of(List.of(adjacent.below(), adjacent).stream()
            .filter(cell -> level.isLoaded(cell) && Footing.withFeetAt(level, cell))
            .map(cell -> at.at(at.cell().offset(cell.subtract(target)))).toList());
    }

    static boolean canOperate(ServerLevel level, WorldPos at, net.minecraft.world.entity.Mob body) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) { return false; }
        BlockState state = level.getBlockState(at.block(level));
        Direction side;
        if (state.is(ModBlocks.TEAPOT.get())) {
            // The native tea block has no rotation property. Keep a fixed serving side.
            side = Direction.SOUTH;
        } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            side = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (state.is(ModBlocks.STOCKPOT.get()) || state.is(ModBlocks.STEAMER.get())) {
                side = side.getClockWise();
            }
        } else { return false; }
        return operating(level, at, side).filter(current -> Reach.standingIn(body, current)).isPresent();
    }

    // A multi-block appliance may operate above its registered anchor. Publish all candidate
    // footings, then retain only those that reach every physical point needed by this batch.
    static Optional<Stances> around(ServerLevel level, List<WorldPos> points) {
        return Stances.of(points.stream().filter(point -> point.in(level))
            .flatMap(point -> Reach.workableCells(level, point.block(level)).stream()
                .map(cell -> WorldPos.of(level, cell))).toList());
    }

    static Optional<Stances> reaching(ServerLevel level, Stances offered, List<WorldPos> points) {
        if (points.isEmpty() || points.stream().anyMatch(point -> !point.in(level))) { return Optional.empty(); }
        Stances candidates = offered instanceof Stances.Cells ? offered : around(level, points).orElse(null);
        if (candidates == null) { return Optional.empty(); }
        return Stances.of(candidates.cells().stream().filter(footing ->
            points.stream().allMatch(point -> point.realm().equals(footing.realm()))
                && WorldSpaces.storage(level, footing).filter(cell -> Footing.withFeetAt(level, cell)
                    && points.stream().allMatch(point -> Reach.withinReach(cell, point.block(level))
                        && Reach.inSight(level, cell, point.block(level)))).isPresent()).toList());
    }

    default Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                     Stances stances, KitchenBookings bookings, Runnable changed) {
        return Optional.empty();
    }

    default List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at,
                                 Stances stances, KitchenBookings bookings, Runnable changed) {
        return List.of();
    }

    // A running recovery remains wanted when its approach is temporarily blocked. This must never
    // create a new task: providers return only the original, successfully planned recovery instance.
    default List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
        return List.of();
    }

    default void release(UUID colony, ServerLevel level, WorldPos at) {
    }

    // Runs even after the last station is unmarked: a resident may still be walking out of it.
    default void tick(ServerLevel level, UUID colony) {
    }

    // Shutdown releases running bodies while leaving physical, persistent batches to recover.
    default void stop(UUID colony, Closing why) {
    }

    default Component status(ServerLevel level, WorldPos at) {
        return Component.translatable("folkways.page.cookery.idle");
    }
}
