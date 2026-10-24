package io.github.izakyl.folkways.core.api.ground;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

public interface Ground {

    boolean known(BlockPos cell);

    BlockState state(BlockPos cell);

    LevelReader level();

    boolean standable(BlockPos feet);

    // Prepare this cell's standing geometry before using the synchronous standable() query.
    default boolean prepare(BlockPos feet, Budget budget) {
        return true;
    }

    boolean reachable(BlockPos feet);

    List<BlockPos> stancesFor(BlockPos target);

    // Which stretch of the ground come to holds the cell, or -1 for a cell that is not come to: a stretch is walked
    // both ways between any two of its cells, and not between one of its cells and another stretch's.
    int stretch(BlockPos feet);

    // Every cell come to within arm's reach of the target, seen from there or not, the nearest first.
    List<BlockPos> withinReachOf(BlockPos target);

    Collection<BlockPos> lostSince(Ground earlier);

    Ground with(Map<BlockPos, BlockState> changes);

    // A proof of access to explicit exits, without treating a body's own position as an exit. Each step is
    // traversable in both directions, so a worker can come to the work and leave it by the same passage.
    Access accessFrom(Collection<BlockPos> exits);

    // Starts or reuses an exit proof without requiring its search to finish on this tick. A caller which
    // treats an unproved cell as inaccessible must first check complete(), and discard an invalid proof.
    default Access beginAccess(Collection<BlockPos> exits) {
        return accessFrom(exits);
    }

    default boolean valid() {
        return true;
    }

    // One allowance is shared by all searches and the surrounding planning loops in a tick. A non-positive
    // time limit leaves only the deterministic work allowance, useful for fixtures and synchronous callers.
    final class Budget {
        private final int allowance;
        private final long nanos;
        private final long began;
        private int left;

        public Budget(int work, long nanos) {
            if (work < 0) {
                throw new IllegalArgumentException("negative ground work allowance");
            }
            this.allowance = work;
            this.left = work;
            this.nanos = nanos;
            this.began = nanos > 0 ? System.nanoTime() : 0;
        }

        public static Budget unlimited() {
            return new Budget(Integer.MAX_VALUE, 0);
        }

        public boolean tryStep() {
            if (exhausted()) {
                return false;
            }
            left--;
            return true;
        }

        public boolean exhausted() {
            return left == 0 || nanos > 0 && System.nanoTime() - began >= nanos;
        }

        public int used() {
            return allowance - left;
        }
    }

    interface Access {

        default boolean advance(Budget budget) {
            return complete();
        }

        default boolean complete() {
            return true;
        }

        // A completed bounded search may still have stopped at its cell cap. Only an exhaustive proof
        // permits a caller to use a negative distance as evidence that two places are disconnected.
        default boolean exhaustive() {
            return complete();
        }

        default boolean valid() {
            return true;
        }

        default int visited() {
            return 0;
        }

        // -1 means no route was proved. It proves disconnection only after an exhaustive search.
        int distance(BlockPos feet);

        List<BlockPos> path(BlockPos feet);

        List<BlockPos> stancesFor(BlockPos target);

        boolean canWorkFrom(BlockPos feet, BlockPos target);
    }
}
