package io.github.izakyl.folkways.core.inventory.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Collection;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public interface ColonyInventory {

    // Returns the last publication without resolving worlds, entities or containers.
    InventorySnapshot snapshot();

    // Queue missing observations without reading inventories on the calling thread.
    default void ensureObserved() {
    }

    default void ensureObserved(Stash store) {
    }

    default void ensureObservedResident(UUID resident) {
    }

    default void ensureObservedProvider(ResourceLocation owner) {
    }

    // Replaces this owner's extra physical endpoints in one dimension, without offering them to labor.
    default void observe(ResourceLocation owner, ServerLevel level, Collection<WorldPos> endpoints) {
    }
}
