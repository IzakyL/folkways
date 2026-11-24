package io.github.izakyl.folkways.airline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Geometry and copied visibility data; safe to evaluate on the survey worker. */
public final class TowerCoverage {
    public static final int BASE_RADIUS = 32;
    public static final int RADIUS_PER_HEIGHT = 8;
    public static final int MAX_RADIUS = 384;
    public static final int SAMPLE_SPACING = 8;
    public static final double SECTOR_DEGREES = 120;
    private static final double HALF_SECTOR_COS = Math.cos(Math.toRadians(SECTOR_DEGREES / 2));

    private TowerCoverage() {
    }

    public record Tower(BlockPos pos, Direction facing, UUID colony, boolean staffed) {
        public Direction scanDirection() {
            return facing.getOpposite();
        }

        public int radius(int targetY) {
            int height = pos.getY() - targetY;
            return height <= 0 ? 0 : Math.min(MAX_RADIUS, BASE_RADIUS + RADIUS_PER_HEIGHT * height);
        }

        public String geometry(BlockPos target) {
            long dx = (long) target.getX() - pos.getX();
            long dz = (long) target.getZ() - pos.getZ();
            int radius = radius(target.getY());
            double distance = Math.hypot(dx, dz);
            if (radius == 0 || distance > radius) {
                return "distance";
            }
            double forward = dx * scanDirection().getStepX() + dz * scanDirection().getStepZ();
            return forward + 1.0e-9 < distance * HALF_SECTOR_COS ? "sector" : null;
        }
    }

    public record Sample(BlockPos pos, Set<BlockPos> towers) {
        public Sample {
            towers = Set.copyOf(towers);
        }
    }

    public record Facility(Footprint area, List<Sample> samples, String reason) {
        public Facility {
            samples = List.copyOf(samples);
        }

        public boolean covered() {
            return samples.stream().allMatch(sample -> !sample.towers().isEmpty());
        }

    }

    public static List<BlockPos> samples(Footprint area) {
        if (area instanceof Footprint.Oriented) {
            return area.samples();
        }
        Set<BlockPos> points = new LinkedHashSet<>();
        for (int x = area.minX(); x < area.maxX(); x += SAMPLE_SPACING) {
            points.add(new BlockPos(x, area.y(), area.minZ()));
            points.add(new BlockPos(x, area.y(), area.maxZ()));
        }
        for (int z = area.minZ(); z < area.maxZ(); z += SAMPLE_SPACING) {
            points.add(new BlockPos(area.minX(), area.y(), z));
            points.add(new BlockPos(area.maxX(), area.y(), z));
        }
        points.add(new BlockPos(area.maxX(), area.y(), area.maxZ()));
        points.add(new BlockPos(area.maxX(), area.y(), area.minZ()));
        points.add(new BlockPos(area.minX(), area.y(), area.maxZ()));
        return List.copyOf(points);
    }

    // Only a full occlusion shape stops a sight ray. Transparent and partial blocks remain visible through.
    public static boolean blocksSight(BlockState state, BlockGetter world, BlockPos pos) {
        return state.canOcclude() && Block.isShapeFullBlock(state.getOcclusionShape(world, pos));
    }

    /** Exact voxel traversal from the top of the tower to just above the facility surface. */
    static List<BlockPos> ray(BlockPos tower, BlockPos target) {
        Vec3 start = new Vec3(tower.getX() + 0.5, tower.getY() + 0.999, tower.getZ() + 0.5);
        Vec3 end = new Vec3(target.getX() + 0.5, target.getY() + 1.001, target.getZ() + 0.5);
        Vec3 delta = end.subtract(start);
        int x = tower.getX();
        int y = tower.getY();
        int z = tower.getZ();
        BlockPos last = BlockPos.containing(end);
        int sx = delta.x > 0 ? 1 : -1;
        int sy = delta.y > 0 ? 1 : -1;
        int sz = delta.z > 0 ? 1 : -1;
        double tx = delta.x == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(delta.x);
        double ty = delta.y == 0 ? Double.POSITIVE_INFINITY : 0.999 / Math.abs(delta.y);
        double tz = delta.z == 0 ? Double.POSITIVE_INFINITY : 0.5 / Math.abs(delta.z);
        List<BlockPos> cells = new ArrayList<>();
        while (x != last.getX() || y != last.getY() || z != last.getZ()) {
            double next = Math.min(tx, Math.min(ty, tz));
            if (tx <= next) {
                x += sx;
                tx += 1 / Math.abs(delta.x);
            }
            if (ty <= next) {
                y += sy;
                ty += 1 / Math.abs(delta.y);
            }
            if (tz <= next) {
                z += sz;
                tz += 1 / Math.abs(delta.z);
            }
            cells.add(new BlockPos(x, y, z));
        }
        return cells;
    }

    static List<BlockPos> samples(GroundGraph.Edge edge) {
        Set<BlockPos> points = new LinkedHashSet<>();
        List<BlockPos> cells = edge.cells();
        for (int index = 0; index < cells.size(); index++) {
            if (index % SAMPLE_SPACING == 0 || index == cells.size() - 1 || index > 0 && index + 1 < cells.size()
                    && !cells.get(index).subtract(cells.get(index - 1))
                        .equals(cells.get(index + 1).subtract(cells.get(index)))) {
                points.add(cells.get(index));
            }
        }
        return List.copyOf(points);
    }

    static Facility inspect(Footprint area, List<Tower> towers, Predicate<BlockPos> blocked) {
        return inspect(area, samples(area), towers, blocked);
    }

    static Facility inspect(Footprint area, List<BlockPos> points, List<Tower> towers,
                            Predicate<BlockPos> blocked) {
        List<Sample> samples = new ArrayList<>();
        String reason = null;
        for (BlockPos point : points) {
            Set<BlockPos> seen = new LinkedHashSet<>();
            String failure = "distance";
            for (Tower tower : towers) {
                String geometry = tower.geometry(point);
                if (geometry == null) {
                    failure = "obstructed";
                    if (ray(tower.pos(), point).stream().noneMatch(blocked)) {
                        seen.add(tower.pos());
                    }
                } else if (geometry.equals("sector") && failure.equals("distance")) {
                    failure = geometry;
                }
            }
            samples.add(new Sample(point, seen));
            if (seen.isEmpty() && reason == null) {
                reason = failure;
            }
        }
        return new Facility(area, samples, reason == null ? "" : reason);
    }
}
