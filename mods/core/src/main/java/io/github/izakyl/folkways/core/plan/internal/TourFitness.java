package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.work.api.NodeSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TourFitness {

    final Map<UUID, UUID> heldBy = new HashMap<>();
    private final Map<UUID, byte[]> fits = new HashMap<>();
    private WeaveView weave;

    void know(WeaveView weave, Crew crew) {
        this.weave = weave;
        fits.clear();
        heldBy.clear();
        for (Crew.Hand hand : crew.hands()) {
            hand.holding().ifPresent(node -> heldBy.put(node, hand.id()));
        }
    }

    UUID boundWorker(UUID id, Map<UUID, UUID> seatOf) {
        for (UUID member : weave.group(id)) {
            UUID hard = requiredWorker(member);
            if (hard != null) {
                return hard;
            }
        }
        for (UUID member : weave.group(id)) {
            UUID seat = seatOf.get(member);
            if (seat != null) {
                return seat;
            }
        }
        return null;
    }

    UUID requiredWorker(UUID id) {
        UUID holder = heldBy.get(id);
        if (holder != null) {
            return holder;
        }
        UUID pinned = weave.pin(id);
        if (pinned != null) {
            return pinned;
        }
        return weave.vertex(id).node().worker().orElse(null);
    }

    private static final Unassigned.Reason[] REASONS = Unassigned.Reason.values();

    // Asked of every hand for every piece of work, more than once a schedule, and the same each time within one.
    Unassigned.Reason refusal(Crew.Hand hand, int slot, int seats, UUID id) {
        byte[] known = fits.computeIfAbsent(id, key -> new byte[seats]);
        if (slot >= known.length) {
            return unfit(hand, id);
        }
        if (known[slot] == 0) {
            Unassigned.Reason why = unfit(hand, id);
            known[slot] = (byte) (why == null ? 1 : why.ordinal() + 2);
        }
        return known[slot] == 1 ? null : REASONS[known[slot] - 2];
    }

    private Unassigned.Reason unfit(Crew.Hand hand, UUID id) {
        Set<UUID> bound = new LinkedHashSet<>();
        List<NodeSpec> steps = new ArrayList<>();
        for (UUID member : weave.group(id)) {
            UUID pinned = requiredWorker(member);
            if (pinned != null) {
                bound.add(pinned);
            }
            steps.add(weave.vertex(member).node().spec());
        }
        return Fitness.of(hand, new Fitness.Work(steps, bound, 0)).map(unfit -> switch (unfit) {
            case BOUND_ELSEWHERE -> Unassigned.Reason.SAME_WORKER_SPLIT;
            case NO_LICENCE -> Unassigned.Reason.NO_LICENCE;
            case NO_TOOL -> Unassigned.Reason.NO_TOOL;
            case NO_ROOM -> Unassigned.Reason.PACK_FULL;
        }).orElse(null);
    }

    void clear() {
        fits.clear();
    }
}
