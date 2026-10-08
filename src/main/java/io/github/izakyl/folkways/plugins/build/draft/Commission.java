package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.front.api.Settings;
import java.util.Objects;
import net.minecraft.core.Direction;

public record Commission(Hint hint, Settings settings, World world, Direction facing, long seed, Growth growth) {

    public Commission {
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(world, "world");
        if (facing == null || facing.getAxis() == Direction.Axis.Y) {
            facing = Direction.NORTH;
        }
        if (growth == null) {
            growth = Growth.first(hint);
        }
    }

    public Commission(Hint hint, Settings settings, World world, Direction facing, long seed) {
        this(hint, settings, world, facing, seed, null);
    }

    public Commission(Hint hint, Settings settings, long seed) {
        this(hint, settings, World.NONE, Direction.NORTH, seed);
    }
}
