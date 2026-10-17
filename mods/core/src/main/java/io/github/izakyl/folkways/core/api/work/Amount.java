package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;

public record Amount(ItemSpec spec, long count) {
}
