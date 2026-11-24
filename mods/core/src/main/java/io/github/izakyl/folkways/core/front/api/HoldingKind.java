package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a module makes of something the colony holds under one of its delegations: a zone, a path, a block, a point
 * or a body drawn or pointed at with the book. Each held thing is a facility, and its card is the delegation's
 * settings followed by what every kind registered for the delegation adds, in registration order. A kind answers
 * only for itself: lines and rows it has none of are empty, and an act it does not know is left to the next kind.
 */
public interface HoldingKind {

    /** The physical body this kind adds to the held facility. */
    default FacilityExtent extent(Held held, ServerLevel level) {
        return FacilityExtent.EMPTY;
    }

    /** Elements its card shows after the delegation's own settings. */
    default List<Card.Element> elements() {
        return List.of();
    }

    /** What the held thing is doing now, for its card and the card hovering over it. */
    default List<Line> lines(Held held, ColonyView view) {
        return List.of();
    }

    /** The list a {@link Card.Element.Rows} element of this kind names. */
    default List<Card.Row> rows(Held held, String source, ColonyView view) {
        return List.of();
    }

    /** Rings drawn around the held thing while it is shown, such as how far a post's guard sees. */
    default List<ZoneRing> rings(Held held, ColonyView view) {
        return List.of();
    }

    /**
     * Carries out a button, a listed row's act or a choice's run. Empty when this kind has no such routine; otherwise
     * why it refused, or nothing when it was done.
     */
    default Optional<List<DefinedFacilities.Problem>> act(Held held, ServerPlayer by, String routine,
            Map<String, Value> arguments) {
        return Optional.empty();
    }

    /**
     * A parameter this kind keeps itself rather than in the delegation's settings, such as a container's item
     * filter that the orders module stores with the container. Empty when the delegation's settings hold it.
     */
    default Optional<Value> parameter(Held held, String parameter) {
        return Optional.empty();
    }

    /** Sets a parameter {@link #parameter} answers for; empty when this kind does not keep it. */
    default Optional<List<DefinedFacilities.Problem>> setParameter(Held held, String parameter, Value value) {
        return Optional.empty();
    }

    /** One held thing as a kind sees it. */
    record Held(Colony colony, UUID id, ResourceLocation delegation, WorldPos site, Optional<ZoneView> zone,
                Optional<PathView> path, Settings settings) {
    }
}
