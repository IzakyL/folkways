package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
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
    final SequencedSet<UUID> nodes = new LinkedHashSet<>();
    final List<Before> links = new ArrayList<>();
    final List<Flow> flows = new ArrayList<>();
    final List<Set<UUID>> together = new ArrayList<>();
    final List<Hold> holds = new ArrayList<>();
    final List<Claim> claims = new ArrayList<>();
    final List<Touch> touches = new ArrayList<>();
    long covers;
    boolean settled;
    // Further edges fed from the same making, each covered whole by what it asks, and the consumers that finished
    // with their share - fed edges no growth answers any more.
    final List<Edge> also = new ArrayList<>();
    final Set<UUID> detached = new LinkedHashSet<>();
    // A workshop's making: what it makes, whose work makes it, and the offer it was taken from - so a further edge
    // for the same goods can be added onto it rather than growing a making of its own.
    Making making;
    // Some of its work is done or in hand: it is no longer replaced whole.
    boolean started;
    // Its workshop makes no more than it already does in one go: nothing more is joined onto it.
    boolean full;

    record Making(ItemSpec made, UUID maker, Stash out, Costs.Offer offer) {
    }

    Expansion(Edge serves) {
        this.serves = serves;
        this.asked = null;
    }

    Expansion(UUID asked) {
        this.serves = null;
        this.asked = asked;
    }

    // The index of what live growths bind, while this one is live: every link, flow and set it gains or gives back
    // is told to it.
    private Bonds bonds;

    void bind(Bonds bonds) {
        this.bonds = bonds;
        links.forEach(link -> bonds.order(link, 1));
        flows.forEach(flow -> flow.after().ifPresent(from -> bonds.order(new Before(from, flow.to()), 1)));
        together.forEach(set -> bonds.together(set, 1));
    }

    void unbind() {
        if (bonds == null) {
            return;
        }
        links.forEach(link -> bonds.order(link, -1));
        flows.forEach(flow -> flow.after().ifPresent(from -> bonds.order(new Before(from, flow.to()), -1)));
        together.forEach(set -> bonds.together(set, -1));
        bonds = null;
    }

    void link(Before link) {
        links.add(link);
        if (bonds != null) {
            bonds.order(link, 1);
        }
    }

    void flow(Flow flow) {
        flows.add(flow);
        if (bonds != null) {
            flow.after().ifPresent(from -> bonds.order(new Before(from, flow.to()), 1));
        }
    }

    void together(Set<UUID> set) {
        Set<UUID> kept = Set.copyOf(set);
        together.add(kept);
        if (bonds != null) {
            bonds.together(kept, 1);
        }
    }

    // Gives back the links, flows and sets gained since `mark`.
    void truncate(Mark mark) {
        List<Before> lost = links.subList(mark.links(), links.size());
        List<Flow> gone = flows.subList(mark.flows(), flows.size());
        List<Set<UUID>> parted = together.subList(mark.together(), together.size());
        if (bonds != null) {
            lost.forEach(link -> bonds.order(link, -1));
            gone.forEach(flow -> flow.after().ifPresent(from -> bonds.order(new Before(from, flow.to()), -1)));
            parted.forEach(set -> bonds.together(set, -1));
        }
        lost.clear();
        gone.clear();
        parted.clear();
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
        return serves.reopening(covers);
    }

    // Every edge it still feeds, each for just what it brings that edge.
    List<Edge> fed() {
        List<Edge> fed = new ArrayList<>();
        if (!detached.contains(serves.consumer())) {
            fed.add(reopened());
        }
        for (Edge edge : also) {
            if (!detached.contains(edge.consumer())) {
                fed.add(edge);
            }
        }
        return fed;
    }

    // Every consumer it was grown to feed, finished or not.
    List<UUID> consumers() {
        List<UUID> all = new ArrayList<>();
        all.add(serves.consumer());
        also.forEach(edge -> all.add(edge.consumer()));
        return all;
    }

    // Whether it feeds more than one consumer still.
    boolean shared() {
        return fed().size() > 1;
    }
}
