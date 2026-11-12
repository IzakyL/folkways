package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Sizing;
import io.github.izakyl.folkways.core.resident.api.Pack;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// How much the plan grows at once this round, sized to the packs of the crew it knows: a carry is what the roomiest
// hauler's pack holds, and a run of work no more than one pack carries the inputs for, shared among the hands that
// may do it. Asked only while intents are refined into work.
final class PackSizing implements Sizing {

    private final Crew crew;
    private final Map<Optional<Vocation>, Integer> roomiest = new HashMap<>();
    private final Map<Optional<Vocation>, Long> takers = new HashMap<>();

    PackSizing(Crew crew) {
        this.crew = crew;
    }

    @Override
    public long runs(WorkSite at, List<Need> perRun, Optional<Vocation> by, long limit) {
        return carriedRuns(at, perRun.stream().filter(Need::carried).toList(), limit, by);
    }

    // How many runs of work a workshop is asked for: no more than the hands that may do it share between them,
    // and no more than one pack carries the inputs for. `whole` is everything the edge asking for it needs.
    Produce.Batching batching(long whole) {
        return (at, perRun, perRunYield, by, limit) ->
            runs(at, perRun, by, Math.min(limit, shareOf(by, (whole + perRunYield - 1) / perRunYield)));
    }

    // The most of `concrete` one carry takes.
    long perTrip(ItemSpec concrete) {
        return crew.hands().stream().filter(hand -> hand.takes(Vocations.of(Vocations.HAULING)))
            // A temporarily full crew must still grow fetch work so scheduling can request cargo recovery.
            .mapToLong(hand -> hand.capacity(concrete)).filter(capacity -> capacity > 0).max()
            .orElse((long) Math.max(1, Goods.stackSize(concrete)) * Pack.BASE_PLANNABLE_CELLS);
    }

    private long shareOf(Optional<Vocation> by, long runs) {
        if (by.isEmpty()) {
            return runs;
        }
        long hands = takers.computeIfAbsent(by,
            trade -> crew.hands().stream().filter(hand -> hand.takes(trade)).count());
        return hands <= 1 ? runs : (runs + hands - 1) / hands;
    }

    private long carriedRuns(WorkSite at, List<Need> carried, long limit, Optional<Vocation> by) {
        if (carried.isEmpty() || limit <= 1) {
            return limit;
        }
        long room;
        if (at instanceof WorkSite.AtEntity entity) {
            room = -1;
            for (Crew.Hand hand : crew.hands()) {
                if (hand.takes(by) && entity.entity().equals(hand.id())) {
                    room = hand.packCells();
                }
            }
        } else {
            room = roomiest.computeIfAbsent(by, trade -> {
                int most = -1;
                for (Crew.Hand hand : crew.hands()) {
                    if (hand.takes(trade)) {
                        most = Math.max(most, hand.packCells());
                    }
                }
                return most;
            });
        }
        if (room < 0) {
            return 1;
        }
        long low = 1;
        long high = limit;
        while (low < high) {
            long runs = low + (high - low + 1) / 2;
            boolean nativeFit = crew.hands().stream().filter(hand -> hand.takes(by))
                .filter(hand -> !(at instanceof WorkSite.AtEntity entity) || entity.entity().equals(hand.id()))
                .anyMatch(hand -> hand.space() != null && hand.load(carried.stream().collect(
                    java.util.stream.Collectors.toMap(Need::spec, need -> need.count() * runs, Long::sum)))
                    != Integer.MAX_VALUE);
            if (nativeFit || crew.hands().stream().noneMatch(hand -> hand.space() != null) && fits(carried, runs,
                room)) {
                low = runs;
            } else {
                high = runs - 1;
            }
        }
        return low;
    }

    private static boolean fits(List<Need> carried, long runs, long room) {
        for (Need need : carried) {
            long stack = need.spec().item().isPresent() ? Math.max(1, Goods.stackSize(need.spec())) : 1;
            if (need.count() > room * stack / runs) {
                return false;
            }
            long count = need.count() * runs;
            room -= (count + stack - 1) / stack;
        }
        return true;
    }

}
