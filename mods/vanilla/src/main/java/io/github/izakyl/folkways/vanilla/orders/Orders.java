package io.github.izakyl.folkways.vanilla.orders;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.card.api.notice.Attempt;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

// Player stock targets and existing deliveries, without exposing the mutable order book.
public final class Orders {
    private Orders() {
    }

    public record Target(WorldPos into, ItemSpec goods, long high, boolean standing) {
    }

    /**
     * One order as a request reads it: the count it fills its container to, what the container holds and what is
     * still on its way, whether work on it has begun, and why it stands still when it does.
     */
    public record Request(WorldPos into, ItemSpec goods, long high, boolean standing, long held, long coming,
                          boolean begun, Optional<Notice> stalled) {
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

    /** Files a once order for count more of the goods than the container holds or already has on order. */
    public static Attempt request(Colony colony, ServerLevel level, WorldPos into, ItemSpec goods, long count) {
        return colony.service(OrdersContent.ID, OrdersPresence.class)
            .map(orders -> orders.request(level, into, goods, count))
            .orElseGet(() -> Attempt.refused(OrderRefusal.NOT_A_CONTAINER));
    }

    /** Withdraws the order for these goods into the container, as its card's delete does. */
    public static Attempt cancel(Colony colony, WorldPos into, ItemSpec goods) {
        return colony.service(OrdersContent.ID, OrdersPresence.class)
            .map(orders -> orders.cancel(into, goods))
            .orElseGet(() -> Attempt.refused(OrderRefusal.NOT_A_CONTAINER));
    }

    public static List<Request> requests(Colony colony, ServerLevel level, Collection<WorldPos> into) {
        return colony.service(OrdersContent.ID, OrdersPresence.class)
            .map(orders -> orders.requests(level, into)).orElse(List.of());
    }
}
