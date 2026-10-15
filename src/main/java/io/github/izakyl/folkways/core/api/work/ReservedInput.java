package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

// An unspent draw in the current plan. This is an obligation, not another copy of goods in stock.
// Consumers reconcile draws against the physical contents at their location; a not-yet-bound pack has
// neither location. Several steps may draw the same goods at successive locations along one route.
public record ReservedInput(UUID node, Optional<WorldPos> store, Optional<UUID> resident, ItemSpec goods, long count) {
    public ReservedInput {
        Objects.requireNonNull(node);
        Objects.requireNonNull(store);
        Objects.requireNonNull(resident);
        Objects.requireNonNull(goods);
        if (count <= 0 || store.isPresent() && resident.isPresent()) {
            throw new IllegalArgumentException("an input needs a positive count and at most one physical location");
        }
    }
}
