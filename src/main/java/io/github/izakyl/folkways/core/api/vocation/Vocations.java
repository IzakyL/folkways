package io.github.izakyl.folkways.core.api.vocation;

import io.github.izakyl.folkways.core.api.Ledger;
import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.perk.PerkPool;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class Vocations {

    public static final ResourceLocation HAULING =
        ResourceLocation.fromNamespaceAndPath("folkways", "hauling");

    private static final Ledger<Vocation> TRADES = new Ledger<>("a trade", Vocation::id);

    private Vocations() {
    }

    public static Vocation register(VocationSpec spec) {
        Vocation vocation = TRADES.claim(new Vocation(spec.id(), spec.perks(), spec.kit()));
        for (Perk perk : spec.perks()) {
            PerkPool.register(spec.id(), perk);
        }
        return vocation;
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
