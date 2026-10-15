package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.Map;
import java.util.UUID;

// Existing delivery promises, for helpers that must not manufacture a second copy of a shared tool.
public final class Orders {
    private Orders() {
    }

    public static Map<UUID, Long> commitments(Colony colony, ItemSpec goods) {
        return colony.service(OrdersContent.ID, OrdersPresence.class)
            .map(orders -> orders.commitments(goods)).orElse(Map.of());
    }
}
