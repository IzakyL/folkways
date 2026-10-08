package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

public sealed interface Skin {

    record Fitted(Materials materials, Fit fit, Direction.Axis axis, String support) implements Skin {

        public Fitted {
            Objects.requireNonNull(materials, "materials");
            Objects.requireNonNull(fit, "fit");
            support = Objects.requireNonNull(support, "support");
            if ((fit == Fit.CONNECTED) != !support.isEmpty()) {
                throw new IllegalArgumentException("connected fitting requires a support role; other fits do not");
            }
        }

        public Fitted(Materials materials, Fit fit, Direction.Axis axis) {
            this(materials, fit, axis, "");
        }

        public Fitted(List<ItemFilter> palette, Fit fit) {
            this(Materials.inOrder(palette), fit, null);
        }
    }

    record Cleared() implements Skin {}

    record Stamped(Map<BlockPos, BlockState> cells, List<Swap> swaps) implements Skin {

        public Stamped {
            cells = Map.copyOf(cells);
            swaps = List.copyOf(swaps);
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("a stamp of no cells builds nothing");
            }
        }
    }

    static Skin cleared() {
        return new Cleared();
    }
}
