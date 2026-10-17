package io.github.izakyl.folkways.core.api.work;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

    // What the plan lends a rule while it refines.
    interface Context {

        Sizing sizing();

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
