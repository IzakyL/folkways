package io.github.izakyl.folkways.plugins.build.draft;

import java.util.Objects;
import net.minecraft.core.BlockPos;

public record Joint(BlockPos from, BlockPos to) {

    public Joint {
        from = Objects.requireNonNull(from, "from").immutable();
        to = Objects.requireNonNull(to, "to").immutable();
        if (from.equals(to)) {
            throw new IllegalArgumentException("a joint runs between two different cells");
        }
    }

    public Joint moved(BlockPos by) {
        return new Joint(from.offset(by), to.offset(by));
    }
}
