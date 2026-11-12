package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** A colony's optional combat supply progress; absent when no module stocks combatants. */
public interface Supplies {
    ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "combat_supplies");
    Supplies NONE = new Supplies() {
    };

    static Supplies of(Colony colony) {
        return colony.service(ID, Supplies.class).orElse(NONE);
    }

    /** The resident is away fetching its own supplies; a guard order waits for it. */
    default boolean underway(UUID resident) {
        return false;
    }
}
