package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.structure.StructurePayload;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record Draft(Extent extent, List<Cell> cells, List<Joint> joints) {

    public Draft {
        Objects.requireNonNull(extent, "extent");
        cells = List.copyOf(cells);
        joints = List.copyOf(joints);
    }

    public Draft(Extent extent, List<Cell> cells) {
        this(extent, cells, List.of());
    }

    public record Cell(BlockPos offset, BlockState state, Source source, StructurePayload payload) {

        public Cell {
            offset = offset.immutable();
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(payload, "payload");
        }

        public Cell(BlockPos offset, BlockState state, Source source) {
            this(offset, state, source, StructurePayload.EMPTY);
        }

        public Cell(BlockPos offset, BlockState state) {
            this(offset, state, Source.NONE);
        }
    }

    public record Source(String path, String role) {

        public static final Source NONE = new Source("", "");

        public Source {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(role, "role");
        }
    }
}
