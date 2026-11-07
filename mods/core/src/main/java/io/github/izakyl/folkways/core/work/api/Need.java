package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;

public record Need(ItemSpec spec, long count, boolean carried) {

    public Need(ItemSpec spec, long count) {
        this(spec, count, false);
    }
}
