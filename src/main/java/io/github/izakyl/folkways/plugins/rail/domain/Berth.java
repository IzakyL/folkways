package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import net.minecraft.core.BlockPos;

// Where a train stands at a station: the stop on the track, the way the track runs through it, how wide the train is
// and how far along the track it reaches either side of the stop. The platform is what lies beside the train, never
// on the line it runs along.
public record Berth(WorldPos at, double x, double z, double alongX, double alongZ, double halfWidth,
                    double from, double to) {

    static final double SIDE = 2.0D;

    boolean alongside(BlockPos feet) {
        double dx = feet.getX() + 0.5D - x;
        double dz = feet.getZ() + 0.5D - z;
        double along = dx * alongX + dz * alongZ;
        double aside = Math.abs(dx * alongZ - dz * alongX);
        return aside > halfWidth && aside <= halfWidth + SIDE && along >= from - 0.5D && along <= to + 0.5D;
    }
}
