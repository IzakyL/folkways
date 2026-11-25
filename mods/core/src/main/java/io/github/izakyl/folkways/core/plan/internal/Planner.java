package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.work.api.Stall;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The first stage of planning: what the colony reports, in the order it reports it, grows the plan - work asked for
 * is woven in as it comes, and what it needs grown before it - and what changed in the plan is handed on, to the
 * schedule and to the colony. Work is woven first come, first served: nothing asked jumps ahead of what was asked
 * before it.
 */
public final class Planner {

    private final Claims claims;
    private final Cooldowns cooldowns;
    private PlanInputs.Capture inputs;
    private final Weaver weaver;
    private long rounds;

    private final Map<UUID, UUID> inHand = new LinkedHashMap<>();

    private final Map<UUID, Long> readyTimes = new HashMap<>();

    public record Timing(long messageNanos, long weaveNanos) {
    }

    // One round of weaving: what changed, the crew and ways it was woven for (the schedule lays its routes for the
    // same), whether the world was answered or offered anew (so work kept waiting is worth trying again), and what
    // the plan has to say about itself.
    public record Woven(WeaveDelta delta, Crew crew, Ways ways, boolean retry, List<Shortfall> shortfalls,
                        List<Refused> refused, List<Stall> stalls, Asks asks, Timing timing, int nodes,
                        Map<UUID, Cooldowns.Bar> cooldowns, PlanInputs inputs, long tick,
                        List<PlanTask> attempted, boolean wanted, Map<UUID, List<Claim>> claims,
                        Map<UUID, Long> readyTimes) {

        public Woven {
            shortfalls = List.copyOf(shortfalls);
            refused = List.copyOf(refused);
            stalls = List.copyOf(stalls);
            cooldowns = Map.copyOf(cooldowns);
            attempted = List.copyOf(attempted);
            Map<UUID, List<Claim>> copy = new LinkedHashMap<>();
            claims.forEach((owner, entries) -> copy.put(owner, List.copyOf(entries)));
            claims = Map.copyOf(copy);
            readyTimes = Map.copyOf(readyTimes);
        }

        public Woven(WeaveDelta delta, Crew crew, Ways ways, boolean retry, List<Shortfall> shortfalls,
                     List<Refused> refused, List<Stall> stalls, Asks asks, Timing timing, int nodes) {
            this(delta, crew, ways, retry, shortfalls, refused, stalls, asks, timing, nodes, Map.of(),
                PlanInputs.EMPTY, -1, List.of(), false, Map.of(), Map.of());
        }

        public Woven(WeaveDelta delta, Crew crew, Ways ways, boolean retry, List<Shortfall> shortfalls,
                     List<Refused> refused, List<Stall> stalls, Asks asks, Timing timing, int nodes,
                     Map<UUID, List<Claim>> claims) {
            this(delta, crew, ways, retry, shortfalls, refused, stalls, asks, timing, nodes, Map.of(),
                PlanInputs.EMPTY, -1, List.of(), false, claims, Map.of());
        }
    }

    public Planner(Claims claims, Cooldowns cooldowns) {
        this(claims, cooldowns, new UUID(0, 0));
    }

    public Planner(Claims claims, Cooldowns cooldowns, UUID colony) {
        this.claims = claims;
        this.cooldowns = cooldowns;
        this.weaver = new Weaver(claims, cooldowns, colony);
    }

    // The last tours can identify the worker when a step finishes without a start report.
    public Woven handle(Known known, List<Message> inbox, long tick, Map<UUID, Tour> tours) {
        return handle(known, inbox, tick, tours, false);
    }

    public Woven handle(Known known, List<Message> inbox, long tick, Map<UUID, Tour> tours, boolean wanted) {
        rounds++;
        long started = System.nanoTime();
        cooldowns.round(tick);
        boolean retry = false;
        Map<UUID, UUID> touring = null;
        for (Message message : inbox) {
            switch (message) {
                case Message.Submitted(var owner, var work) -> weaver.submit(owner, work);
                case Message.Withdrawn(var owner, UUID node) -> weaver.withdraw(owner, node);
                case Message.NotReady(UUID node, long readyAt) -> {
                    if (weaver.contains(node) && !inHand.containsValue(node)) {
                        readyTimes.put(node, readyAt);
                    }
                }
                case Message.Ready(UUID node) -> readyTimes.remove(node);
                case Message.Started(UUID node, UUID resident) -> {
                    readyTimes.remove(node);
                    inHand.put(resident, node);
                    weaver.heldBy(node, resident, false);
                }
                case Message.Finished(UUID node) -> {
                    readyTimes.remove(node);
                    UUID worker = inHand.entrySet().stream().filter(held -> node.equals(held.getValue()))
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                    letGo(node);
                    if (worker == null) {
                        if (touring == null) {
                            touring = new HashMap<>();
                            for (Tour tour : tours.values()) {
                                for (UUID planned : tour.nodes()) {
                                    touring.put(planned, tour.resident());
                                }
                            }
                        }
                        worker = touring.get(node);
                    }
                    if (worker != null) {
                        weaver.heldBy(node, worker, true);
                    }
                    cooldowns.behind(node).ifPresent(cooldowns::succeeded);
                    weaver.finished(node);
                }
                case Message.Failed(UUID node, var why) -> {
                    readyTimes.remove(node);
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
                case Message.PackBusy(UUID resident) -> weaver.stow(resident);
                case Message.Offered ignored -> retry = true;
                case Message.Answered ignored -> retry = true;
            }
        }
        // A withdrawn request can remove its running dependencies without a Finished message.
        // Such nodes no longer occupy a hand; otherwise idle cargo recovery waits on them forever.
        inHand.values().removeIf(node -> !weaver.contains(node));
        Crew crew = crewOf(known);
        weaver.know(known.world(), crew, wanted);

        long digested = System.nanoTime();
        WeaveDelta delta = weaver.grow(retry);
        readyTimes.keySet().removeAll(delta.removed());
        readyTimes.keySet().removeAll(delta.fresh());
        readyTimes.keySet().removeIf(node -> !weaver.contains(node));
        // A committed node can leave the plan before its settlement is reported. Its runner has already
        // let go, so no later Released message is guaranteed to clear the planner's old holding.
        if (inHand.values().removeIf(delta.removed()::contains)) {
            crew = crewOf(known);
        }
        long woven = System.nanoTime();
        List<Shortfall> shortfalls = weaver.shortfalls();
        Map<UUID, Cooldowns.Bar> cooling = Map.of();
        PlanInputs captured = PlanInputs.EMPTY;
        List<PlanTask> attempted = List.of();
        if (wanted) {
            if (inputs == null) {
                inputs = new PlanInputs.Capture();
            }
            cooling = cooldowns.snapshot();
            captured = inputs.of(known, weaver.processes());
            attempted = weaver.attempted();
        } else {
            inputs = null;
        }
        return new Woven(delta, crew, known.world().ways(), retry, shortfalls, weaver.refused(),
            weaver.stalls(), asks(shortfalls, crew), new Timing(digested - started, woven - digested), weaver.size(),
            cooling, captured, tick, attempted, wanted, claims.snapshot(), readyTimes);
    }

    public long rounds() {
        return rounds;
    }

    public void closed() {
        weaver.closed();
        readyTimes.clear();
        inHand.clear();
    }

    private void letGo(UUID node) {
        inHand.values().removeIf(node::equals);
    }

    private Crew crewOf(Known known) {
        List<Crew.Hand> hands = new ArrayList<>(known.hands().size());
        for (Crew.Hand hand : known.hands()) {
            UUID held = inHand.get(hand.id());
            hands.add(new Crew.Hand(hand.who(), hand.at(), hand.packCells(), hand.packCapacity(), hand.pace(),
                hand.licences(), hand.tools(), Optional.ofNullable(held), hand.cargo(), hand.toolRules(),
                    hand.space(), hand.personalOnly()));
        }
        return new Crew(hands);
    }

    // What the weave asks of the colony: stores read again where goods ran short or room did, hands read again
    // where no one could be found, the packs of those putting goods away, and a wake when a resting choice opens.
    // The schedule adds what it asks for itself.
    private Asks asks(List<Shortfall> shortfalls, Crew crew) {
        boolean survey = false;
        boolean hands = crew.hands().isEmpty() && weaver.asking();
        for (Shortfall shortfall : shortfalls) {
            survey |= shortfall.why() instanceof Why.NoSource || shortfall.why() instanceof Why.NoRoom;
            hands |= shortfall.why() instanceof Why.NoHand;
        }
        long wakeAt = cooldowns.nextOpening().orElse(Long.MAX_VALUE);
        return new Asks(survey, weaver.looks(), hands, weaver.stowing(), wakeAt);
    }
}
