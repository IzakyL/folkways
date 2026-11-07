package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.transport.api.TransportSnapshot;
import io.github.izakyl.folkways.core.work.api.Hold;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.resources.ResourceLocation;

public interface Refinement {
    ResourceLocation id();
    default Optional<Grown> expand(Intent intent) { return Optional.empty(); }

    default Optional<Change> rewrite(Intent intent, Grown graph) {
        return expand(intent).map(Change::of);
    }

    // The rewrite as the plan asks for it, lending what it knows of how much to grow at once. A rule that sizes its
    // work does it here, and only here.
    default Optional<Change> rewrite(Intent intent, Grown graph, Context context) {
        return rewrite(intent, graph);
    }

    // Empty while the cost is unknown; Long.MAX_VALUE when no way is possible.
    default OptionalLong estimate(Intent intent, Context context) {
        return OptionalLong.empty();
    }

    // Material refinements fulfill an existing edge, which may need no new work node. They grow the lent
    // expansion, including its flows and reservations. Empty means this rule does not handle the intent;
    // zero means it tried but could not lay any goods.
    default OptionalLong lay(Intent intent, Context context) {
        return OptionalLong.empty();
    }

    // A read-only view of this round, including the crew's carrying room for the goods being priced.
    interface Planning {
        long now();

        TransportPublication transports();

        TransportSnapshot snapshot(ItemSpec goods);
    }

    // The plan owns the expansion and undoes a failed attempt, including holds and capacity reservations.
    // Rules may extend this capability with the material operations they need.
    interface Laying {
        interface Mark {
        }

        Mark mark();

        void rollback(Mark mark);

        long attempt(LongSupplier work);
    }

    // What the plan lends a rule while it refines.
    interface Context {

        Sizing sizing();

        default Optional<Planning> planning() {
            return Optional.empty();
        }

        default Optional<Laying> laying() {
            return Optional.empty();
        }

        Context NONE = () -> Sizing.UNBOUNDED;
    }

    default List<Change> options(Intent intent, Grown graph) {
        return rewrite(intent, graph).map(List::of).orElse(List.of());
    }

    record Change(Grown replacement, List<Before> links, List<Set<UUID>> workerGroups,
                  List<Hold> holds) {
        public Change {
            links = List.copyOf(links);
            workerGroups = workerGroups.stream().map(Set::copyOf).toList();
            holds = List.copyOf(holds);
        }

        public Change(Grown replacement, List<Before> links, List<Set<UUID>> workerGroups) {
            this(replacement, links, workerGroups, List.of());
        }

        public static Change of(Grown replacement) {
            return new Change(replacement, List.of(), List.of());
        }

        public Change holding(Hold... more) {
            List<Hold> all = new ArrayList<>(holds);
            all.addAll(List.of(more));
            return new Change(replacement, links, workerGroups, all);
        }
    }
}
