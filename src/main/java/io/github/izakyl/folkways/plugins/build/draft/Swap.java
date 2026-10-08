package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import java.util.Objects;

public record Swap(ItemFilter from, Materials into) {

    public Swap {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(into, "into");
    }
}
