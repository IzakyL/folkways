package io.github.izakyl.folkways.core.engine.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Planner {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final Set<Unassigned.Reason> CREW_COULD_ANSWER = Set.of(Unassigned.Reason.NO_LICENCE,
        Unassigned.Reason.NO_TOOL, Unassigned.Reason.NO_WAY, Unassigned.Reason.PACK_FULL,
        Unassigned.Reason.PACK_BUSY, Unassigned.Reason.WORKER_AWAY);

    private final Cooldowns cooldowns;
    private final Weaver weaver;
    private Tours tours = new Tours();

    private final Map<UUID, UUID> inHand = new LinkedHashMap<>();
    private final Set<Unassigned> told = new HashSet<>();

    private Solved last = Solved.NOTHING;

    public record Timing(long messageNanos, long weaveNanos, long scheduleNanos) {
    }

    private Timing timing = new Timing(0, 0, 0);

    public Planner(Claims claims, Cooldowns cooldowns) {
        this.cooldowns = cooldowns;
        this.weaver = new Weaver(claims, cooldowns);
    }

    public Timing timing() {
        return timing;
    }

    public Solved handle(Known known, List<Message> inbox, long tick) {
        long started = System.nanoTime();
        cooldowns.round(tick);
        boolean retry = false;
        Map<UUID, UUID> touring = null;
        for (Message message : inbox) {
            switch (message) {
                case Message.Submitted ignored -> { }
                case Message.Withdrawn ignored -> { }
                case Message.Started(UUID node, UUID resident) -> {
                    inHand.put(resident, node);
                    weaver.heldBy(node, resident, false);
                }
                case Message.Finished(UUID node) -> {
                    letGo(node);
                    if (touring == null) {
                        touring = new HashMap<>();
                        for (Tour tour : last.schedule().tours().values()) {
                            for (UUID planned : tour.nodes()) {
                                touring.put(planned, tour.resident());
                            }
                        }
                    }
                    UUID worker = touring.get(node);
                    if (worker != null) {
                        weaver.heldBy(node, worker, true);
                    }
                    cooldowns.behind(node).ifPresent(cooldowns::succeeded);
                    weaver.finished(node);
                }
                case Message.Failed(UUID node, var why) -> {
                    letGo(node);
                    cooldowns.behind(node).ifPresent(cooldowns::failed);
                    weaver.failed(node, why);
                }
                case Message.Released(UUID node) -> {
                    letGo(node);
                    weaver.released(node);
                }
                case Message.Left(UUID resident) -> {
                    inHand.remove(resident);
                    weaver.left(resident);
                }
                case Message.Idle(UUID resident) -> weaver.stow(resident);
                case Message.Offered ignored -> retry = true;
                case Message.Answered ignored -> retry = true;
            }
        }
        for (Message message : inbox) {
            switch (message) {
                case Message.Submitted(var owner, var work) -> weaver.submit(owner, work);
                case Message.Withdrawn(var owner, UUID node) -> weaver.withdraw(owner, node);
                default -> { }
            }
        }
        Crew crew = crewOf(known);
        weaver.know(known.world(), crew);

        long digested = System.nanoTime();
        Weave weave = weaver.grow(retry);
        long woven = System.nanoTime();
        Schedule schedule = tours.schedule(weave, crew, known.world().ways(), tick, retry);
        tellStructural(schedule, crew);
        timing = new Timing(digested - started, woven - digested, System.nanoTime() - woven);
        List<Shortfall> shortfalls = weaver.shortfalls();
        Diagnosis diagnosis = new Diagnosis(shortfalls, schedule.unassigned(), weaver.refused());
        last = new Solved(weave, schedule, diagnosis, asks(shortfalls, schedule, crew, tick));
        return last;
    }

    // The plan grows only work someone is fit for, so the schedule leaving work to no one for good is a bug in how it
    // was grown: said once for each piece of work and reason, while there is a crew it could have been given to.
    private void tellStructural(Schedule schedule, Crew crew) {
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
        weaver.closed();
        inHand.clear();
        told.clear();
        tours = new Tours();
        last = Solved.NOTHING;
    }

    private void letGo(UUID node) {
        inHand.values().removeIf(node::equals);
    }

    private Crew crewOf(Known known) {
        List<Crew.Hand> hands = new ArrayList<>(known.hands().size());
        for (Crew.Hand hand : known.hands()) {
            UUID held = inHand.get(hand.id());
            hands.add(new Crew.Hand(hand.who(), hand.at(), hand.packCells(), hand.packCapacity(), hand.pace(),
                hand.licences(), hand.tools(), Optional.ofNullable(held), hand.cargo()));
        }
        return new Crew(hands);
    }

    private Asks asks(List<Shortfall> shortfalls, Schedule schedule, Crew crew, long tick) {
        boolean survey = false;
        boolean hands = crew.hands().isEmpty() && weaver.asking();
        for (Shortfall shortfall : shortfalls) {
            survey |= shortfall.why() instanceof Why.NoSource || shortfall.why() instanceof Why.NoRoom;
            hands |= shortfall.why() instanceof Why.NoHand;
        }
        boolean beyond = false;
        for (Unassigned nobody : schedule.unassigned()) {
            hands |= CREW_COULD_ANSWER.contains(nobody.why());
            beyond |= nobody.why() == Unassigned.Reason.BEYOND_HORIZON;
        }
        long wakeAt = cooldowns.nextOpening().orElse(Long.MAX_VALUE);
        if (beyond) {
            // Work left past the horizon comes nearer by itself: look again before it would have started.
            wakeAt = Math.min(wakeAt, tick + Math.max(1, tours.horizon() / 3));
        }
        return new Asks(survey, weaver.looks(), hands, weaver.stowing(), wakeAt);
    }
}
