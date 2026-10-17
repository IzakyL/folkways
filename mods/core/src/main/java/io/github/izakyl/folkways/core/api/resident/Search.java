package io.github.izakyl.folkways.core.api.resident;

import java.util.Optional;
import net.minecraft.core.BlockPos;

public record Search(Optional<BlockPos> from) {

    public static Search from(BlockPos cell) {
        return new Search(Optional.of(cell));
    }
}
