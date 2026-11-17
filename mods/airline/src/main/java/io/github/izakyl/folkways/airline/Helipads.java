package io.github.izakyl.folkways.airline;

import dev.ryanhcode.sable.Sable;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class Helipads {
    private static final int MIN_SIZE = 7;
    private static final int MAX_SIZE = 31;

    private Helipads() {
    }

    public static Optional<Helipad> atCenter(Level level, BlockPos center) {
        if (!level.hasChunkAt(center) || level.isOutsideBuildHeight(center)
                || Sable.HELPER.getContaining(level, center) != null) {
            return Optional.empty();
        }
        return atCenter(AirportSurface.world(level), center);
    }

    static Optional<Helipad> atCenter(AirportSurface view, BlockPos center) {
        for (int size = MIN_SIZE; size <= MAX_SIZE; size += 2) {
            if (matches(view, center, size)) {
                return Optional.of(new Helipad(center.immutable(), size));
            }
        }
        return Optional.empty();
    }

    private static boolean matches(AirportSurface view, BlockPos center, int size) {
        int radius = size / 2;
        int xExtent = 0;
        int zExtent = 0;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos pos = center.offset(x, 0, z);
                if (!view.loaded(pos)) {
                    return false;
                }
                int material = view.material(pos);
                if (Math.abs(x) == radius || Math.abs(z) == radius) {
                    if (material != AirportSurface.WHITE) {
                        return false;
                    }
                } else if (material == AirportSurface.WHITE) {
                    if (Math.abs(x) > radius - 1 || Math.abs(z) > radius - 1) {
                        return false;
                    }
                    xExtent = Math.max(xExtent, Math.abs(x));
                    zExtent = Math.max(zExtent, Math.abs(z));
                } else if (material != AirportSurface.GRAY && material != AirportSurface.YELLOW) {
                    return false;
                }
            }
        }
        return matchesH(view, center, xExtent, zExtent, false)
            || matchesH(view, center, zExtent, xExtent, true);
    }

    private static boolean matchesH(AirportSurface view, BlockPos center, int across, int along, boolean rotated) {
        if (across < 1 || along < 2 || (2.0 * across + 1) / (2 * along + 1) < 0.45
                || (2.0 * across + 1) / (2 * along + 1) > 0.8) {
            return false;
        }
        int inner = 0;
        while (inner <= across && !white(view, center, inner, along, rotated)) {
            inner++;
        }
        int cross = 0;
        while (cross <= along && white(view, center, 0, cross, rotated)) {
            cross++;
        }
        if (inner < 1 || inner > across || cross < 1 || cross > along
                || across - inner + 1 > Math.max(1, (2 * along + 1) / 5)
                || 2 * cross - 1 > Math.max(1, (2 * along + 1) / 5)) {
            return false;
        }
        for (int x = -across; x <= across; x++) {
            for (int z = -along; z <= along; z++) {
                boolean expected = Math.abs(x) >= inner || Math.abs(z) < cross;
                if (white(view, center, x, z, rotated) != expected) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean white(AirportSurface view, BlockPos center, int x, int z, boolean rotated) {
        return view.material(center.offset(rotated ? z : x, 0, rotated ? x : z)) == AirportSurface.WHITE;
    }
}
