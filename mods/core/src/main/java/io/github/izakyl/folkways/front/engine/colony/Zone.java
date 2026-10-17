package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record Zone(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {

    public Zone {
        BlockPos low = BlockPos.min(min, max);
        BlockPos high = BlockPos.max(min, max);
        min = low;
        max = high;
    }

    public static Zone over(ColonyZone zone) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos cell : zone.cells()) {
            minX = Math.min(minX, cell.getX());
            minY = Math.min(minY, cell.getY());
            minZ = Math.min(minZ, cell.getZ());
            maxX = Math.max(maxX, cell.getX());
            maxY = Math.max(maxY, cell.getY());
            maxZ = Math.max(maxZ, cell.getZ());
        }
        return new Zone(zone.dimension(), new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ));
    }

    public WorldPos at(BlockPos pos) {
        return WorldPos.of(dimension, pos);
    }

    public boolean contains(WorldPos pos) {
        return pos.in(dimension) && contains(pos.cell());
    }

    public boolean contains(BlockPos pos) {
        return pos.getX() >= min.getX()
            && pos.getX() <= max.getX()
            && pos.getY() >= min.getY()
            && pos.getY() <= max.getY()
            && pos.getZ() >= min.getZ()
            && pos.getZ() <= max.getZ();
    }

}
