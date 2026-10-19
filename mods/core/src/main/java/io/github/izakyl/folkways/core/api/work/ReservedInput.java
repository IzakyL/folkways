package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

// An unspent draw in the current plan. This is an obligation, not another copy of goods in stock.
// Consumers reconcile draws against the physical contents at their location; a not-yet-bound pack has
// neither location. A producer identifies goods promised by an upstream step. Until it has actually
// produced into a known resident pack, that promise is pending, never another physical inventory claim.
public record ReservedInput(UUID node, Optional<WorldPos> store, Optional<UUID> resident, ItemSpec goods, long count,
                            Optional<UUID> producer) {
    public ReservedInput(UUID node, Optional<WorldPos> store, Optional<UUID> resident, ItemSpec goods, long count) {
        this(node, store, resident, goods, count, Optional.empty());
    }

    public ReservedInput {
        Objects.requireNonNull(node);
        Objects.requireNonNull(store);
        Objects.requireNonNull(resident);
        Objects.requireNonNull(goods);
        Objects.requireNonNull(producer);
        if (count <= 0 || store.isPresent() && (resident.isPresent() || producer.isPresent())) {
            throw new IllegalArgumentException("an input needs a positive count and at most one physical location");
        }
    }

    public boolean pending() {
        return producer.isPresent() && resident.isEmpty();
    }
}
