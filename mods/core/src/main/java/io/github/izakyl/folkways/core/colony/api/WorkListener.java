package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public interface WorkListener {

    void submitted(ResourceLocation owner, Grown work);

    void withdrawn(ResourceLocation owner, UUID node);

    void offered();

    default Optional<UUID> holder(UUID node) {
        return Optional.empty();
    }

    default boolean isApproaching(UUID node) {
        return false;
    }

    default boolean isSuspended(UUID node) {
        return false;
    }

    default void controllerOrder(ServerLevel level, Body body) {
    }

    default void relocating() {
    }
}
