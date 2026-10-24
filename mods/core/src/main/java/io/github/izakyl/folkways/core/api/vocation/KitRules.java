package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.base.Ledger;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

// Which of the tools a trade's kit names a colony lets its residents take up. Core only asks;
// whoever keeps the colony's settings answers, and until someone does every tool is allowed.
public final class KitRules {

    @FunctionalInterface
    public interface Rule {
        boolean allows(Colony colony, Item tool);
    }

    @FunctionalInterface
    public interface SupplyPolicy {
        boolean allows(Colony colony, Body body);
    }

    private static volatile Rule rule = (colony, tool) -> true;
    private static volatile Map<ResourceLocation, SupplyPolicy> supplies = Map.of();

    private KitRules() {
    }

    public static void install(Rule installed) {
        rule = Objects.requireNonNull(installed);
    }

    public static boolean allows(Colony colony, Item tool) {
        return rule.allows(colony, tool);
    }

    /** Whether ordinary equipment delivery may run alongside this body's current controller. */
    public static synchronized void supplyPolicy(ResourceLocation owner, SupplyPolicy policy) {
        Ledger.writing(owner);
        Map<ResourceLocation, SupplyPolicy> next = new LinkedHashMap<>(supplies);
        if (next.putIfAbsent(Objects.requireNonNull(owner), Objects.requireNonNull(policy)) != null) {
            throw new IllegalStateException(owner + " already supplies an equipment delivery policy");
        }
        supplies = Collections.unmodifiableMap(next);
    }

    public static boolean supplies(Colony colony, Body body) {
        return supplies.values().stream().allMatch(policy -> policy.allows(colony, body));
    }
}
