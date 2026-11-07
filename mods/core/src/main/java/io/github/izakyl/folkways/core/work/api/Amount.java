package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;

public record Amount(ItemSpec spec, long count) {
}
