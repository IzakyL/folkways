package io.github.izakyl.folkways.core.world.api;

import java.util.Objects;

public record Stand(WorldPos cell) {

    public Stand {
        Objects.requireNonNull(cell, "cell");
    }

    public static Stand at(WorldPos cell) {
        return new Stand(cell);
    }

    @Override
    public String toString() {
        return cell.toString();
    }
}
