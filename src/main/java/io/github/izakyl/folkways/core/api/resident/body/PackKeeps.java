package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.work.Amount;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

// What a resident keeps in their pack beyond the tools of their trades, such as a couple of meals: goods held this
// way are not cargo, so they are neither put away when the resident goes idle nor drawn on for others' work. Core
// only asks; each plugin that wants something kept answers for its own goods, and until one does nothing is kept.
public final class PackKeeps {

    @FunctionalInterface
    public interface Rule {
        List<Amount> keeps(Colony colony, Body body);
    }

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>();

    private PackKeeps() {
    }

    public static void add(Rule rule) {
        RULES.add(Objects.requireNonNull(rule));
    }

    public static List<Amount> of(Colony colony, Body body) {
        List<Amount> kept = new ArrayList<>();
        for (Rule rule : RULES) {
            kept.addAll(rule.keeps(colony, body));
        }
        return List.copyOf(kept);
    }
}
