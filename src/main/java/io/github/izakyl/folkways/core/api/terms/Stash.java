package io.github.izakyl.folkways.core.api.terms;

import java.util.Objects;

public record Stash(WorldPos pos) {

    public Stash {
        Objects.requireNonNull(pos, "pos");
    }

    public static Stash at(WorldPos pos) {
        return new Stash(pos);
    }

    @Override
    public String toString() {
        return pos.toString();
    }
}
