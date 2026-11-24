package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import net.minecraft.server.level.ServerLevel;

/** Observes publication and removal of loaded player facilities on the server thread. */
public interface FacilityObserver {

    default void published(ServerLevel level, Colony colony, FacilityDefinition facility) {
    }

    default void removed(ServerLevel level, Colony colony, FacilityDefinition facility) {
    }

    default void refresh(ServerLevel level, Colony colony, FacilityDefinition facility, long tick) {
    }
}
