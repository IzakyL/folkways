package io.github.izakyl.folkways.plugins.build;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record Footprint(BlockPos anchor, Map<BlockPos, BlockState> cells) {

    public Footprint {
        anchor = anchor.immutable();
        if (!cells.containsKey(anchor)) {
            throw new IllegalArgumentException("a footprint holds its anchor");
        }
        Map<BlockPos, BlockState> ordered = new LinkedHashMap<>();
        ordered.put(anchor, cells.get(anchor));
        cells.forEach((cell, state) -> ordered.putIfAbsent(cell.immutable(), state));
        cells = Collections.unmodifiableMap(ordered);
    }

    public static Footprint single(BlockPos cell, BlockState state) {
        return new Footprint(cell, Map.of(cell, state));
    }

    public static Footprint pair(BlockPos anchor, BlockState anchorState, BlockPos other, BlockState otherState) {
        return new Footprint(anchor, Map.of(anchor, anchorState, other, otherState));
    }
}
