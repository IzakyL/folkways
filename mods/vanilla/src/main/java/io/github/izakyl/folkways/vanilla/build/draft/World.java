package io.github.izakyl.folkways.vanilla.build.draft;

import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public interface World {

    Optional<BlockState> block(BlockPos at);

    OptionalInt top(int x, int z);

    default int floor() {
        return Integer.MIN_VALUE;
    }

    /** Prepares this view on the caller's thread before handing it to a drafting worker. */
    default World snapshot(Area area) {
        return this;
    }

    World NONE = new World() {
        @Override
        public Optional<BlockState> block(BlockPos at) {
            return Optional.of(Blocks.AIR.defaultBlockState());
        }

        @Override
        public OptionalInt top(int x, int z) {
            return OptionalInt.of(Integer.MIN_VALUE);
        }
    };

    /** The area as it stands now, to be drawn over off the level's thread; taken on the level's own thread. */
    static World capture(Level level, Area area) {
        return Captured.of(level, area);
    }

    static World of(Level level) {
        return new World() {
            @Override
            public World snapshot(Area area) {
                return capture(level, area);
            }

            @Override
            public Optional<BlockState> block(BlockPos at) {
                if (!level.hasChunkAt(at.getX(), at.getZ())) {
                    return Optional.empty();
                }
                return Optional.of(level.getBlockState(at));
            }

            @Override
            public OptionalInt top(int x, int z) {
                if (!level.hasChunkAt(x, z)) {
                    return OptionalInt.empty();
                }
                return OptionalInt.of(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z));
            }

            @Override
            public int floor() {
                return level.getMinBuildHeight();
            }
        };
    }
}
