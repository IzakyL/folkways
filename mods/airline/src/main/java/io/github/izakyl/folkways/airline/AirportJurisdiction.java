package io.github.izakyl.folkways.airline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

final class AirportJurisdiction {
    private AirportJurisdiction() {
    }

    static Airport select(Airport raw, List<TowerCoverage.Tower> towers, Predicate<BlockPos> blocked,
                          BlockPos requested) {
        List<TowerCoverage.Facility> coverage = new ArrayList<>(raw.footprints().stream()
            .map(area -> TowerCoverage.inspect(area, towers, blocked)).toList());
        List<GroundGraph.Edge> edges = new ArrayList<>();
        for (GroundGraph.Edge edge : raw.ground().edges()) {
            TowerCoverage.Facility sight = TowerCoverage.inspect(Airport.Rectangle.between(edge.from(), edge.to()),
                TowerCoverage.samples(edge), towers, blocked);
            coverage.add(sight);
            if (sight.covered()) {
                edges.add(edge);
            }
        }
        GroundGraph ground = new GroundGraph(raw.ground().nodes(), edges);
        Set<BlockPos> members = new LinkedHashSet<>();
        members.add(requested);
        Set<Footprint> areas = new LinkedHashSet<>();
        Set<BlockPos> facilities = new LinkedHashSet<>();
        boolean changed;
        do {
            changed = false;
            Set<BlockPos> reachable = connected(ground, facilities);
            ground.nodes().stream().filter(node -> node.facility() != null && reachable.contains(node.pos()))
                .map(GroundGraph.Node::facility).toList().forEach(facilities::add);

            for (TowerCoverage.Facility facility : coverage) {
                if (!facility.covered() || !raw.footprints().contains(facility.area())) {
                    continue;
                }
                Set<BlockPos> observers = new LinkedHashSet<>();
                facility.samples().forEach(sample -> observers.addAll(sample.towers()));
                BlockPos anchor = anchor(raw, facility.area());
                if (observers.stream().anyMatch(members::contains) || facilities.contains(anchor)) {
                    changed |= members.addAll(observers);
                    changed |= areas.add(facility.area());
                    changed |= facilities.add(anchor);
                }
            }
        } while (changed);
        List<Runway> runways = raw.runways().stream().filter(runway -> areas.contains(Airport.footprint(runway)))
            .toList();
        List<Helipad> pads = raw.helipads().stream().filter(pad -> areas.contains(Airport.footprint(pad))).toList();
        List<Airport.Stand> stands = raw.stands().stream().filter(stand -> areas.contains(stand.footprint())).toList();
        Set<BlockPos> used = connected(ground, facilities);
        List<GroundGraph.Node> nodes = ground.nodes().stream().filter(node -> used.contains(node.pos())).toList();
        List<GroundGraph.Edge> selectedEdges = ground.edges().stream()
            .filter(edge -> used.contains(edge.from()) && used.contains(edge.to())).toList();
        GroundGraph selectedGround = new GroundGraph(nodes, selectedEdges,
            raw.ground().turns().stream().filter(turn -> selectedEdges.stream()
                .anyMatch(edge -> edge.cells().contains(turn.pos()))).toList(), raw.ground().graded(), raw.ground().pavement());
        Set<BlockPos> selectedCells = new LinkedHashSet<>(used);
        selectedEdges.forEach(edge -> selectedCells.addAll(edge.cells()));
        List<Airport.Problem> flaws = new ArrayList<>(raw.flaws().stream()
            .filter(problem -> problem.pos().equals(requested) || selectedCells.contains(problem.pos())
                || areas.stream().anyMatch(area -> area.contains(problem.pos()))).toList());
        if (runways.isEmpty() && pads.isEmpty()
                && flaws.stream().noneMatch(problem -> problem.kind() == AirportFlaw.NO_LANDING_AREA)) {
            flaws.add(new Airport.Problem(AirportFlaw.NO_LANDING_AREA, requested, ""));
        }
        List<TowerCoverage.Tower> selected = towers.stream().filter(tower -> members.contains(tower.pos())).toList();
        BlockPos representative = selected.stream().map(TowerCoverage.Tower::pos).min(BlockPos::compareTo)
            .orElse(requested);
        return new Airport("", representative, runways, pads, stands, selectedGround, flaws, selected, coverage);
    }

    private static Set<BlockPos> connected(GroundGraph ground, Set<BlockPos> facilities) {
        Set<BlockPos> reached = new LinkedHashSet<>();
        ground.nodes().stream().filter(node -> facilities.contains(node.pos())
            || node.facility() != null && facilities.contains(node.facility())).map(GroundGraph.Node::pos)
            .forEach(reached::add);
        boolean changed;
        do {
            changed = false;
            for (GroundGraph.Edge edge : ground.edges()) {
                if (reached.contains(edge.from()) || reached.contains(edge.to())) {
                    changed |= reached.add(edge.from());
                    changed |= reached.add(edge.to());
                }
            }
        } while (changed);
        return reached;
    }

    private static BlockPos anchor(Airport raw, Footprint area) {
        return raw.runways().stream().filter(runway -> Airport.footprint(runway).equals(area))
            .map(Runway::firstThreshold).findFirst().orElseGet(() -> raw.helipads().stream()
                .filter(pad -> Airport.footprint(pad).equals(area)).map(Helipad::center).findFirst()
                .orElseGet(() -> raw.stands().stream().filter(stand -> stand.footprint().equals(area))
                    .map(Airport.Stand::pos).findFirst().orElseThrow()));
    }
}
