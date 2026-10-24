package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.terms.ObstructedPathRegion;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;

/** Permission belongs to this pathfinding view, this vehicle and its actual stopped deck only. */
final class CabinPathRegion extends ObstructedPathRegion {
    private final UUID vehicle;
    private final Set<Long> deck;

    CabinPathRegion(Level level, BlockPos anchor, PathNavigationRegion world, Map<BlockPos, BlockState> blocks,
                    UUID vehicle, Set<BlockPos> deck) {
        super(level, anchor, world, blocks);
        this.vehicle = vehicle;
        this.deck = deck.stream().map(BlockPos::asLong).collect(Collectors.toUnmodifiableSet());
    }

    boolean permits(UUID other, int x, int y, int z) {
        // Node evaluation also checks the standing body's head clearance.
        return vehicle.equals(other) && (deck.contains(BlockPos.asLong(x, y, z))
            || deck.contains(BlockPos.asLong(x, y - 1, z)));
    }
}
