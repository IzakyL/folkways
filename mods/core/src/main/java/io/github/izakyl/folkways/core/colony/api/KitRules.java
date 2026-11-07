package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.work.api.ToolPolicy;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

// Settings are resolved on the server thread; planners only carry these immutable item sets.
public final class KitRules {

    @FunctionalInterface
    public interface SupplyPolicy {
        boolean allows(Colony colony, Body body);
    }

    public interface Access {

        void install(Function<Colony, ToolPolicy> installed);

        ToolPolicy defaults();

        ToolPolicy snapshot(Colony colony);

        void supplyPolicy(ResourceLocation owner, SupplyPolicy policy);

        boolean supplies(Colony colony, Body body);
    }

    private static Access access;

    private KitRules() {
    }

    public static void install(Access installed) {
        access = Objects.requireNonNull(installed);
    }

    public static void install(Function<Colony, ToolPolicy> installed) {
        access.install(installed);
    }

    public static ToolPolicy defaults() {
        return access.defaults();
    }

    public static ToolPolicy snapshot(Colony colony) {
        return access.snapshot(colony);
    }

    public static boolean allows(Colony colony, Optional<Vocation> vocation, Item tool) {
        return snapshot(colony).allows(vocation, tool);
    }

    public static boolean allows(Colony colony, Vocation vocation, Item tool) {
        return allows(colony, Optional.of(vocation), tool);
    }

    public static void supplyPolicy(ResourceLocation owner, SupplyPolicy policy) {
        access.supplyPolicy(owner, policy);
    }

    public static boolean supplies(Colony colony, Body body) {
        return access.supplies(colony, body);
    }
}
