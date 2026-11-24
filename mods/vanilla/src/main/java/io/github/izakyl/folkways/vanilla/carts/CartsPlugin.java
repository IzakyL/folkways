package io.github.izakyl.folkways.vanilla.carts;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Category;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.shell.carts.api.CartHitches;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;

public final class CartsPlugin {
    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "carts");

    // A cart line, drawn as a path whose every point is a station.
    public static final ResourceLocation LINE = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "cart_line");

    static final ResourceLocation OPEN = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "line_open");
    static final ResourceLocation CLOSED = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "line_closed");

    static final Schema.Setting.Choice CART = new Schema.Setting.Choice("cart", "folkways.settings.cart_line.cart",
        ResourceLocation.fromNamespaceAndPath(CartHitches.ASTIKOR, "oak_hand_cart"),
        List.of(Line.Kind.HAND.entity(), Line.Kind.SUPPLY.entity()), Line.Kind.HAND.entity());

    static final Schema.Setting.Choice ROUND = new Schema.Setting.Choice("round", "folkways.settings.cart_line.round",
        ResourceLocation.withDefaultNamespace("compass"), List.of(OPEN, CLOSED), OPEN);

    private static final int MOST_STATIONS = 16;

    private CartsPlugin() {
    }

    public static void install(IEventBus bus) {
        NeoForge.EVENT_BUS.addListener(CartEvents::horseTick);
        NeoForge.EVENT_BUS.addListener(CartEvents::removed);
        if (FMLEnvironment.dist.isClient()) {
            bus.addListener(CartsScene::declare);
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, CartsCardRenderer::render);
        }
        bus.addListener(Registering.class, event -> event.enrollment(OWNER,
            new Enrollment(List.of(new Delegation(LINE, new Shape.Path(MOST_STATIONS),
                new Schema(List.of(CART, ROUND)), ResourceLocation.fromNamespaceAndPath(CartHitches.ASTIKOR,
                    "oak_hand_cart")).in(Category.LOGISTICS)))));
        bus.addListener(Declaring.class, event -> {
            event.locomotion(Carting.INSTANCE);
            event.colony(OWNER, scope -> {
                CartPresence presence = new CartPresence(scope.colony(), scope.server());
                CartLines lines = new CartLines(scope.colony());
                scope.watch(OWNER, presence);
                scope.share(lines);
                scope.tick(lines::tick);
                scope.closed(why -> {
                    lines.closed(scope.server(), why);
                    presence.closed(why);
                });
            });
        });
    }
}
