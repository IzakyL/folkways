package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.Stand;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record Placement(Set<Stand> stands, List<Amount> carrying, List<Stash> from, List<Stash> into,
                        List<Draw> draws, ReservationPool reservations, UUID stowing, DrawSettlement settlement) {

    public static final Placement NOWHERE = new Placement(Set.of(), List.of(), List.of(), List.of());

    // Goods the plan has set aside for this work, and where they are taken from: a store, or the worker's pack
    // when `from` is empty. The work's needs are drawn from these and nowhere else.
    public record Draw(Optional<Stash> from, ItemSpec spec, long count, Optional<UUID> resident,
                       Optional<UUID> producer) {
        public Draw(Optional<Stash> from, ItemSpec spec, long count) {
            this(from, spec, count, Optional.empty(), Optional.empty());
        }
    }

    public record DrawSettlement(UUID node, List<Runnable> booked) {

        public DrawSettlement {
            booked = List.copyOf(booked);
        }

        public void drawn(UUID committed) {
            if (node.equals(committed)) {
                booked.forEach(Runnable::run);
            }
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

    public Placement(Set<Stand> stands, List<Amount> carrying, List<Stash> from, List<Stash> into, List<Draw> draws) {
        this(stands, carrying, from, into, draws, null, null, null);
    }

    public Optional<Stash> firstFrom() {
        return from.isEmpty() ? Optional.empty() : Optional.of(from.get(0));
    }
}
