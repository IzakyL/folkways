package io.github.izakyl.folkways.core.combat.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Weapons residents fight with. A later registration is asked first, so an extension precedes the common kinds. */
public final class Loadouts {
    private static volatile Map<ResourceLocation, LoadoutProvider> providers = Map.of();

    private Loadouts() {
    }

    public static synchronized void register(ResourceLocation id, LoadoutProvider provider) {
        if (providers.containsKey(id)) {
            throw new IllegalArgumentException("loadout already registered: " + id);
        }
        Map<ResourceLocation, LoadoutProvider> next = new LinkedHashMap<>();
        next.put(Objects.requireNonNull(id), Objects.requireNonNull(provider));
        next.putAll(providers);
        providers = Collections.unmodifiableMap(next);
    }

    public static Optional<LoadoutProvider> find(ResourceLocation id) {
        return Optional.ofNullable(providers.get(id));
    }

    public static Map<ResourceLocation, LoadoutProvider> entries() {
        return providers;
    }

    public static Optional<LoadoutProvider> using(ItemStack stack) {
        return providers.values().stream().filter(provider -> provider.accepts(stack)).findFirst();
    }
}
