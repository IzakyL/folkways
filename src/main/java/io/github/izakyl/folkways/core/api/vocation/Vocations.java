package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.core.api.Ledger;
import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.perk.PerkPool;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class Vocations {

    public static final ResourceLocation HAULING =
        ResourceLocation.fromNamespaceAndPath("folkways", "hauling");

    private static final Ledger<Vocation> TRADES = new Ledger<>("a trade", Vocation::id);

    private static volatile Map<ResourceLocation, List<ItemSpec>> addedKit = Map.of();

    private Vocations() {
    }

    public static Vocation register(VocationSpec spec) {
        Vocation vocation = TRADES.claim(new Vocation(spec.id(), spec.perks(), spec.kit()));
        for (Perk perk : spec.perks()) {
            PerkPool.register(spec.id(), perk);
        }
        return vocation;
    }

    // A plugin may add tools before or after another plugin declares the trade. Readers only see immutable snapshots.
    public static synchronized void addKit(ResourceLocation vocation, ItemSpec tool) {
        Ledger.writing(vocation);
        Objects.requireNonNull(vocation);
        Objects.requireNonNull(tool);
        Map<ResourceLocation, List<ItemSpec>> next = new LinkedHashMap<>(addedKit);
        LinkedHashSet<ItemSpec> tools = new LinkedHashSet<>(next.getOrDefault(vocation, List.of()));
        tools.add(tool);
        next.put(vocation, List.copyOf(tools));
        addedKit = Map.copyOf(next);
    }

    static List<ItemSpec> kit(ResourceLocation vocation, List<ItemSpec> declared) {
        List<ItemSpec> added = addedKit.getOrDefault(vocation, List.of());
        if (added.isEmpty()) {
            return declared;
        }
        LinkedHashSet<ItemSpec> tools = new LinkedHashSet<>(declared);
        tools.addAll(added);
        return List.copyOf(tools);
    }

    public static List<ResourceLocation> ids() {
        return TRADES.ids();
    }

    public static List<Vocation> all() {
        return TRADES.all();
    }

    public static Optional<Vocation> of(ResourceLocation id) {
        return TRADES.of(id);
    }

    public static Vocation required(ResourceLocation id) {
        return TRADES.required(id);
    }
}
