package io.github.izakyl.folkways.vanilla.build.draft;

import io.github.izakyl.folkways.core.front.api.Settings;
import java.util.Objects;
import net.minecraft.core.Direction;

public record Commission(Hint hint, Settings settings, World world, Direction facing, long seed) {

    public Commission {
        Objects.requireNonNull(hint, "hint");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(world, "world");
        if (facing == null || facing.getAxis() == Direction.Axis.Y) {
            facing = Direction.NORTH;
        }
    }

    public Commission(Hint hint, Settings settings, long seed) {
        this(hint, settings, World.NONE, Direction.NORTH, seed);
    }
}
