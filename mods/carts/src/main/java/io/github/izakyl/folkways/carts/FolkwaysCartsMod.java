package io.github.izakyl.folkways.carts;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(FolkwaysCartsMod.ID)
public final class FolkwaysCartsMod {
    public static final String ID = "folkways_carts";
    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "carts");

    // A cart line, drawn as a path whose every point is a station.
    public static final ResourceLocation LINE = ResourceLocation.fromNamespaceAndPath("folkways", "cart_line");

    static final ResourceLocation OPEN = ResourceLocation.fromNamespaceAndPath("folkways", "line_open");
    static final ResourceLocation CLOSED = ResourceLocation.fromNamespaceAndPath("folkways", "line_closed");

    static final Schema.Setting.Choice CART = new Schema.Setting.Choice("cart", "folkways.settings.cart_line.cart",
        ResourceLocation.fromNamespaceAndPath(CartLinks.ASTIKOR, "oak_hand_cart"),
        List.of(Line.Kind.HAND.entity(), Line.Kind.SUPPLY.entity()), Line.Kind.HAND.entity());

    static final Schema.Setting.Choice ROUND = new Schema.Setting.Choice("round", "folkways.settings.cart_line.round",
        ResourceLocation.withDefaultNamespace("compass"), List.of(OPEN, CLOSED), OPEN);

    private static final int MOST_STATIONS = 16;

    public FolkwaysCartsMod(IEventBus bus) {
        if (FMLEnvironment.dist.isClient()) {
            bus.addListener(CartsScene::declare);
        }
        bus.addListener(Registering.class, event -> event.enrollment(OWNER,
            new Enrollment(List.of(new Delegation(LINE, new Shape.Path(MOST_STATIONS),
                new Schema(List.of(CART, ROUND)), ResourceLocation.fromNamespaceAndPath(CartLinks.ASTIKOR,
                    "oak_hand_cart"))))));
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
