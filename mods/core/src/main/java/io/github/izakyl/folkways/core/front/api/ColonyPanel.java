package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/**
 * What a module shows on the colony book's own pages, beside the facilities it holds: problems on the overview, and
 * a section of rows on the residents or stock page (the settler queue, standing orders, parcels on their way). Each
 * page section is shown as a card of one {@link Card.Element.Rows} list, so its rows' acts come back to {@link #act}.
 * A module's colony-wide settings are its enrollment schema, shown on the settings page as the card of its scope;
 * what a schema cannot hold, such as a reserve set per kind of seed, comes as {@link Page#SETTINGS} rows listed on
 * that card below the settings, when the panel is registered under the scope's own id.
 */
public interface ColonyPanel {

    enum Page {
        /** What needs the player's attention: refusals, stalls, missing supplies. */
        OVERVIEW,
        RESIDENTS,
        STOCK,
        /** Rows on the card of the module's own settings scope, under the settings its schema declares. */
        SETTINGS
    }

    /** The module's rows on a page, empty when it has nothing to show there now. */
    default List<Card.Row> rows(Page page, ColonyView view) {
        return List.of();
    }

    /** Carries out a row's act; empty when this panel has no such routine, else why it refused or nothing. */
    default Optional<List<DefinedFacilities.Problem>> act(ServerPlayer by, String routine,
            Map<String, Value> arguments) {
        return Optional.empty();
    }
}
