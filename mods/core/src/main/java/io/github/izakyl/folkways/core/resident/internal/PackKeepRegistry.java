package io.github.izakyl.folkways.core.resident.internal;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.PackKeeps.Rule;
import io.github.izakyl.folkways.core.colony.api.PackKeeps;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Amount;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

// What a resident keeps in their pack beyond the tools of their trades, such as a couple of meals: goods held this
// way are not cargo, so they are neither put away when the resident goes idle nor drawn on for others' work. Core
// only asks; each plugin that wants something kept answers for its own goods, and until one does nothing is kept.
public final class PackKeepRegistry implements PackKeeps.Access {

    private static final List<Rule> RULES = new CopyOnWriteArrayList<>();

    private PackKeepRegistry() {
    }

    @Override
    public void add(Rule rule) {
        RULES.add(Objects.requireNonNull(rule));
    }

    @Override
    public List<Amount> of(Colony colony, Body body) {
        List<Amount> kept = new ArrayList<>();
        for (Rule rule : RULES) {
            kept.addAll(rule.keeps(colony, body));
        }
        return List.copyOf(kept);
    }

    public static void install() {
        PackKeeps.install(new PackKeepRegistry());
    }
}
