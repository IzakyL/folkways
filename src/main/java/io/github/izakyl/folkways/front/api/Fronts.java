package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

public final class Fronts {

    public static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways", "front");

    private static Function<Colony, FrontView> source = colony -> {
        throw new IllegalStateException("the colony front has not been installed");
    };

    private Fronts() {
    }

    public static void install(Function<Colony, FrontView> installed) {
        source = installed;
    }

    public static FrontView of(Colony colony) {
        return source.apply(colony);
    }
}
