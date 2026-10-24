package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;

public record Need(ItemSpec spec, long count, boolean carried, boolean substitutable) {

    public Need(ItemSpec spec, long count) {
        this(spec, count, false, false);
    }

    public Need(ItemSpec spec, long count, boolean carried) {
        this(spec, count, carried, false);
    }

    public Need replacing() { return new Need(spec, count, carried, true); }
}
