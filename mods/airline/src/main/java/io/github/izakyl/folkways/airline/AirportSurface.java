package io.github.izakyl.folkways.airline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

// The same exact patterns read either the world or an immutable, tick-budgeted copy of its concrete.
interface AirportSurface {
    TagKey<Block> RUNWAY_ACCESSORIES = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_airline", "runway_accessories"));
    int OTHER = 0;
    int GRAY = 1;
    int WHITE = 2;
    int YELLOW = 3;

    int material(BlockPos pos);

    boolean loaded(BlockPos pos);

    default boolean runwayConcrete(BlockPos pos) {
        int material = material(pos);
        return material == GRAY || material == WHITE;
    }

    default boolean pavement(BlockPos pos) {
        return material(pos) != OTHER;
    }

    static int material(BlockState state) {
        if (state.is(Blocks.GRAY_CONCRETE) || state.is(RUNWAY_ACCESSORIES)) {
            return GRAY;
        }
        if (state.is(Blocks.WHITE_CONCRETE)) {
            return WHITE;
        }
        return state.is(Blocks.YELLOW_CONCRETE) ? YELLOW : OTHER;
    }

    static AirportSurface world(Level level) {
        return new AirportSurface() {
            @Override
            public int material(BlockPos pos) {
                if (!loaded(pos)) {
                    return OTHER;
                }
                var state = level.getBlockState(pos);
                return AirportSurface.material(state);
            }

            @Override
            public boolean loaded(BlockPos pos) {
                return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos);
            }
        };
    }
}
