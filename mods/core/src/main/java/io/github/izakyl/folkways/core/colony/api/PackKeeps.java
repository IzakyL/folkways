package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Amount;
import java.util.List;
import java.util.Objects;

// What a resident keeps in their pack beyond the tools of their trades, such as a couple of meals: goods held this
// way are not cargo, so they are neither put away when the resident goes idle nor drawn on for others' work. Core
// only asks; each plugin that wants something kept answers for its own goods, and until one does nothing is kept.
public final class PackKeeps {

    @FunctionalInterface
    public interface Rule {
        List<Amount> keeps(Colony colony, Body body);
    }

    public interface Access {

        void add(Rule rule);

        List<Amount> of(Colony colony, Body body);
    }

    private static Access access;

    private PackKeeps() {
    }

    public static void install(Access installed) {
        access = Objects.requireNonNull(installed);
    }

    public static void add(Rule rule) {
        access.add(rule);
    }

    public static List<Amount> of(Colony colony, Body body) {
        return access.of(colony, body);
    }
}
