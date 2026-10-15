package io.github.izakyl.folkways.plugins.cookery;

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
        return Stances.of(level, Reach.workableCells(level, at.block(level)));
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
