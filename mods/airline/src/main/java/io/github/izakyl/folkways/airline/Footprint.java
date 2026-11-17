package io.github.izakyl.folkways.airline;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Occupied surface cells, independent of the facility's orientation. Bounds are for indexing only. */
public interface Footprint {
    int minX();

    int minZ();

    int maxX();

    int maxZ();

    int y();

    boolean contains(BlockPos pos);

    default boolean overlaps(Footprint other) {
        if (y() != other.y() || minX() > other.maxX() || maxX() < other.minX()
                || minZ() > other.maxZ() || maxZ() < other.minZ()) {
            return false;
        }
        for (int x = Math.max(minX(), other.minX()); x <= Math.min(maxX(), other.maxX()); x++) {
            for (int z = Math.max(minZ(), other.minZ()); z <= Math.min(maxZ(), other.maxZ()); z++) {
                BlockPos pos = new BlockPos(x, y(), z);
                if (contains(pos) && other.contains(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    default List<BlockPos> samples() {
        return TowerCoverage.samples(new Airport.Rectangle(minX(), minZ(), maxX(), maxZ(), y()));
    }

    record Oriented(Runway runway) implements Footprint {
        private double extentX() {
            return Math.abs(runway.dz()) * runway.width() / 2.0 + Math.abs(runway.dx()) * 0.5;
        }

        private double extentZ() {
            return Math.abs(runway.dx()) * runway.width() / 2.0 + Math.abs(runway.dz()) * 0.5;
        }

        public int minX() {
            return (int) Math.ceil(Math.min(runway.firstThreshold().getX(), runway.secondThreshold().getX()) - extentX());
        }

        public int maxX() {
            return (int) Math.floor(Math.max(runway.firstThreshold().getX(), runway.secondThreshold().getX()) + extentX());
        }

        public int minZ() {
            return (int) Math.ceil(Math.min(runway.firstThreshold().getZ(), runway.secondThreshold().getZ()) - extentZ());
        }

        public int maxZ() {
            return (int) Math.floor(Math.max(runway.firstThreshold().getZ(), runway.secondThreshold().getZ()) + extentZ());
        }

        public int y() {
            return runway.surfaceY();
        }

        public boolean contains(BlockPos pos) {
            double station = runway.station(pos);
            return pos.getY() == y() && station >= -0.5 - 1.0e-8
                && station <= runway.distance() + 0.5 + 1.0e-8
                && Math.abs(runway.lateral(pos)) <= runway.width() / 2.0 + 1.0e-8;
        }

        public List<BlockPos> samples() {
            Set<BlockPos> points = new LinkedHashSet<>();
            double half = (runway.width() - 1) / 2.0;
            for (double d = 0; d < runway.distance(); d += TowerCoverage.SAMPLE_SPACING) {
                points.add(runway.cell(d, -half));
                points.add(runway.cell(d, half));
            }
            for (double side = -half; side <= half; side += TowerCoverage.SAMPLE_SPACING) {
                points.add(runway.cell(0, side));
                points.add(runway.cell(runway.distance(), side));
            }
            points.add(runway.cell(runway.distance(), half));
            points.add(runway.cell(runway.distance(), -half));
            return List.copyOf(points);
        }
    }
}
