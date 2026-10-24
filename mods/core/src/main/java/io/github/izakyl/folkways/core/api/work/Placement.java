package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record Placement(Set<Stand> stands, List<Amount> carrying, List<Stash> from, List<Stash> into,
                        List<Draw> draws) {

    public static final Placement NOWHERE = new Placement(Set.of(), List.of(), List.of(), List.of());

    // Goods the plan has set aside for this work, and where they are taken from: a store, or the worker's pack
    // when `from` is empty. The work's needs are drawn from these and nowhere else.
    // The logical demand and its reserved payment ratio survive hauling and rule reloads.
    public record Input(ItemSpec requested, int cost) { }

    public record Draw(Optional<Stash> from, ItemSpec spec, long count, Optional<UUID> resident,
                       Optional<UUID> producer, Optional<Input> input) {
        public Draw(Optional<Stash> from, ItemSpec spec, long count, Optional<UUID> resident, Optional<UUID> producer) {
            this(from, spec, count, resident, producer, Optional.empty());
        }

        public Draw(Optional<Stash> from, ItemSpec spec, long count) {
            this(from, spec, count, Optional.empty(), Optional.empty());
        }
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
