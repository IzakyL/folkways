package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysConfig;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

public final class BlueprintVolume {

    public static final int MAX_EDGE = 512;
    private static long maxVolume() {
        return FolkwaysConfig.maxBlueprintVolume();
    }

    private final Vec3i size;

    private BlueprintVolume(Vec3i size) {
        this.size = size;
    }

    public static Optional<BlueprintVolume> of(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0 || x > MAX_EDGE || y > MAX_EDGE || z > MAX_EDGE) {
            return Optional.empty();
        }
        if ((long) x * y * z > maxVolume()) {
            return Optional.empty();
        }
        return Optional.of(new BlueprintVolume(new Vec3i(x, y, z)));
    }

    public static Optional<BlueprintVolume> around(List<BlockPos> offsets) {
        int x = 0;
        int y = 0;
        int z = 0;
        for (BlockPos offset : offsets) {
            x = Math.max(x, offset.getX() + 1);
            y = Math.max(y, offset.getY() + 1);
            z = Math.max(z, offset.getZ() + 1);
        }
        return of(x, y, z);
    }

    public boolean holds(BlockPos offset) {
        return offset.getX() >= 0 && offset.getY() >= 0 && offset.getZ() >= 0
            && offset.getX() < size.getX() && offset.getY() < size.getY() && offset.getZ() < size.getZ();
    }

    public boolean holdsAll(List<BlockPos> offsets) {
        for (BlockPos offset : offsets) {
            if (!holds(offset)) {
                return false;
            }
        }
        return true;
    }

    public Vec3i size() {
        return size;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BlueprintVolume volume && size.equals(volume.size);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(size);
    }

    @Override
    public String toString() {
        return size.getX() + "x" + size.getY() + "x" + size.getZ();
    }
}
