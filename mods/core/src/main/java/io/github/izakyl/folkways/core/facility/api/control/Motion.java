package io.github.izakyl.folkways.core.facility.api.control;

import java.util.Map;
import java.util.Optional;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/** Live, read-only feedback from a native actuator. */
public non-sealed interface Motion extends Control {

    double position();

    double maximum();

    Optional<Vec3> contraption();

    @Override
    default Map<String, Reading> readings() {
        return Map.of("position", level -> valid(level) ? Optional.of(Value.of(position())) : Optional.empty(),
            "maximum", level -> valid(level) ? Optional.of(Value.of(maximum())) : Optional.empty(),
            "moving", level -> valid(level) ? Optional.of(Value.of(moving())) : Optional.empty(),
            "contraption", level -> valid(level)
                ? contraption().map(point -> Value.of(point.x, point.y, point.z)) : Optional.empty());
    }

    boolean moving();

    Optional<AABB> sweep();
}
