package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record Placement(Set<Stand> stands, List<Amount> carrying, List<Stash> from, List<Stash> into,
                        List<Draw> draws) {

    public static final Placement NOWHERE = new Placement(Set.of(), List.of(), List.of(), List.of());

    // Goods the plan has set aside for this work, and where they are taken from: a store, or the worker's pack
    // when `from` is empty. The work's needs are drawn from these and nowhere else.
    public record Draw(Optional<Stash> from, ItemSpec spec, long count) {
    }

    public Placement {
        stands = Set.copyOf(stands);
        carrying = List.copyOf(carrying);
        from = List.copyOf(from);
        into = List.copyOf(into);
        draws = List.copyOf(draws);
    }

    public Placement(Set<Stand> stands, List<Amount> carrying, List<Stash> from, List<Stash> into) {
        this(stands, carrying, from, into, List.of());
    }

    public Optional<Stash> firstFrom() {
        return from.isEmpty() ? Optional.empty() : Optional.of(from.get(0));
    }
}
