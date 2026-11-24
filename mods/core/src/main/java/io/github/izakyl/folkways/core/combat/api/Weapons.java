package io.github.izakyl.folkways.core.combat.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** The adapters in weapon-selection order. */
public final class Weapons {
    private record Registered(ResourceLocation id, WeaponAdapter adapter) {
    }

    private static volatile List<Registered> registered = List.of();

    private Weapons() {
    }

    /** Register an adapter ahead of earlier registrations. */
    public static synchronized void register(ResourceLocation id, WeaponAdapter adapter) {
        register(id, adapter, false);
    }

    public static synchronized void registerFallback(ResourceLocation id, WeaponAdapter adapter) {
        register(id, adapter, true);
    }

    private static void register(ResourceLocation id, WeaponAdapter adapter, boolean fallback) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(adapter);
        if (registered.stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalArgumentException("weapon adapter already registered: " + id);
        }
        List<Registered> next = new ArrayList<>();
        if (fallback) {
            next.addAll(registered);
            next.add(new Registered(id, adapter));
        } else {
            next.add(new Registered(id, adapter));
            next.addAll(registered);
        }
        registered = List.copyOf(next);
    }

    public static List<WeaponAdapter> adapters() {
        return registered.stream().map(Registered::adapter).toList();
    }
}
