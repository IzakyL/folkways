package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Motion;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

public record MovingKeepout(List<String> parts, double margin, Optional<String> source) {
    public MovingKeepout {
        parts = List.copyOf(parts);
        if (!Double.isFinite(margin) || margin < 0 || (parts.isEmpty() && source.isEmpty())) {
            throw new IllegalArgumentException("invalid moving keepout");
        }
    }

    Optional<Contribution> project(ServerLevel level, FacilityDefinition facility, Map<String, Control> controls) {
        var motion = parts.stream().map(controls::get).filter(Motion.class::isInstance)
            .map(Motion.class::cast).toList();
        if (motion.stream().noneMatch(Motion::moving)) {
            return Optional.empty();
        }
        var floor = facility.extent().bounds(facility.anchor().realm())
            .flatMap(box -> WorldSpaces.world(level, facility.anchor().at(box.min())));
        List<AABB> areas = new java.util.ArrayList<>();
        motion.stream().flatMap(control -> control.sweep().stream()).forEach(areas::add);
        var bounds = areas.stream().map(box -> box.inflate(margin))
            .map(box -> floor.map(at -> box.setMinY(Math.min(box.minY, at.y))).orElse(box)).toList();
        return projection(bounds);
    }

    static Optional<Contribution> projection(List<AABB> bounds) {
        return bounds.isEmpty() ? Optional.empty() : Optional.of(new Contribution.Keepout((world, x, y, z) ->
            bounds.stream().anyMatch(box -> box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0))));
    }
}
