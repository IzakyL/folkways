package io.github.izakyl.folkways.vanilla.build.draft;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public record Extent(Vec3i size) {

    public static final int MAX_EDGE = 1024;

    public Extent {
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            throw new IllegalArgumentException("an extent with no cells is not an extent");
        }
    }

    public static Optional<Extent> of(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0 || x > MAX_EDGE || y > MAX_EDGE || z > MAX_EDGE) {
            return Optional.empty();
        }
        return Optional.of(new Extent(new Vec3i(x, y, z)));
    }

    static boolean fits(BoundingBox box, long volume) {
        long x = (long) box.maxX() - box.minX() + 1;
        long y = (long) box.maxY() - box.minY() + 1;
        long z = (long) box.maxZ() - box.minZ() + 1;
        return x > 0 && y > 0 && z > 0 && x <= MAX_EDGE && y <= MAX_EDGE && z <= MAX_EDGE
            && box.maxX() < Integer.MAX_VALUE && box.maxY() < Integer.MAX_VALUE && box.maxZ() < Integer.MAX_VALUE
            && x * y * z <= volume;
    }

    public static Optional<Extent> tightAround(List<BlockPos> offsets) {
        int width = 0;
        int height = 0;
        int depth = 0;
        for (BlockPos offset : offsets) {
            width = Math.max(width, offset.getX() + 1);
            height = Math.max(height, offset.getY() + 1);
            depth = Math.max(depth, offset.getZ() + 1);
        }
        return of(width, height, depth);
    }
}
