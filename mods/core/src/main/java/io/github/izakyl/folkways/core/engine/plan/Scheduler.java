package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.engine.travel.Ways;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The second stage of planning: the plan as the weave showed it, taken change by change, laid out on routes as far
 * ahead as the horizon. It runs apart from the weave, on what the weave last showed: work woven since waits for the
 * next schedule, as work beyond the horizon waits for time to bring it nearer.
 */
public final class Scheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final Set<Unassigned.Reason> CREW_COULD_ANSWER = Set.of(Unassigned.Reason.NO_LICENCE,
        Unassigned.Reason.NO_TOOL, Unassigned.Reason.NO_WAY, Unassigned.Reason.PACK_FULL,
        Unassigned.Reason.PACK_BUSY, Unassigned.Reason.WORKER_AWAY);

    private final WeaveView weave = new WeaveView();
    private Tours tours = new Tours();
    private final Set<Unassigned> told = new HashSet<>();
    private Map<UUID, Tour> laid = Map.of();
    private Crew crew = new Crew(List.of());
    private Ways ways;

    // What one weave handed on: what changed, and the crew and ways it was woven for. A wake from time alone hands
    // on no change, and is laid for the crew and ways last heard of.
    public record Input(WeaveDelta delta, Crew crew, Ways ways, boolean retry, long tick) {

        public static Input wake(long tick) {
            return new Input(WeaveDelta.NONE, null, null, false, tick);
        }
    }

    public record Timing(long applyNanos, long scheduleNanos) {
    }

    // The routes that changed, by resident (an empty route where one ended), the work left unassigned and why,
    // whether the crew is worth reading again for it, when to wake for work past the horizon, every route laid, and
    // the residents with no route whose full packs keep work waiting.
    public record Scheduled(Map<UUID, Tour> changed, List<Unassigned> unassigned, boolean hands, long wakeAt,
                            Map<UUID, Tour> all, Set<UUID> overloaded, Timing timing, long tick) {
    }

    public Scheduled handle(List<Input> inputs) {
        long started = System.nanoTime();
        Set<UUID> fresh = new LinkedHashSet<>();
        boolean retry = false;
        long tick = Long.MIN_VALUE;
        for (Input input : inputs) {
            weave.apply(input.delta());
            fresh.removeAll(input.delta().removed());
            fresh.addAll(input.delta().fresh());
            if (input.crew() != null) {
                crew = input.crew();
                ways = input.ways();
            }
            retry |= input.retry();
            tick = Math.max(tick, input.tick());
        }
        long applied = System.nanoTime();
        if (ways == null) {
            return new Scheduled(Map.of(), List.of(), false, Long.MAX_VALUE, Map.of(), Set.of(),
                new Timing(applied - started, 0), tick);
        }
        Schedule schedule;
        try {
            schedule = tours.schedule(weave, fresh, crew, ways, tick, retry);
        } catch (RuntimeException | Error broken) {
            // Inputs are now part of the weave. Only the route projection is suspect: rebuilding it must not
            // replay messages, regrow material demands, or discard the last routes handed to the colony.
            // A new Tours reads every vertex, including its pins and the crew's work already in hand.
            tours = new Tours();
            throw broken;
        }
        tellStructural(schedule);
        Map<UUID, Tour> changed = new LinkedHashMap<>();
        schedule.tours().forEach((resident, tour) -> {
            if (!tour.equals(laid.get(resident))) {
                changed.put(resident, tour);
            }
        });
        for (UUID resident : laid.keySet()) {
            if (!schedule.tours().containsKey(resident)) {
                changed.put(resident, new Tour(resident, List.of()));
            }
        }
        laid = schedule.tours();
        boolean hands = false;
        for (Unassigned nobody : schedule.unassigned()) {
            hands |= CREW_COULD_ANSWER.contains(nobody.why());
        }
        return new Scheduled(changed, schedule.unassigned(), hands, tours.wakeAt(), laid, tours.overloaded(),
            new Timing(applied - started, System.nanoTime() - applied), tick);
    }

    public int size() {
        return weave.size();
    }

    // The plan grows only work someone is fit for, so the schedule leaving work to no one for good is a bug in how it
    // was grown: said once for each piece of work and reason, while there is a crew it could have been given to.
    private void tellStructural(Schedule schedule) {
        told.retainAll(schedule.unassigned());
        if (crew.hands().isEmpty()) {
            return;
        }
        for (Unassigned nobody : schedule.unassigned()) {
            if (nobody.why().structural() && told.add(nobody)) {
                LOGGER.error("the plan grew work no one can be given: {} is unassigned for {}", nobody.node(),
                    nobody.why());
            }
        }
    }

    public void closed() {
        weave.clear();
        tours.closed();
        tours = new Tours();
        told.clear();
        laid = Map.of();
        crew = new Crew(List.of());
        ways = null;
    }
}
