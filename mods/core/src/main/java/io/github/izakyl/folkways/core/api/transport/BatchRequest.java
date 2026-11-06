package io.github.izakyl.folkways.core.api.transport;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;

public record BatchRequest(ItemSpec goods, long count, long readyAt) {

    public BatchRequest {
        if (goods.item().isEmpty() || count <= 0) {
            throw new IllegalArgumentException("a batch needs concrete goods and a positive count");
        }
    }
}
