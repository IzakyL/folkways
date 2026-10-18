package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Stall;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// What a colony's work in one dimension stands still for, as the player is told it: what the plan could not grow,
// once it has stayed so for a while, and the lasting refusals residents met more than once at the same place. A gap the
// next plan fills, or a refusal met once, is never told.
final class Stalls {

    private static final long SETTLE_TICKS = 100L;

    private static final int MET_TIMES = 2;

    // How long a refusal met at work is told after it was last met, unless work there is done first.
    private static final long FORGET_TICKS = 1_200L;

    private record Met(WorldPos where, RefusalKind why) {
    }

    private static final class Tally {

        private int times;
        private long last;
        private Stall stall;
    }

    private final Map<Stall, Long> planned = new LinkedHashMap<>();
    private final Map<Met, Tally> met = new LinkedHashMap<>();
    private List<Stall> told = List.of();

    void planned(List<Stall> standing, long now) {
        planned.keySet().retainAll(Set.copyOf(standing));
        for (Stall stall : standing) {
            planned.putIfAbsent(stall, now);
        }
    }

    void met(NodeSpec spec, RefusalKind why, long now) {
        if (!why.lasting()) {
            return;
        }
        WorldPos where = spec.site().where();
        Tally tally = met.computeIfAbsent(new Met(where, why), key -> new Tally());
        if (now - tally.last >= FORGET_TICKS) {
            tally.times = 0;
        }
        tally.times++;
        tally.last = now;
        tally.stall = new Stall(where, why, Optional.empty(), spec.doing(), spec.about());
    }

    void done(NodeSpec spec) {
        WorldPos where = spec.site().where();
        met.keySet().removeIf(one -> one.where().equals(where));
    }

    // What is to be told now, when it is not what was told last.
    Optional<List<Stall>> changed(long now) {
        met.values().removeIf(tally -> now - tally.last >= FORGET_TICKS);
        Set<Stall> standing = new LinkedHashSet<>();
        planned.forEach((stall, since) -> {
            if (now - since >= SETTLE_TICKS) {
                standing.add(stall);
            }
        });
        for (Tally tally : met.values()) {
            if (tally.times >= MET_TIMES) {
                standing.add(tally.stall);
            }
        }
        List<Stall> telling = List.copyOf(standing);
        if (telling.equals(told)) {
            return Optional.empty();
        }
        told = telling;
        return Optional.of(telling);
    }

    void clear() {
        planned.clear();
        met.clear();
        told = List.of();
    }
}
