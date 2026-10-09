package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class OrdersContent {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "orders");

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

    static final Schema.Setting RANK = new Schema.Setting.Count(
        "rank", "folkways.settings.orders.rank",
        ResourceLocation.withDefaultNamespace("bell"),
        Order.KEENEST, Order.LEAST, Order.RANK_NORMAL);

    static final Schema.Setting FIRST_SLOT = new Schema.Setting.Count(
        "first_slot", "folkways.settings.orders.first_slot",
        ResourceLocation.withDefaultNamespace("hopper"),
        1, Shelf.MAX_SLOT, 1);

    static final Schema.Setting LAST_SLOT = new Schema.Setting.Count(
        "last_slot", "folkways.settings.orders.last_slot",
        ResourceLocation.withDefaultNamespace("hopper"),
        1, Shelf.MAX_SLOT, 27);

    static final PageKind PAGE = PageKind.boarded(ID, "folkways.panel.orders",
        ResourceLocation.withDefaultNamespace("writable_book"));

    private static final Schema DRAFT = new Schema(List.of(ITEM, LOW, COUNT, RANK, FIRST_SLOT, LAST_SLOT));

    public static void declare(Declaring declaring) {
        declaring.colony(ID, scope -> {
            OrdersPresence state = new OrdersPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, OrdersPresence.class)
            .map(value -> (io.github.izakyl.folkways.front.api.Facing) value));
        registering.enrollment(ID, new Enrollment(List.of(), DRAFT, List.of(PAGE)));
    }

}
