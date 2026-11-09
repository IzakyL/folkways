package io.github.izakyl.folkways.core.vocation.internal;

import io.github.izakyl.folkways.core.base.Ledger;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.effect.perk.Perk;
import io.github.izakyl.folkways.core.effect.perk.PerkPool;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class VocationRegistry implements Vocations.Access {

    private final Ledger<Vocation> trades = new Ledger<>("a trade", Vocation::id);

    private volatile Map<ResourceLocation, List<ItemSpec>> addedKit = Map.of();

    private VocationRegistry() {
    }

    public Vocation register(VocationSpec spec) {
        Vocation vocation = trades.claim(new Vocation(spec.id(), spec.perks(), spec.kit(),
            spec.order(), spec.defaultPriority(), spec.group()));
        for (Perk perk : spec.perks()) {
            PerkPool.register(spec.id(), perk);
        }
        return vocation;
    }

    // A plugin may add tools before or after another plugin declares the trade. Readers only see immutable snapshots.
    public synchronized void addKit(ResourceLocation vocation, ItemSpec tool) {
        Ledger.writing(vocation);
        Objects.requireNonNull(vocation);
        Objects.requireNonNull(tool);
        Map<ResourceLocation, List<ItemSpec>> next = new LinkedHashMap<>(addedKit);
        LinkedHashSet<ItemSpec> tools = new LinkedHashSet<>(next.getOrDefault(vocation, List.of()));
        tools.add(tool);
        next.put(vocation, List.copyOf(tools));
        addedKit = Map.copyOf(next);
    }

    public List<ItemSpec> kit(ResourceLocation vocation, List<ItemSpec> declared) {
        List<ItemSpec> added = addedKit.getOrDefault(vocation, List.of());
        if (added.isEmpty()) {
            return declared;
        }
        LinkedHashSet<ItemSpec> tools = new LinkedHashSet<>(declared);
        tools.addAll(added);
        return List.copyOf(tools);
    }

    /**
     * Shared column/wire order, independent of mod loading and event listener registration order.
     * Columns run group by group so each group's heading spans one contiguous run; within a group,
     * explicit orders come first, then namespace:path (not ResourceLocation's path-first natural order).
     */
    public List<ResourceLocation> ids() {
        return all().stream().map(Vocation::id).toList();
    }

    public List<Vocation> all() {
        return trades.all().stream()
            .sorted(Comparator.<Vocation>comparingInt(trade -> trade.group().order())
                .thenComparing(trade -> trade.group().id().toString())
                .thenComparingInt(Vocation::order).thenComparing(trade -> trade.id().toString()))
            .toList();
    }

    public Optional<Vocation> of(ResourceLocation id) {
        return trades.of(id);
    }

    public Vocation required(ResourceLocation id) {
        return trades.required(id);
    }

    public static void install() {
        Vocations.install(new VocationRegistry());
    }
}
