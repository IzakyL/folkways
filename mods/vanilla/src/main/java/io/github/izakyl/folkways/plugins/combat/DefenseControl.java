package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Optional manual-control observations used by defence, supplies and ordinary resident needs. */
public interface DefenseControl {
    ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "defense_control");
    DefenseControl NONE = new DefenseControl() {
    };

    /** The same live order object must be returned until replacement, so supply authority cannot outlive it. */
    interface Order {
        boolean guarding();

        boolean defensiveOnly();

        WorldPos destination();

        void engaging();
    }

    static DefenseControl of(Colony colony) {
        return colony.service(ID, DefenseControl.class).orElse(NONE);
    }

    default Optional<? extends Order> current(UUID resident) {
        return Optional.empty();
    }

    default boolean reserved(UUID resident) {
        return false;
    }

    default boolean failedWaiting(UUID resident) {
        return false;
    }
}
