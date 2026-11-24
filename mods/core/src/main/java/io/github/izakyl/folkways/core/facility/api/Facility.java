package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/**
 * Anything the colony runs routines for: named routines, the triggers that run them, its parameters and what it reads.
 * A facility need not have a body; a farm zone or a building site can be one. A {@link FacilityDefinition} is a facility with a
 * body: controls in an extent of the world, written under leases.
 */
public interface Facility {

    UUID id();

    /** Where the facility's runs are told to be. */
    WorldPos site();

    /** The body's block and entity ownership on the server thread. */
    default FacilityExtent extent(ServerLevel level) {
        return FacilityExtent.EMPTY;
    }

    /** Its routines by its own names for them. */
    Map<String, Routine.Use> routines();

    List<Trigger> triggers();

    Map<String, Value> parameters();

    /** What it reads, by name; taken on the server thread when a run is asked for. */
    Map<String, Reading> readings(ServerLevel level);

    /** The contributions of this facility at the given reading snapshot. Equal contributions stay installed. */
    default List<Contribution> projections(ServerLevel level, Map<String, Value> readings) {
        return List.of();
    }

    /** Geometry identifiable directly from native blocks, even without a loaded colony registration. */
    default List<Contribution> geometry(ServerLevel level) {
        return List.of();
    }

    /** What the player sees of it: elements bound by name to its routines, triggers, parameters and readings. */
    default Card card() {
        return Card.EMPTY;
    }

    /** Its status, as its card and the hover card over it show it, from the readings just taken. */
    default List<Line> lines(ServerLevel level, Map<String, Value> readings) {
        return List.of();
    }

    /** The list its card shows under {@code source}. */
    default List<Card.Row> rows(ServerLevel level, String source, Map<String, Value> readings) {
        return List.of();
    }

    /** Its controls as they stand now; a facility without a body has none. */
    default Optional<Rig> rig(ServerLevel level) {
        return Optional.empty();
    }
}
