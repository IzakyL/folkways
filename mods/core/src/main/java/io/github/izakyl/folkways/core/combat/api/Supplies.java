package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** A colony's optional combat supply readout for the command page; absent when no module stocks combatants. */
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

    /** The stock part of the resident's row, if this colony tracks supplies for its kind. */
    default Optional<Component> stock(Body body) {
        return Optional.empty();
    }

    /** The group's loadout line; empty hides the page's supply section. */
    default Optional<Component> loadout(Collection<UUID> members) {
        return Optional.empty();
    }

    /** Notes shown under the loadout line. */
    default List<Component> help() {
        return List.of();
    }
}
