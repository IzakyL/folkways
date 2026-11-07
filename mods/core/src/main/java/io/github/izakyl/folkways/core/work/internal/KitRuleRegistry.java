package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.base.Ledger;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.KitRules.SupplyPolicy;
import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.ToolPolicy;
import io.github.izakyl.folkways.core.work.support.Tools;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

// Settings are resolved on the server thread; planners only carry these immutable item sets.
public final class KitRuleRegistry implements KitRules.Access {

    private volatile Function<Colony, ToolPolicy> rule = colony -> defaults();

    private volatile Map<ResourceLocation, SupplyPolicy> supplies = Map.of();

    private KitRuleRegistry() {
    }

    public void install(Function<Colony, ToolPolicy> installed) {
        rule = Objects.requireNonNull(installed);
    }

    public ToolPolicy defaults() {
        Map<ResourceLocation, Set<Item>> allowed = new LinkedHashMap<>();
        for (Vocation vocation : Vocations.all()) {
            Set<Item> tools = new LinkedHashSet<>();
            vocation.kit().forEach(spec -> tools.addAll(Goods.members(spec)));
            Tools.sharedGoods(vocation.id())
                .forEach(spec -> tools.addAll(Goods.members(spec)));
            allowed.put(vocation.id(), tools);
        }
        return new ToolPolicy(allowed);
    }

    public ToolPolicy snapshot(Colony colony) {
        return rule.apply(colony);
    }

    /** Whether ordinary equipment delivery may run alongside this body's current controller. */
    public synchronized void supplyPolicy(ResourceLocation owner, SupplyPolicy policy) {
        Ledger.writing(owner);
        Map<ResourceLocation, SupplyPolicy> next = new LinkedHashMap<>(supplies);
        if (next.putIfAbsent(Objects.requireNonNull(owner), Objects.requireNonNull(policy)) != null) {
            throw new IllegalStateException(owner + " already supplies an equipment delivery"
                + " policy");
        }
        supplies = Collections.unmodifiableMap(next);
    }

    public boolean supplies(Colony colony, Body body) {
        return supplies.values().stream().allMatch(policy -> policy.allows(colony, body));
    }

    public static void install() {
        KitRules.install(new KitRuleRegistry());
    }
}
