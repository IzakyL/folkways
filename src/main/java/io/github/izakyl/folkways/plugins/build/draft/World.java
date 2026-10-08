package io.github.izakyl.folkways.plugins.build.draft;

import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public interface World {

    Optional<BlockState> block(BlockPos at);

    OptionalInt top(int x, int z);

    default int floor() {
        return Integer.MIN_VALUE;
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

    static World of(LevelReader level) {
        return new World() {
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
                return OptionalInt.of(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }

            @Override
            public int floor() {
                return level.getMinBuildHeight();
            }
        };
    }
}
