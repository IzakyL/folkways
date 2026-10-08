package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Hold;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;
import java.util.UUID;

/**
 * One growth of the plan, kept whole: a request's own work, or what an open edge grew. It holds everything that
 * growth put on the graph - nodes, the logic and material edges between them, workers bound together, bookings,
 * holds, and what it says about where nodes stand and what they carry - so taking it back is removing it, and the
 * edge it served is open again.
 */
final class Expansion {

    // What the growth says about one node: where it may stand, what it picks up (positive) or puts down, the stores
    // it reaches into or fills, the goods it hands on, and who must do it. Any of them may be absent.
    record Touch(UUID node, Set<Stand> stands, Amount carrying, Stash from, Stash into, Manifest cargo,
                 UUID worker) {

        Touch {
            stands = Set.copyOf(stands);
        }

        static Touch standing(UUID node, Set<Stand> stands) {
            return new Touch(node, stands, null, null, null, null, null);
        }

        static Touch carrying(UUID node, Amount carrying) {
            return new Touch(node, Set.of(), carrying, null, null, null, null);
        }

        static Touch from(UUID node, Stash from) {
            return new Touch(node, Set.of(), null, from, null, null, null);
        }

        static Touch into(UUID node, Stash into) {
            return new Touch(node, Set.of(), null, null, into, null, null);
        }

        static Touch handed(UUID node, Manifest cargo) {
            return new Touch(node, Set.of(), null, null, null, cargo, null);
        }

        static Touch by(UUID node, UUID worker) {
            return new Touch(node, Set.of(), null, null, null, null, worker);
        }
    }

    // What the growth answers: the edge it feeds, or - with no edge - what was asked of the plan itself.
    final Edge serves;
    final UUID asked;
    final int rank;
    final SequencedSet<UUID> nodes = new LinkedHashSet<>();
    final List<Before> links = new ArrayList<>();
    final List<Flow> flows = new ArrayList<>();
    final List<Set<UUID>> together = new ArrayList<>();
    final List<Hold> holds = new ArrayList<>();
    final List<Claim> claims = new ArrayList<>();
    final List<Touch> touches = new ArrayList<>();
    long covers;
    boolean settled;

    Expansion(Edge serves) {
        this.serves = serves;
        this.asked = null;
        this.rank = serves.rank();
    }

    Expansion(UUID asked, int rank) {
        this.serves = null;
        this.asked = asked;
        this.rank = rank;
    }

    // How far the growth had got: what it gains after, it can give back.
    record Mark(int nodes, int links, int flows, int together, int claims, int touches) implements Laying.Mark {
    }

    Mark mark() {
        return new Mark(nodes.size(), links.size(), flows.size(), together.size(), claims.size(), touches.size());
    }

    boolean isRequest() {
        return serves == null;
    }

    // The edge again, for just what this growth was bringing.
    Edge reopened() {
        return serves.covering(covers);
    }
}
