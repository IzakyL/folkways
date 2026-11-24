package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/** Finds a colony's facility by id, whichever source holds it, for its card. */
public final class Operations {
    private static final List<BiFunction<Colony, UUID, Optional<? extends Operating>>> SOURCES =
        new CopyOnWriteArrayList<>(List.of(DefinedOperating::find));

    private Operations() {
    }

    /** Adds a source of facilities; sources are asked in registration order and the first answer wins. */
    public static void register(BiFunction<Colony, UUID, Optional<? extends Operating>> source) {
        SOURCES.add(source);
    }

    private static final List<java.util.function.Function<Colony, List<? extends Operating>>> LISTS =
        new CopyOnWriteArrayList<>(List.of(DefinedOperating::all));

    /** Adds a source of a colony's facilities to list, such as the book's facility page. */
    public static void listing(java.util.function.Function<Colony, List<? extends Operating>> source) {
        LISTS.add(source);
    }

    /** Every facility the colony has that a player can open, source by source. */
    public static List<Operating> all(Colony colony) {
        List<Operating> all = new java.util.ArrayList<>();
        LISTS.forEach(source -> all.addAll(source.apply(colony)));
        return all;
    }

    public static Optional<Operating> find(Colony colony, UUID facility) {
        for (var source : SOURCES) {
            Optional<? extends Operating> found = source.apply(colony, facility);
            if (found.isPresent()) {
                return Optional.of(found.get());
            }
        }
        return Optional.empty();
    }
}
