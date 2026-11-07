package io.github.izakyl.folkways.core.resident.api;

import java.util.Optional;
import net.minecraft.core.BlockPos;

public record Search(Optional<BlockPos> from) {

    public static Search from(BlockPos cell) {
        return new Search(Optional.of(cell));
    }
}
