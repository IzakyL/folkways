package io.github.izakyl.folkways.plugins.build.draft;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

final class Octants {

    static final int FULL = 0xFF;
    static final int NONE = 0;

    private Octants() {
    }

    static int of(Solid solid, int x, int y, int z) {
        int mask = NONE;
        for (int octant = 0; octant < 8; octant++) {
            if (solid.holds(x + offsetX(octant), y + offsetY(octant), z + offsetZ(octant))) {
                mask |= 1 << octant;
            }
        }
        return mask;
    }

    static int layers(Solid solid, int x, int y, int z) {
        return (solid.holds(x + 0.5D, y + 0.25D, z + 0.5D) ? 0x0F : NONE)
            | (solid.holds(x + 0.5D, y + 0.75D, z + 0.5D) ? 0xF0 : NONE);
    }

    static int of(VoxelShape shape) {
        if (shape.isEmpty()) {
            return NONE;
        }
        List<AABB> boxes = shape.toAabbs();
        int mask = NONE;
        for (int octant = 0; octant < 8; octant++) {
            double x = offsetX(octant);
            double y = offsetY(octant);
            double z = offsetZ(octant);
            for (AABB box : boxes) {
                if (x > box.minX && x < box.maxX && y > box.minY && y < box.maxY
                    && z > box.minZ && z < box.maxZ) {
                    mask |= 1 << octant;
                    break;
                }
            }
        }
        return mask;
    }

    static int count(int mask) {
        return Integer.bitCount(mask & FULL);
    }

    static int apart(int one, int other) {
        return Integer.bitCount((one ^ other) & FULL);
    }

    private static double offsetX(int octant) {
        return (octant & 1) == 0 ? 0.25D : 0.75D;
    }

    private static double offsetZ(int octant) {
        return (octant & 2) == 0 ? 0.25D : 0.75D;
    }

    private static double offsetY(int octant) {
        return (octant & 4) == 0 ? 0.25D : 0.75D;
    }
}
