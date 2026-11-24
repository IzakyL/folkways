package io.github.izakyl.folkways.core.facility.internal;

import io.github.izakyl.folkways.core.facility.api.Contribution;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

final class FacilityGates {

    private FacilityGates() {
    }

    public static List<Contribution> project(ServerLevel level, FacilityDefinition facility, Map<String, Value> readings) {
        List<Contribution> projected = new ArrayList<>();
        for (int index = 0; index < facility.gates().size(); index++) {
            FacilityDefinition.Gate gate = facility.gates().get(index);
            Set<WorldPos> cells = gate.cells().stream().map(facility.anchor()::at).collect(Collectors.toUnmodifiableSet());
            Set<String> axes = gate.axes().orElseGet(() -> crossingAxes(gate.cells()));
            boolean open = readings.get(Integer.toString(index)) instanceof Value.Bool(boolean on) && on;
            for (WorldPos cell : cells) {
                projected.add(new Contribution.Closure(cell, !open));
                if (!open || cells.contains(cell.at(cell.cell().below()))) {
                    continue;
                }
                for (Direction axis : List.of(Direction.NORTH, Direction.WEST)) {
                    if (!axes.contains(axis.getAxis().getName())) {
                        continue;
                    }
                    WorldPos from = cell.at(cell.cell().relative(axis));
                    WorldPos to = cell.at(cell.cell().relative(axis.getOpposite()));
                    if (cells.contains(from) || cells.contains(to)) {
                        continue;
                    }
                    projected.add(new Contribution.Hop(GatePassage.hop(from, to)));
                    projected.add(new Contribution.Hop(GatePassage.hop(to, from)));
                }
            }
        }
        return List.copyOf(projected);
    }

    private static Set<String> crossingAxes(List<BlockPos> cells) {
        if (cells.isEmpty()) {
            return Set.of();
        }
        BlockPos first = cells.getFirst();
        boolean spansX = cells.stream().anyMatch(cell -> cell.getX() != first.getX());
        boolean spansZ = cells.stream().anyMatch(cell -> cell.getZ() != first.getZ());
        if (spansX != spansZ) {
            return Set.of(spansX ? "z" : "x");
        }
        return Set.of("x", "z");
    }
}
