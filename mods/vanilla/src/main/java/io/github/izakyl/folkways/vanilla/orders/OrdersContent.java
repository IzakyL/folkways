package io.github.izakyl.folkways.vanilla.orders;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class OrdersContent {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "orders");

    static final Schema.Setting ITEM = Schema.Setting.Items.one(
        "item", "folkways.settings.orders.item",
        ResourceLocation.withDefaultNamespace("chest"));

    static final Schema.Setting LOW = new Schema.Setting.Count(
        "low", "folkways.settings.orders.low",
        ResourceLocation.withDefaultNamespace("hopper"),
        Order.MIN_COUNT, Order.MAX_COUNT, Order.MIN_COUNT);

    static final Schema.Setting COUNT = new Schema.Setting.Count(
        "count", "folkways.settings.orders.count",
        ResourceLocation.withDefaultNamespace("comparator"),
        Order.MIN_COUNT, Order.MAX_COUNT, Order.MIN_COUNT);

    static final Schema.Setting FILTER = new Schema.Setting.Items(
        "filter", "folkways.settings.orders.filter",
        ResourceLocation.withDefaultNamespace("hopper"), List.of());

    private static final Schema DRAFT = new Schema(List.of(ITEM, LOW, COUNT, FILTER));

    public static void declare(Declaring declaring) {
        declaring.colony(ID, scope -> {
            OrdersPresence state = new OrdersPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.holding(WaresContent.STORE, colony -> colony.service(ID, OrdersPresence.class).map(state -> state));
        registering.panel(ID, colony -> colony.service(ID, OrdersPresence.class).map(state -> state));
        registering.enrollment(ID, new Enrollment(List.of(), DRAFT));
    }

}
