package io.github.izakyl.folkways.airline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

// Runs only on an immutable surface copied by AirportScan.
final class AirportSurvey {
    static final List<Direction> HORIZONTAL = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private final AirportSurface surface;
    private final List<BlockPos> cells;
    private final BlockPos tower;
    private final boolean jurisdiction;
    private final List<Runway> runways = new ArrayList<>();
    private final List<Helipad> pads = new ArrayList<>();
    private final List<Airport.Stand> stands = new ArrayList<>();
    private final Set<Airport.Problem> flaws = new LinkedHashSet<>();

    AirportSurvey(AirportSurface surface, Set<BlockPos> concrete, BlockPos tower) {
        this(surface, concrete, tower, true);
    }

    AirportSurvey(AirportSurface surface, Set<BlockPos> concrete, BlockPos tower, boolean jurisdiction) {
        this.surface = surface;
        this.tower = tower;
        this.jurisdiction = jurisdiction;
        cells = concrete.stream().filter(pos -> !jurisdiction || AirportScan.inside(tower, pos))
            .sorted(Comparator.comparingLong(BlockPos::asLong)).toList();
    }

    Airport scan() {
        Runways.diagonal(surface, cells).stream().filter(runway -> contained(Airport.footprint(runway)))
            .forEach(runways::add);
        for (BlockPos pos : cells) {
            if (surface.material(pos) == AirportSurface.WHITE && !landingAt(pos)
                    && (surface.material(pos.east()) == AirportSurface.WHITE
                        && surface.material(pos.west()) == AirportSurface.WHITE
                        || surface.material(pos.north()) == AirportSurface.WHITE
                        && surface.material(pos.south()) == AirportSurface.WHITE)) {
                Helipads.atCenter(surface, pos).filter(pad -> contained(Airport.footprint(pad))).ifPresent(pads::add);
            }
        }
        List<Stop> stops = new ArrayList<>();
        for (BlockPos pos : cells) {
            if (surface.material(pos) != AirportSurface.WHITE || landingAt(pos)) {
                continue;
            }
            for (Direction facing : HORIZONTAL) {
                Direction across = facing.getClockWise();
                if (surface.material(pos.relative(across)) == AirportSurface.WHITE
                        && surface.material(pos.relative(across, -1)) == AirportSurface.WHITE
                        && surface.material(pos.relative(across, 2)) != AirportSurface.WHITE
                        && surface.material(pos.relative(across, -2)) != AirportSurface.WHITE
                        && surface.material(pos.relative(facing, -1)) == AirportSurface.YELLOW
                        && surface.material(pos.relative(facing, -2)) == AirportSurface.YELLOW
                        && surface.material(pos.relative(facing)) != AirportSurface.YELLOW) {
                    stops.add(new Stop(pos, facing));
                }
            }
        }
        for (Stop stop : stops) {
            stands.add(stand(stop, stops));
        }
        GroundGraph graph = GroundAreas.build(surface, cells, runways, pads, stands);
        Set<BlockPos> landing = new HashSet<>();
        runways.forEach(runway -> landing.add(runway.firstThreshold()));
        pads.forEach(pad -> landing.add(pad.center()));
        Set<BlockPos> reachable = graph.reachableFrom(landing);
        for (Airport.Stand stand : stands) {
            if (!reachable.contains(stand.pos())) {
                flaws.add(new Airport.Problem(AirportFlaw.STAND_UNREACHABLE, stand.pos(), ""));
            }
        }
        if (landing.isEmpty()) {
            flaws.add(new Airport.Problem(AirportFlaw.NO_LANDING_AREA, tower, ""));
        }
        for (Runway runway : runways) {
            if (landing.size() + stands.size() > 1 && graph.nodes().stream().filter(node ->
                    runway.firstThreshold().equals(node.facility())).noneMatch(node -> graph.edges().stream()
                    .anyMatch(edge -> edge.from().equals(node.pos()) || edge.to().equals(node.pos())))) {
                flaws.add(new Airport.Problem(AirportFlaw.RUNWAY_UNREACHABLE, runway.firstThreshold(), ""));
            }
        }
        Airport airport = new Airport("", tower, runways, pads, stands, graph, List.copyOf(flaws));
        List<Footprint> areas = airport.footprints();
        for (int a = 0; a < areas.size(); a++) {
            for (int b = a + 1; b < areas.size(); b++) {
                if (areas.get(a).overlaps(areas.get(b))) {
                    flaws.add(new Airport.Problem(AirportFlaw.OVERLAP, tower, ""));
                }
            }
        }
        return airport.named("", List.copyOf(flaws));
    }

    private record Stop(BlockPos pos, Direction facing) {
    }

    private Airport.Stand stand(Stop stop, List<Stop> stops) {
        int left = extent(stop, stop.pos(), stop.facing().getCounterClockWise(), stops);
        int right = extent(stop, stop.pos(), stop.facing().getClockWise(), stops);
        Airport.Stand best = new Airport.Stand(stop.pos(), stop.facing(), 1, 2 * Math.min(left, right) + 1,
            Math.min(left, right));
        int bestArea = best.width();
        for (int depth = 1; depth < Airport.RADIUS * 2; depth++) {
            BlockPos row = stop.pos().relative(stop.facing(), -depth);
            if (!surface.pavement(row) || landingAt(row) || stops.stream().anyMatch(other -> other != stop
                    && row.distSqr(other.pos()) <= row.distSqr(stop.pos()))) {
                break;
            }
            left = Math.min(left, extent(stop, row, stop.facing().getCounterClockWise(), stops));
            right = Math.min(right, extent(stop, row, stop.facing().getClockWise(), stops));
            int half = Math.min(left, right);
            int area = (depth + 1) * (2 * half + 1);
            if (area >= bestArea) {
                bestArea = area;
                best = new Airport.Stand(stop.pos(), stop.facing(), depth + 1, 2 * half + 1, half);
            }
        }
        return best;
    }

    private int extent(Stop stop, BlockPos row, Direction across, List<Stop> stops) {
        int reach = 0;
        while (reach < Airport.RADIUS * 2) {
            BlockPos next = row.relative(across, reach + 1);
            if (!surface.pavement(next) || landingAt(next) || stops.stream().anyMatch(other -> other != stop
                    && next.distSqr(other.pos()) <= next.distSqr(stop.pos()))) {
                break;
            }
            reach++;
        }
        return reach;
    }

    private boolean contained(Footprint area) {
        return !jurisdiction || area.samples().stream().allMatch(pos -> AirportScan.inside(tower, pos));
    }

    private boolean landingAt(BlockPos pos) {
        return runways.stream().anyMatch(runway -> Airport.footprint(runway).contains(pos))
            || pads.stream().anyMatch(pad -> Airport.footprint(pad).contains(pos));
    }
}
