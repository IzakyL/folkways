package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

// The ground a pen's animals can get to from the ground marked out for them, found the way an animal walks: a step of
// one block up, a drop of any depth it lands from, never onto a fence or a wall, and never through a door or a gate,
// open or shut, since a keeper opens one only to pass. Ground that leads off what was marked out is not fenced, nor is
// marked ground in more than one piece.
record Enclosure(Shape shape, Set<BlockPos> inside) {

    enum Shape {
        // Nothing marked out has ground to stand on.
        BARE,
        // One closed piece of ground.
        FENCED,
        // The marked ground falls into more than one closed piece.
        SPLIT,
        // The ground runs off what was marked out.
        OPEN
    }

    // A pen this small has no room for a keeper among the animals, who works it over the fence instead.
    static final int SMALL = 16;

    private static final double HIGHEST_FLOOR = 1.0D;

    static final Enclosure BARE = new Enclosure(Shape.BARE, Set.of());

    Enclosure {
        inside = Set.copyOf(inside);
    }

    boolean fenced() {
        return shape == Shape.FENCED;
    }

    boolean small() {
        return fenced() && inside.size() < SMALL;
    }

    static Enclosure survey(ServerLevel level, Pen pen) {
        Set<BlockPos> seeds = new LinkedHashSet<>();
        for (BlockPos column : pen.floor()) {
            for (int y = pen.minY(); y <= pen.maxY() + 1; y++) {
                Optional<BlockPos> feet = WorldSpaces.storage(level,
                    pen.anchor().at(new BlockPos(column.getX(), y, column.getZ())));
                if (feet.isPresent() && level.isLoaded(feet.get()) && standable(level, feet.get())) {
                    seeds.add(feet.get().immutable());
                }
            }
        }
        if (seeds.isEmpty()) {
            return BARE;
        }
        List<Set<BlockPos>> pieces = new ArrayList<>();
        for (BlockPos seed : seeds) {
            if (pieces.stream().anyMatch(piece -> piece.contains(seed))) {
                continue;
            }
            Optional<Set<BlockPos>> reached = fill(level, seed, cell -> pen.holds(WorldPos.of(level, cell)));
            if (reached.isEmpty()) {
                return new Enclosure(Shape.OPEN, Set.of());
            }
            // An animal can drop to ground it cannot climb back from: what one reaches from the other is one pen.
            Set<BlockPos> piece = new LinkedHashSet<>(reached.get());
            for (Iterator<Set<BlockPos>> earlier = pieces.iterator(); earlier.hasNext(); ) {
                Set<BlockPos> other = earlier.next();
                if (!Collections.disjoint(other, piece)) {
                    piece.addAll(other);
                    earlier.remove();
                }
            }
            pieces.add(piece);
        }
        return pieces.size() == 1
            ? new Enclosure(Shape.FENCED, pieces.getFirst())
            : new Enclosure(Shape.SPLIT, Set.of());
    }

    // Empty when the ground leads off the marked ground, by a step or a fall, or into ground not loaded.
    private static Optional<Set<BlockPos>> fill(ServerLevel level, BlockPos seed, Predicate<BlockPos> marked) {
        Set<BlockPos> found = new LinkedHashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        found.add(seed);
        open.add(seed);
        while (!open.isEmpty()) {
            BlockPos at = open.poll();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos beside = at.relative(side);
                if (!level.isLoaded(beside)) {
                    return Optional.empty();
                }
                List<BlockPos> next = new ArrayList<>(2);
                // A step up needs room for the head over the lower cell on the way.
                if (passage(level, at.above(2)) && standable(level, beside.above())) {
                    next.add(beside.above());
                }
                if (standable(level, beside)) {
                    next.add(beside);
                } else if (passage(level, beside) && passage(level, beside.above())) {
                    Optional<Optional<BlockPos>> landing = landing(level, beside, marked);
                    if (landing.isEmpty()) {
                        return Optional.empty();
                    }
                    landing.get().ifPresent(next::add);
                }
                for (BlockPos cell : next) {
                    if (!marked.test(cell)) {
                        return Optional.empty();
                    }
                    if (found.add(cell.immutable())) {
                        open.add(cell.immutable());
                    }
                }
            }
        }
        return Optional.of(found);
    }

    // Where a body stepping off into the open air comes down. Empty when it falls below the marked ground; nothing
    // when it comes down on what it cannot stand on, the top of a fence.
    private static Optional<Optional<BlockPos>> landing(ServerLevel level, BlockPos air, Predicate<BlockPos> marked) {
        for (BlockPos cell = air.below(); ; cell = cell.below()) {
            if (!marked.test(cell) || !level.isLoaded(cell)) {
                return Optional.empty();
            }
            if (standable(level, cell)) {
                return Optional.of(Optional.of(cell.immutable()));
            }
            if (!passage(level, cell)) {
                return Optional.of(Optional.empty());
            }
        }
    }

    static boolean standable(BlockGetter blocks, BlockPos feet) {
        if (!Footing.withFeetAt(blocks, feet) || doorway(blocks.getBlockState(feet))
                || doorway(blocks.getBlockState(feet.above()))) {
            return false;
        }
        BlockState floor = blocks.getBlockState(feet.below());
        if (doorway(floor)) {
            return false;
        }
        // A fence or a wall stands taller than a block: what is on top of it is nowhere an animal walks.
        VoxelShape shape = floor.getCollisionShape(blocks, feet.below());
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= HIGHEST_FLOOR;
    }

    private static boolean passage(BlockGetter blocks, BlockPos at) {
        return Footing.isPassable(blocks, at) && !doorway(blocks.getBlockState(at));
    }

    static boolean doorway(BlockState state) {
        return state.getBlock() instanceof FenceGateBlock || state.getBlock() instanceof DoorBlock;
    }
}
