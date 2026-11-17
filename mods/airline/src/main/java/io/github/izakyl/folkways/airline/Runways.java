package io.github.izakyl.folkways.airline;

import dev.ryanhcode.sable.Sable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

public final class Runways {
    private Runways() {
    }

    public static Optional<Runway> at(Level level, BlockPos pos) {
        AirportSurface surface = AirportSurface.world(level);
        for (int y = 0; y <= 8; y++) {
            BlockPos start = pos.below(y);
            if (!surface.pavement(start) || Sable.HELPER.getContaining(level, start) != null) {
                continue;
            }
            Set<BlockPos> cells = new HashSet<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty() && cells.size() <= Airport.MAX_SURFACE_BLOCKS) {
                BlockPos cell = queue.removeFirst();
                if (surface.pavement(cell) && cells.add(cell)) {
                    for (Direction direction : AirportSurvey.HORIZONTAL) {
                        queue.add(cell.relative(direction));
                    }
                }
            }
            return diagonal(surface, List.copyOf(cells)).stream()
                .filter(runway -> Airport.footprint(runway).contains(start)).findFirst();
        }
        return Optional.empty();
    }

    static Optional<Runway> scan(AirportSurface surface, BlockPos pos, Direction.Axis axis) {
        Set<BlockPos> cells = new HashSet<>();
        Direction along = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction across = along.getClockWise();
        for (int side = -63; side <= 63; side++) {
            BlockPos probe = pos.relative(across, side);
            if (surface.material(probe) != AirportSurface.WHITE) {
                continue;
            }
            for (int d = -2048; d <= 2048; d++) {
                BlockPos cell = probe.relative(along, d);
                if (surface.material(cell) == AirportSurface.WHITE) {
                    cells.add(cell);
                }
            }
        }
        return diagonal(surface, List.copyOf(cells)).stream()
            .filter(runway -> Airport.footprint(runway).contains(pos)).findFirst();
    }

    private record Edge(BlockPos first, BlockPos last, double dx, double dz, double length, List<BlockPos> cells) {
    }

    /** Long, thin white edge components delimit a runway even when its shoulders are paved. */
    static List<Runway> diagonal(AirportSurface surface, List<BlockPos> cells) {
        Set<BlockPos> white = new HashSet<>();
        cells.stream().filter(pos -> surface.material(pos) == AirportSurface.WHITE).forEach(white::add);
        List<Edge> edges = new ArrayList<>();
        for (BlockPos seed : cells) {
            if (!white.remove(seed)) {
                continue;
            }
            List<BlockPos> component = new ArrayList<>();
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.removeFirst();
                component.add(pos);
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockPos next = pos.offset(x, 0, z);
                        if (white.remove(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
            if (component.size() < 40) {
                continue;
            }
            double cx = component.stream().mapToInt(BlockPos::getX).average().orElseThrow();
            double cz = component.stream().mapToInt(BlockPos::getZ).average().orElseThrow();
            double xx = 0;
            double zz = 0;
            double xz = 0;
            for (BlockPos cell : component) {
                double x = cell.getX() - cx;
                double z = cell.getZ() - cz;
                xx += x * x;
                zz += z * z;
                xz += x * z;
            }
            double angle = Math.atan2(2 * xz, xx - zz) / 2;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            Comparator<BlockPos> order = Comparator.comparingDouble(p -> p.getX() * dx + p.getZ() * dz);
            BlockPos first = component.stream().min(order).orElseThrow();
            BlockPos last = component.stream().max(order).orElseThrow();
            double length = Math.sqrt(first.distSqr(last));
            if (length < 47 || length > 2048) {
                continue;
            }
            double low = component.stream().mapToDouble(p -> -p.getX() * dz + p.getZ() * dx).min().orElseThrow();
            double high = component.stream().mapToDouble(p -> -p.getX() * dz + p.getZ() * dx).max().orElseThrow();
            for (double side : high - low > 1.5 ? new double[] {low + 0.45, high - 0.45}
                    : new double[] {(low + high) / 2}) {
                List<BlockPos> line = component.stream().filter(p ->
                    Math.abs(-p.getX() * dz + p.getZ() * dx - side) <= 0.65).toList();
                BlockPos begin = line.stream().min(order).orElseThrow();
                BlockPos end = line.stream().max(order).orElseThrow();
                double run = Math.sqrt(begin.distSqr(end));
                if (run >= 46) {
                    edges.add(new Edge(begin, end, dx, dz, run, line));
                }
            }
        }
        List<Runway> found = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            Edge a = edges.get(i);
            for (int j = i + 1; j < edges.size(); j++) {
                Edge b = edges.get(j);
                if (a.first().getY() != b.first().getY() || Math.abs(a.length() - b.length()) > 2
                        || Math.abs(a.dx() * b.dz() - a.dz() * b.dx()) > 0.02) {
                    continue;
                }
                double vx = b.first().getX() - a.first().getX();
                double vz = b.first().getZ() - a.first().getZ();
                double span = Math.abs(-vx * a.dz() + vz * a.dx());
                int width = (int) Math.round(span / 2) * 2 + 1;
                if (width < 5 || width > 63 || Math.abs(vx * a.dx() + vz * a.dz()) > 1.5) {
                    continue;
                }
                BlockPos first = midpoint(a.first(), b.first());
                BlockPos last = midpoint(a.last(), b.last());
                Runway runway = fit(surface, first, last, width, a, b);
                if (runway != null && found.stream().noneMatch(r -> Airport.footprint(r).contains(first))) {
                    found.add(runway);
                }
            }
        }
        return List.copyOf(found);
    }

    private static Runway fit(AirportSurface surface, BlockPos first, BlockPos last, int width, Edge a, Edge b) {
        Runway best = null;
        int bestError = Integer.MAX_VALUE;
        Set<BlockPos> actual = new HashSet<>(a.cells());
        actual.addAll(b.cells());
        for (int ax = -1; ax <= 1; ax++) {
            for (int az = -1; az <= 1; az++) {
                for (int bx = -1; bx <= 1; bx++) {
                    for (int bz = -1; bz <= 1; bz++) {
                        Runway candidate = new Runway(first.offset(ax, 0, az), last.offset(bx, 0, bz), width);
                        Footprint area = Airport.footprint(candidate);
                        int error = 0;
                        int matched = 0;
                        for (int x = area.minX(); x <= area.maxX(); x++) {
                            for (int z = area.minZ(); z <= area.maxZ(); z++) {
                                BlockPos pos = new BlockPos(x, first.getY(), z);
                                if (area.contains(pos) && Math.abs(candidate.lateral(pos)) >= width / 2.0 - 1) {
                                    if (actual.contains(pos)) {
                                        matched++;
                                    } else {
                                        error++;
                                    }
                                }
                            }
                        }
                        error += actual.size() - matched;
                        if (error < bestError) {
                            bestError = error;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best != null && validate(surface, best) ? best : null;
    }

    private static BlockPos midpoint(BlockPos a, BlockPos b) {
        return new BlockPos((int) Math.round((a.getX() + b.getX()) / 2.0), a.getY(),
            (int) Math.round((a.getZ() + b.getZ()) / 2.0));
    }

    private static boolean validate(AirportSurface surface, Runway runway) {
        int missing = 0;
        int total = 0;
        for (int d = 0; d <= runway.distance(); d++) {
            for (int side = -runway.width() / 2; side <= runway.width() / 2; side++) {
                total++;
                if (!surface.pavement(runway.cell(d, side))) {
                    missing++;
                }
            }
        }
        if (missing > total / 50) {
            return false;
        }
        // Piano keys are longitudinal stripes, separated from the edge lines and centreline.
        for (boolean reverse : new boolean[] {false, true}) {
            int marks = 0;
            int gaps = 0;
            for (int side = -runway.width() / 2 + 1; side <= runway.width() / 2 - 1; side++) {
                double d = reverse ? runway.distance() - 3 : 3;
                int material = surface.material(runway.cell(d, side));
                marks += material == AirportSurface.WHITE ? 1 : 0;
                gaps += material == AirportSurface.GRAY ? 1 : 0;
            }
            if (marks == 0 || gaps == 0) {
                return false;
            }
        }
        return true;
    }
}
