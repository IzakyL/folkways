package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

public record KitCapabilities(Map<ResourceLocation, Double> abilities,
                              Set<StoreWorkstations.Capability> workstations) {
    public static final ResourceLocation PICKUP_RADIUS =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "pickup_radius");
    public static final ResourceLocation AUTO_FEED =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "auto_feed");
    public static final KitCapabilities NONE = new KitCapabilities(Map.of(), Set.of());

    public KitCapabilities {
        if (abilities.values().stream().anyMatch(value -> !Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException("invalid kit capabilities");
        }
        abilities = Map.copyOf(abilities);
        workstations = Set.copyOf(workstations);
    }

    public double ability(ResourceLocation id) {
        return abilities.getOrDefault(id, 0.0);
    }
}
