package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Tools;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Whether one resident can do a piece of work alone: the trade and the tools every step of it asks for, whoever it
// is bound to, and a pack big enough for the most it holds at once. The plan grows work only for someone fit for it,
// and the schedule gives work only to whoever is; both ask here, so they never disagree. A pack too full for it now
// only means waiting until it empties, which is the schedule's to arrange.
final class Fitness {

    enum Unfit {
        BOUND_ELSEWHERE,
        NO_LICENCE,
        NO_TOOL,
        NO_ROOM
    }

    // `bound` is everyone some step is bound to: more than one, and no one is fit. `cells` is the room the work
    // needs in the pack at once, none when that is not asked.
    record Work(List<NodeSpec> steps, Set<UUID> bound, long cells) {

        Work {
            steps = List.copyOf(steps);
            bound = Set.copyOf(bound);
        }
    }

    private Fitness() {
    }

    static Optional<Unfit> of(Crew.Hand hand, Work work) {
        if (!work.bound().isEmpty() && !work.bound().equals(Set.of(hand.id()))) {
            return Optional.of(Unfit.BOUND_ELSEWHERE);
        }
        for (NodeSpec step : work.steps()) {
            if (!hand.takes(step.vocation())) {
                return Optional.of(Unfit.NO_LICENCE);
            }
        }
        for (NodeSpec step : work.steps()) {
            if (!Tools.equipped(hand.who().kind(), step, hand.tools())) {
                return Optional.of(Unfit.NO_TOOL);
            }
        }
        if (work.cells() > hand.packCapacity()) {
            return Optional.of(Unfit.NO_ROOM);
        }
        return Optional.empty();
    }

    static boolean anyone(Crew crew, Work work) {
        for (Crew.Hand hand : crew.hands()) {
            if (of(hand, work).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // Whether someone fit for the work has the room for it in their pack already, not only once it empties.
    static boolean anyoneNow(Crew crew, Work work) {
        for (Crew.Hand hand : crew.hands()) {
            if (of(hand, work).isEmpty() && work.cells() <= hand.packCells()) {
                return true;
            }
        }
        return false;
    }
}
