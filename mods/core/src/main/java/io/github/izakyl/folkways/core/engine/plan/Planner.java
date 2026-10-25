package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Stall;
import io.github.izakyl.folkways.core.engine.travel.Ways;
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

    private final Cooldowns cooldowns;
    private final Weaver weaver;

    private final Map<UUID, UUID> inHand = new LinkedHashMap<>();

    public record Timing(long messageNanos, long weaveNanos) {
    }

    // One round of weaving: what changed, the crew and ways it was woven for (the schedule lays its routes for the
    // same), whether the world was answered or offered anew (so work kept waiting is worth trying again), and what
    // the plan has to say about itself.
    public record Woven(WeaveDelta delta, Crew crew, Ways ways, boolean retry, List<Shortfall> shortfalls,
                        List<Refused> refused, List<Stall> stalls, Asks asks, Timing timing, int nodes) {
    }

    public Planner(Claims claims, Cooldowns cooldowns) {
        this.cooldowns = cooldowns;
        this.weaver = new Weaver(claims, cooldowns);
    }

    // `tours` are the routes the schedule last laid, as far as this stage has heard: work that finishes on one is
    // the toured worker's from then on.
    public Woven handle(Known known, List<Message> inbox, long tick, Map<UUID, Tour> tours) {
        long started = System.nanoTime();
        cooldowns.round(tick);
        boolean retry = false;
        Map<UUID, UUID> touring = null;
        for (Message message : inbox) {
            switch (message) {
                case Message.Submitted(var owner, var work) -> weaver.submit(owner, work);
                case Message.Withdrawn(var owner, UUID node) -> weaver.withdraw(owner, node);
                case Message.Started(UUID node, UUID resident) -> {
                    inHand.put(resident, node);
                    weaver.heldBy(node, resident, false);
                }
                case Message.Finished(UUID node) -> {
                    letGo(node);
                    if (touring == null) {
                        touring = new HashMap<>();
                        for (Tour tour : tours.values()) {
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
                case Message.PackBusy(UUID resident) -> weaver.stow(resident);
                case Message.Offered ignored -> retry = true;
                case Message.Answered ignored -> retry = true;
            }
        }
        Crew crew = crewOf(known);
        weaver.know(known.world(), crew);

        long digested = System.nanoTime();
        WeaveDelta delta = weaver.grow(retry);
        long woven = System.nanoTime();
        List<Shortfall> shortfalls = weaver.shortfalls();
        return new Woven(delta, crew, known.world().ways(), retry, shortfalls, weaver.refused(),
            weaver.stalls(), asks(shortfalls, crew), new Timing(digested - started, woven - digested), weaver.size());
    }

    public void closed() {
        weaver.closed();
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
                hand.licences(), hand.tools(), Optional.ofNullable(held), hand.cargo()));
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
