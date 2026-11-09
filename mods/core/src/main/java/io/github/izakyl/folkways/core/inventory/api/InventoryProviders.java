package io.github.izakyl.folkways.core.inventory.api;

import io.github.izakyl.folkways.core.base.Ledger;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

// Providers offer one complete physical inventory per canonical anchor, never overlapping handler views.
public final class InventoryProviders {

    public interface Provider {

        // A bounded discovery page; next is null on the last page.
        Page discover(InventoryDiscovery colony, ServerLevel level, String cursor, int limit);

        Container open(ServerLevel level, Endpoint endpoint);

        // Metadata only. Change this when the endpoint membership changes.
        default long revision(InventoryDiscovery colony, ServerLevel level) {
            return 0;
        }
    }

    public record Endpoint(ResourceLocation inventory, WorldPos anchor) {
    }

    public record Page(List<Endpoint> endpoints, String next, boolean complete) {

        public Page(List<Endpoint> endpoints, String next) {
            this(endpoints, next, true);
        }

        public Page {
            endpoints = List.copyOf(endpoints);
        }
    }

    private static final Map<ResourceLocation, Provider> PROVIDERS = new LinkedHashMap<>();

    private InventoryProviders() {
    }

    public static synchronized void register(ResourceLocation owner, Provider provider) {
        Ledger.writing(owner);
        if (PROVIDERS.putIfAbsent(Objects.requireNonNull(owner), Objects.requireNonNull(provider)) != null) {
            throw new IllegalStateException(owner + " already supplies inventory observations");
        }
    }

    public static synchronized Map<ResourceLocation, Provider> all() {
        return Map.copyOf(PROVIDERS);
    }
}
