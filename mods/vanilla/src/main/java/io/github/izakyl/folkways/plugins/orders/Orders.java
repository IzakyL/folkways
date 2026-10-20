package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Player stock targets and existing deliveries, without exposing the mutable order book.
public final class Orders {
    private Orders() {
    }

    public record Target(WorldPos into, ItemSpec goods, long high, boolean standing) {
    }

    // Order metadata is written whenever a player files/withdraws it or its batch completes. Reading
    // it here also retains targets in unloaded dimensions without inventing a second live order book.
    public static List<Target> targets(Colony colony) {
        return Reader.of(colony.kept(OrdersContent.ID)).children("orders").stream()
            .flatMap(saved -> Order.load(saved).stream())
            .map(order -> new Target(order.into(), order.what(), order.high(), order.standing())).toList();
    }

    public static Map<UUID, Long> commitments(Colony colony, ItemSpec goods) {
        return colony.service(OrdersContent.ID, OrdersPresence.class)
            .map(orders -> orders.commitments(goods)).orElse(Map.of());
    }
}
