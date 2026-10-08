package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;

public record Need(ItemSpec spec, long count, boolean carried) {

    public Need(ItemSpec spec, long count) {
        this(spec, count, false);
    }
}
