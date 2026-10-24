package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkProgress;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonySnapshot;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.colony.ColonyWorks;
import io.github.izakyl.folkways.core.engine.plan.Claims;
import io.github.izakyl.folkways.core.engine.plan.Cooldowns;
import io.github.izakyl.folkways.core.engine.plan.Diagnosis;
import io.github.izakyl.folkways.core.engine.plan.Gone;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PackLoad;
import io.github.izakyl.folkways.core.engine.plan.Planner;
import io.github.izakyl.folkways.core.engine.plan.Schedule;
import io.github.izakyl.folkways.core.engine.plan.Asks;
import io.github.izakyl.folkways.core.engine.plan.Refused;
import io.github.izakyl.folkways.core.engine.plan.Scheduler;
import io.github.izakyl.folkways.core.engine.plan.Shortfall;
import io.github.izakyl.folkways.core.engine.plan.Tour;
import io.github.izakyl.folkways.core.engine.plan.Unassigned;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.WeaveDelta;
import io.github.izakyl.folkways.core.engine.plan.WeaveView;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;

public final class ColonyLabor implements ColonyWorks.Listening {

    private static final long DESERTED_TICKS = 1_200L;

    private static final int MOST_ROUNDS = 2;

    private static final long WAYS_ANSWER_TICKS = 20L;

    private final ColonyData colony;
    private final ResourceKey<Level> dimension;

    private Lanes lanes = Lanes.INLINE;

    private final Phases phases = new Phases(this::ending);
    private record Ended(UUID node, Ending how) { }
    private final List<Ended> endings = new ArrayList<>();
    private final List<UUID> withdrawnGone = new ArrayList<>();
    private final Phases personal = new Phases();
    private record Personal(UUID resident, ResourceLocation urge) { }
    private final Map<UUID, Personal> personalRequests = new LinkedHashMap<>();
    private final Set<UUID> reportedFailures = new LinkedHashSet<>();
    private final Inbox inbox = new Inbox();
    private final Pipeline pipeline = new Pipeline(inbox, new Planner(new Claims(), new Cooldowns()), new Scheduler(),
        this::planningProblem);
    private final Runners runners = new Runners(this::arrived);
    private final Urges urges = new Urges();
    private final Salvage salvage = new Salvage();
    private final Kits kits = new Kits();
    private final Survey survey = new Survey();
    private final Readout readout = new Readout();
    private final Stalls stalls = new Stalls();

    private long wakeAt = Long.MAX_VALUE;
    private long scheduleWakeAt = Long.MAX_VALUE;
    // What each stage last asked of the survey: the weave's whole ask, and whether the schedule wants hands read.
    private Asks weaveAsks = Asks.NONE;
    private boolean scheduleHands;
    private List<Shortfall> shortfalls = List.of();
    private List<Refused> refused = List.of();
    private boolean waysAnswered;
    private long waysAnsweredAt = Long.MIN_VALUE / 2;
    private long desertedSince = -1L;

    private boolean closed;
    private List<UUID> dropsToRead = List.of();

    // The plan as the colony keeps it, change by change, and the routes laid on it.
    private final WeaveView graph = new WeaveView();
    // Actual execution survives route changes while its physical output awaits the planner hand-off.
    private final Map<UUID, UUID> inputWorkers = new LinkedHashMap<>();
    private final Set<UUID> declined = new LinkedHashSet<>();
    private final Map<UUID, Tour> tours = new LinkedHashMap<>();
    private Schedule schedule = Schedule.EMPTY;
    private Diagnosis diagnosis = Diagnosis.NOTHING;
    private long submittedNodes;
    private long lastScheduleNanos;
    private long limitedSchedules;

    public ColonyLabor(ColonyData colony, ResourceKey<Level> dimension) {
        this.colony = colony;
        this.dimension = dimension;
        colony.works().listen(dimension, this);
    }

    private void planningProblem(String stage, String error) {
        LaborIncident.publish(colony.colonyId(), dimension, "planning/" + stage,
            net.minecraft.network.chat.Component.translatable("folkways.debug.incident.planning",
                dimension.location().toString() + "/" + stage, error));
    }

    @Override
    public void submitted(ResourceLocation owner, Grown work) {
        inbox.raise(new Message.Submitted(owner, work));
    }

    @Override
    public void withdrawn(ResourceLocation owner, UUID node) {
        inbox.raise(new Message.Withdrawn(owner, node));
    }

    @Override
    public void offered() {
        inbox.raise(Message.OFFERED);
    }

    @Override
    public void relocating() {
        close();
    }

    ColonyLabor current() {
        if (!closed) {
            return this;
        }
        ColonyLabor fresh = new ColonyLabor(colony, dimension);
        fresh.dropsToRead = salvage.drops();
        return fresh;
    }

    boolean desertedLongEnough(boolean deserted, long now) {
        if (!deserted) {
            desertedSince = -1L;
            return false;
        }
        if (desertedSince < 0L) {
            desertedSince = now;
            return false;
        }
        return now - desertedSince >= DESERTED_TICKS;
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        pipeline.close(lanes.weave(), lanes.schedule());
        colony.works().unlisten(dimension, this);
        runners.close(this);
        phases.retain(Set.of());
        inputWorkers.clear();
        personal.retain(Set.of());
        personalRequests.clear();
        endings.clear();
        withdrawnGone.clear();
        stalls.clear();
        colony.works().stalled(dimension, List.of());
    }

    public void run(ServerLevel level, Lanes lanes) {
        if (closed) {
            return;
        }
        this.lanes = lanes;
        for (UUID drop : dropsToRead) {
            if (level.getEntity(drop) instanceof ItemEntity item && item.isAlive()) {
                spilled(item);
            }
        }
        dropsToRead = List.of();
        pipeline.applyReady(level);
        settle(level);
        Set<UUID> roster = enrolled();
        runners.reconcile(this, roster);
        for (var entry : List.copyOf(personalRequests.entrySet())) {
            if (!roster.contains(entry.getValue().resident())) {
                personal.cancelled(entry.getKey());
                forgetPersonal(entry.getKey());
            }
        }
        Map<UUID, Body> present = new LinkedHashMap<>();
        for (Body body : colony.holdings().bodiesIn(level)) {
            if (!body.mob().isRemoved()) {
                present.put(body.id(), body);
            }
        }
        runners.away(this, present.keySet(), colony.holdings().carriedOff(), level.getGameTime());
        ColonyView view = present.isEmpty() ? null : ColonySnapshot.of(colony, level);
        for (Body body : present.values()) {
            BodyRunner runner = runners.of(body.id());
            interrupt(level, view, body, runner);
            runner.tick(this, level, body);
        }
        answer(level, present);
        for (int round = 0; round < MOST_ROUNDS && !pipeline.weaving() && inbox.any(); round++) {
            plan(level);
            answer(level, present);
        }
        if (level.getGameTime() >= scheduleWakeAt) {
            scheduleWakeAt = Long.MAX_VALUE;
            pipeline.wake(lanes.schedule(), level.getGameTime(), this::laid);
            pipeline.applyReady(level);
        }
        tellEndings();
        stalls.changed(level.getGameTime()).ifPresent(told -> colony.works().stalled(dimension, told));
    }

    private void ending(UUID node, Ending how) {
        if (!closed) {
            endings.add(new Ended(node, how));
        }
    }

    private void tellEndings() {
        while (!endings.isEmpty() || !withdrawnGone.isEmpty()) {
            List<Ended> ended = List.copyOf(endings);
            List<UUID> gone = List.copyOf(withdrawnGone);
            endings.clear();
            withdrawnGone.clear();
            for (Ended one : ended) {
                kits.ended(one.node());
                salvage.ended(one.node());
                colony.works().ended(dimension, one.node(), one.how());
            }
            for (UUID node : gone) {
                colony.works().withdrawnGone(dimension, node);
            }
        }
    }

    private void answer(ServerLevel level, Map<UUID, Body> present) {
        if (survey.answer(level, colony, present, body -> kits.read(colony.works(), body, inbox::raise))) {
            inbox.raise(Message.ANSWERED);
        }
        waysAnswered |= ColonyWays.answered(colony, level);
        if (waysAnswered && level.getGameTime() - waysAnsweredAt >= WAYS_ANSWER_TICKS) {
            waysAnswered = false;
            waysAnsweredAt = level.getGameTime();
            inbox.raise(Message.ANSWERED);
        }
        if (level.getGameTime() >= wakeAt) {
            wakeAt = Long.MAX_VALUE;
            inbox.raise(Message.ANSWERED);
        }
    }

    private void plan(ServerLevel level) {
        Ways ways = ColonyWays.of(colony, level);
        List<Workshop> workshops = new ArrayList<>(colony.works().workshopsIn(dimension));
        salvage.workshop().ifPresent(workshops::add);
        Known known = survey.known(ways, List.copyOf(workshops));
        readout.froze(known.hands().size(), inbox.size());
        pipeline.weave(lanes.weave(), lanes.schedule(), known, level.getGameTime(),
            (batch, woven) -> woven(level, batch, woven), this::laid);
        pipeline.applyReady(level);
    }

    private void settle(ServerLevel level) {
        for (UUID id : phases.settling()) {
            if (phases.settled(phases.node(id), level)) {
                finished(id);
            } else {
                phases.failure(id).ifPresent(why -> failed(id, why));
            }
        }
        for (UUID id : personal.settling()) {
            Node node = personal.node(id);
            boolean settled = personal.settled(node, level);
            if (settled) {
                readout.completed(node);
                Personal request = personalRequests.get(id);
                WorkProgress.reported(colony.colonyId(), node, WorkProgress.Step.FINISHED,
                    Optional.ofNullable(request).map(Personal::resident));
            }
            if (settled || personal.of(id).terminal()) {
                Personal request = personalRequests.get(id);
                if (request != null && request.urge() != null) {
                    personal.failure(id).ifPresent(why -> urgeRefused(request.urge(), why, level.getGameTime()));
                }
                forgetPersonal(id);
            }
        }
    }

    private void interrupt(ServerLevel level, ColonyView view, Body body, BodyRunner runner) {
        Worker who = hands(body, Placement.NOWHERE);
        List<Urge> offered = urges.offered(colony, view, body, who);
        Optional<Urge> current = runner.ownUrge().flatMap(id -> offered.stream()
            .filter(urge -> urge.id().equals(id)).findFirst());
        if (runner.ownBusiness() && current.isEmpty()) {
            runner.letGo(this, level, body, "urge-gone");
        }
        Optional<Urge> keenest = urges.keenest(offered, level.getGameTime(), urge ->
            !personalRequests.containsValue(new Personal(body.id(), urge.id()))
                && (!runner.executing() || urge.preempts()
                    && current.map(active -> urge.weight() > active.weight()).orElse(true)));
        if (keenest.isEmpty()) {
            return;
        }
        Node doing;
        try {
            doing = keenest.get().take().get();
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("urge {} threw asking for its work", keenest.get().id(), broken);
            urges.refused(keenest.get().id(), LaborRefusal.WORK_BROKE, level.getGameTime());
            return;
        }
        personal.entered(doing, level);
        try {
            doing.planned(level).ifPresent(why -> personal.failed(doing.id(), why));
        } catch (RuntimeException | LinkageError broken) {
            personal.failed(doing.id(), LaborRefusal.WORK_BROKE);
        }
        if (!personal.ready(doing, level)) {
            personal.failure(doing.id()).ifPresent(why -> urgeRefused(keenest.get().id(), why, level.getGameTime()));
            personal.cancelled(doing.id());
            personal.forget(doing.id());
            return;
        }
        runner.interruptedWork().ifPresent(interrupted -> LaborIncident.work(colony.colonyId(), level, body,
            interrupted, "urge-preempts/" + keenest.get().id(),
            net.minecraft.network.chat.Component.translatable("folkways.debug.incident.preempted",
                keenest.get().id().toString(), LaborIncident.job(doing), LaborIncident.target(level, doing),
                LaborIncident.surroundings(body))));
        runner.letGo(this, level, body, "urge-preempts");
        personalRequests.put(doing.id(), new Personal(body.id(), keenest.get().id()));
        runner.offer(this, level, body, keenest.get(), doing);
    }

    // What the weave changed, applied: work that went or was grown afresh is let go of and ends as the plan says,
    // and work new to the plan, or grown afresh, enters it.
    List<io.github.izakyl.folkways.core.api.work.ReservedInput> reservedInputs() {
        Map<UUID, UUID> assigned = new LinkedHashMap<>();
        for (Tour tour : schedule.tours().values()) {
            for (UUID node : tour.nodes()) assigned.put(node, tour.resident());
        }
        return InputReservations.read(graph, phases::of, node -> {
            UUID actual = inputWorkers.get(node);
            if (actual != null) return actual;
            Vertex vertex = graph.vertex(node);
            if (vertex != null && vertex.node().worker().isPresent()) return vertex.node().worker().get();
            UUID pinned = graph.pin(node);
            return pinned != null ? pinned : assigned.get(node);
        });
    }
    private void woven(ServerLevel level, List<Message> batch, Planner.Woven woven) {
        WeaveDelta delta = woven.delta();
        Set<UUID> withdrawn = new LinkedHashSet<>();
        for (Message message : batch) {
            if (message instanceof Message.Withdrawn(var owner, UUID node)) {
                withdrawn.add(node);
            }
        }
        Set<UUID> regrew = new LinkedHashSet<>();
        for (UUID regrown : delta.fresh()) {
            if (graph.has(regrown)) {
                regrew.add(regrown);
            }
        }
        Set<UUID> replaced = new LinkedHashSet<>(regrew);
        for (UUID gone : delta.removed()) {
            if (graph.has(gone)) {
                replaced.add(gone);
            }
        }
        runners.letGo(this, replaced);
        for (UUID regrown : regrew) {
            phases.cancelled(regrown);
            phases.forget(regrown);
            declined.remove(regrown);
            reportedFailures.remove(regrown);
        }
        if (!regrew.isEmpty()) {
            inbox.discard(message -> regrew.contains(about(message)));
        }
        List<Node> entered = new ArrayList<>();
        delta.put().forEach((id, vertex) -> {
            if (!graph.has(id) || delta.fresh().contains(id)) {
                entered.add(vertex.node());
            }
        });
        Set<UUID> heldBefore = graph.held();
        graph.apply(delta);
        inputWorkers.keySet().removeAll(replaced);
        inputWorkers.keySet().retainAll(graph.vertices().keySet());
        declined.removeAll(delta.removed());
        reportedFailures.removeAll(delta.removed());
        untaken.keySet().removeAll(delta.removed());
        // Work the plan gave up ends as the plan says; work it holds off the plan for now has not ended.
        Map<UUID, Ending> given = new LinkedHashMap<>();
        Set<UUID> worked = new LinkedHashSet<>();
        for (Gone gone : delta.gone()) {
            given.put(gone.node().id(), gone.how());
            if (phases.knows(gone.node().id())) {
                worked.add(gone.node().id());
            }
        }
        Set<UUID> offPlan = new LinkedHashSet<>(delta.removed());
        for (UUID was : heldBefore) {
            if (!graph.held().contains(was)) {
                offPlan.add(was);
            }
        }
        for (UUID id : offPlan) {
            if (!graph.has(id) && !graph.held().contains(id) && phases.knows(id)) {
                phases.cancelled(id, withdrawn.contains(id) ? Ending.REVOKED : given.getOrDefault(id, Ending.DROPPED));
                phases.forget(id);
            }
        }
        for (UUID node : withdrawn) {
            if (!graph.has(node)) {
                withdrawnGone.add(node);
            }
        }
        weaveAsks = woven.asks();
        shortfalls = woven.shortfalls();
        refused = woven.refused();
        stalls.planned(woven.stalls(), level.getGameTime());
        diagnosis = new Diagnosis(shortfalls, diagnosis.unassigned(), refused);
        survey.asked(new Asks(weaveAsks.survey(), weaveAsks.looks(), weaveAsks.hands() || scheduleHands,
            weaveAsks.handsOf(), weaveAsks.wakeAt()));
        wakeAt = weaveAsks.wakeAt();
        for (Gone gone : delta.gone()) {
            if (worked.contains(gone.node().id())) {
                continue;
            }
            try {
                gone.node().ended(level, gone.how());
            } catch (RuntimeException | LinkageError broken) {
                Labor.LOGGER.error("node {} threw ending as {}", gone.node().id(), gone.how(), broken);
            }
            ending(gone.node().id(), gone.how());
        }
        for (Node node : entered) {
            submittedNodes++;
            phases.entered(node, level);
            try {
                node.planned(level).ifPresent(why -> decline(node.id(), why));
            } catch (RuntimeException | LinkageError broken) {
                Labor.LOGGER.error("node {} threw accepting its plan", node.id(), broken);
                decline(node.id(), LaborRefusal.WORK_BROKE);
            }
        }
    }

    // What the schedule laid, applied: every route is followed afresh from now on, so work a runner let go of
    // is taken up again whether or not its route changed.
    private void laid(Scheduler.Scheduled laid) {
        lastScheduleNanos = laid.timing().scheduleNanos();
        if (laid.timing().limited()) {
            limitedSchedules++;
            LaborIncident.publish(colony.colonyId(), dimension, "fallback",
                net.minecraft.network.chat.Component.translatable("folkways.debug.incident.fallback",
                    dimension.location().toString(), laid.timing().scheduleNanos() / 1_000_000, limitedSchedules));
        }
        laid.changed().forEach((resident, tour) -> {
            if (tour.nodes().isEmpty()) {
                tours.remove(resident);
            } else {
                tours.put(resident, tour);
            }
        });
        runners.follow(tours);
        schedule = new Schedule(tours, laid.unassigned());
        diagnosis = new Diagnosis(shortfalls, laid.unassigned(), refused);
        if (laid.hands() != scheduleHands) {
            scheduleHands = laid.hands();
            survey.asked(new Asks(weaveAsks.survey(), Set.of(), weaveAsks.hands() || scheduleHands, Set.of(),
                weaveAsks.wakeAt()));
        }
        scheduleWakeAt = laid.wakeAt();
    }

    private static UUID about(Message message) {
        return switch (message) {
            case Message.Started(UUID node, var resident) -> node;
            case Message.Finished(UUID node) -> node;
            case Message.Failed(UUID node, var why) -> node;
            case Message.Released(UUID node) -> node;
            default -> null;
        };
    }

    Colony colony() {
        return colony.works();
    }

    Phases phases() {
        return phases;
    }

    Phases personal() { return personal; }

    void forgetPersonal(UUID node) {
        personal.forget(node);
        personalRequests.remove(node);
    }

    public Optional<NodeState> state(UUID node) {
        return graph.has(node) ? Optional.of(phases.of(node)) : Optional.empty();
    }

    private void decline(UUID id, RefusalKind why) {
        declined.add(id);
        failed(id, why);
    }

    Optional<Node> node(UUID id) {
        return Optional.ofNullable(graph.vertex(id)).map(Vertex::node);
    }

    Optional<Node> takeable(UUID id, ServerLevel level) {
        if (declined.contains(id)) {
            untaken.put(id, new Untaken("declined", null));
            return Optional.empty();
        }
        for (UUID predecessor : graph.before(id)) {
            if (phases.of(predecessor) != NodeState.DONE) {
                untaken.put(id, new Untaken("after", predecessor));
                return Optional.empty();
            }
        }
        Vertex vertex = graph.vertex(id);
        if (vertex == null) {
            untaken.put(id, new Untaken("off_graph", null));
            return Optional.empty();
        }
        if (!phases.ready(vertex.node(), level)) {
            untaken.put(id, new Untaken(phases.waiting(id) ? "waiting"
                : phases.of(id).terminal() ? "terminal:" + phases.of(id) : "not_ready", null));
            phases.failure(id).ifPresent(why -> failed(id, why));
            return Optional.empty();
        }
        untaken.remove(id);
        return Optional.of(vertex.node());
    }

    // Why the last try at taking a node turned it down, kept for the stall readout.
    record Untaken(String why, UUID after) { }

    private final Map<UUID, Untaken> untaken = new java.util.HashMap<>();

    // Ready, but taking it ahead of the work still waiting before it would overfill the pack.
    void packBlocked(UUID id) {
        untaken.put(id, new Untaken("pack_order", null));
    }

    // One line on why a stalled body's queued node cannot be taken: the check that turned it down and, for an
    // order, where the work it waits on stands and who holds it.
    String untakenWhy(UUID id) {
        Untaken why = untaken.get(id);
        if (why == null) {
            return "why=unknown kind=" + kindOf(id);
        }
        StringBuilder said = new StringBuilder("why=").append(why.why()).append(" kind=").append(kindOf(id));
        said.append(' ').append(detailOf(id));
        if (why.after() != null) {
            UUID before = why.after();
            said.append(" after=").append(before.toString(), 0, 8)
                .append(' ').append(phases.of(before))
                .append(" kind=").append(kindOf(before))
                .append(" held=").append(holderOf(before))
                .append(' ').append(detailOf(before));
        }
        return said.toString();
    }

    String untakenKey(UUID id) {
        Untaken why = untaken.get(id);
        if (why == null) {
            return "unknown/" + kindOf(id);
        }
        String key = why.why() + "/" + kindOf(id);
        if (why.after() != null) {
            key += "<-" + phases.of(why.after()) + "/" + kindOf(why.after()) + "/" + holderKind(why.after());
        }
        return key;
    }

    // What the node does and where, which group it is in and who it is bound to, as the last plan said.
    private String detailOf(UUID id) {
        Vertex vertex = graph.vertex(id);
        if (vertex == null) {
            return "[?]";
        }
        Node node = vertex.node();
        StringBuilder said = new StringBuilder("[").append(node.spec().doing().getPath())
            .append('@').append(node.spec().site().where().cell().toShortString())
            .append(" goods=").append(node.spec().about().orElse("-"));
        said.append(" group=").append(graph.group(id).size());
        UUID pin = graph.pin(id);
        said.append(" pin=").append(pin == null ? "-" : pin.toString().substring(0, 8));
        node.worker().ifPresent(worker -> said.append(" worker=").append(worker.toString(), 0, 8));
        said.append(" in=").append(graph.before(id).size());
        return said.append(']').toString();
    }


    private String kindOf(UUID id) {
        Vertex vertex = graph.vertex(id);
        if (vertex == null) {
            return "?";
        }
        Node node = vertex.node();
        return node.spec().owner().getPath() + "/" + node.getClass().getSimpleName();
    }

    private String holderOf(UUID node) {
        for (BodyRunner runner : runners.all()) {
            if (runner.holding().filter(node::equals).isPresent()) {
                return runner.resident().toString().substring(0, 8) + ":hand:" + runner.standing();
            }
            int at = runner.queued().indexOf(node);
            if (at >= 0) {
                return runner.resident().toString().substring(0, 8) + ":queue#" + at + ":" + runner.standing()
                    + (runner.stalledTicks() > 0 ? "(" + runner.stalledTicks() + "t)" : "");
            }
        }
        return "nobody";
    }

    private String holderKind(UUID node) {
        for (BodyRunner runner : runners.all()) {
            if (runner.holding().filter(node::equals).isPresent()) {
                return "hand:" + runner.standing();
            }
            int at = runner.queued().indexOf(node);
            if (at >= 0) {
                return (at == 0 ? "queue-head:" : "queue-later:") + runner.standing();
            }
        }
        return "nobody";
    }

    // Whether doing `next` first, then the rest of the route in its order, keeps the body's pack within what it
    // holds at every step, counted as the schedule counts it.
    boolean fitsAhead(Body body, UUID next, Collection<UUID> route) {
        List<List<Amount>> order = new ArrayList<>();
        order.add(placementOf(next).carrying());
        for (UUID queued : route) {
            if (!queued.equals(next) && onTheGraph(queued)) {
                order.add(placementOf(queued).carrying());
            }
        }
        Kit kit = Kit.of(colony.works(), body);
        int capacity = body.pack().plannableCells(item -> kit.holds(item.getDefaultInstance())) - kit.keptCells(body);
        int used = capacity - body.pack().plannableRoom(item -> kit.holds(item.getDefaultInstance()));
        return PackLoad.fits(used, capacity, order);
    }

    // Two pending deliveries may share a destination even when their source stores differ. Only
    // explicit graph dependencies identify their cargo: matching item names alone is ambiguous.
    boolean sharesDelivery(UUID previous, UUID candidate, Collection<UUID> route, Body body) {
        WorldPos destination = null;
        WorldPos nextDestination = null;
        for (UUID id : route) {
            Vertex vertex = graph.vertex(id);
            if (vertex == null || !(vertex.node() instanceof io.github.izakyl.folkways.core.engine.plan.haul.TransferNode transfer)
                    || transfer.fetching() || !onTheGraph(id)) { continue; }
            if (graph.before(id).contains(previous)) { destination = transfer.spec().site().where(); }
            if (graph.before(id).contains(candidate)) { nextDestination = transfer.spec().site().where(); }
        }
        Vertex take = graph.vertex(candidate);
        if (destination == null || !destination.equals(nextDestination) || take == null) { return false; }
        WorldPos here = io.github.izakyl.folkways.core.api.terms.WorldSpaces.at(body.mob());
        WorldPos source = take.node().spec().site().where();
        if (!here.sameRealm(source) || !here.sameRealm(destination)) { return false; }
        double detour = Math.sqrt(here.cell().distSqr(source.cell()))
            + Math.sqrt(source.cell().distSqr(destination.cell()))
            - Math.sqrt(here.cell().distSqr(destination.cell()));
        return detour <= 8;
    }

    boolean onTheGraph(UUID id) {
        return graph.has(id) && !phases.of(id).terminal();
    }

    Placement placementOf(UUID id) {
        Vertex vertex = graph.vertex(id);
        return vertex == null ? Placement.NOWHERE : vertex.plan();
    }

    Hands hands(Body body, Placement placement) {
        return new Hands(body, survey.stands(), placement, this::spilled, () -> routeOf(body.id()));
    }

    private List<Node> routeOf(UUID resident) {
        BodyRunner runner = runners.of(resident);
        List<Node> ahead = new ArrayList<>();
        runner.inHand().ifPresent(ahead::add);
        for (UUID queued : runner.queued()) {
            Vertex vertex = graph.vertex(queued);
            if (vertex != null) {
                ahead.add(vertex.node());
            }
        }
        return List.copyOf(ahead);
    }

    Optional<Ways> ways(ServerLevel level) {
        return Optional.of(ColonyWays.of(colony, level));
    }

    private void spilled(ItemEntity drop) {
        salvage.dropped(drop).ifPresent(message -> {
            inbox.raise(message);
            inbox.raise(Message.OFFERED);
        });
    }

    void started(UUID node, UUID resident) {
        if (graph.has(node)) inputWorkers.put(node, resident);
        progress(node, WorkProgress.Step.STARTED, Optional.of(resident));
        inbox.raise(new Message.Started(node, resident));
    }

    void finished(UUID node) {
        Vertex finished = graph.vertex(node);
        if (finished != null) {
            stalls.done(finished.node().spec());
        }
        readout.finished(finished == null ? null : finished.node());
        progress(node, WorkProgress.Step.FINISHED, Optional.empty());
        kits.ended(node);
        salvage.ended(node);
        inbox.raise(new Message.Finished(node));
    }

    // Fails the node outright: its owner has had its say by now, or never gets one.
    void failed(UUID node, RefusalKind why) {
        phases.fail(node, why);
        if (!reportedFailures.add(node)) {
            return;
        }
        readout.refused(why);
        progress(node, WorkProgress.Step.FAILED, Optional.empty());
        inbox.raise(new Message.Failed(node, why));
        if (salvage.ended(node)) {
            inbox.raise(new Message.Withdrawn(Salvage.DOMAIN, node));
        }
    }

    void released(UUID node) {
        readout.released();
        progress(node, WorkProgress.Step.RELEASED, Optional.empty());
        inbox.raise(new Message.Released(node));
    }

    private void progress(UUID node, WorkProgress.Step step, Optional<UUID> worker) {
        Vertex vertex = graph.vertex(node);
        if (vertex != null) {
            WorkProgress.reported(colony.colonyId(), vertex.node(), step, worker);
        }
    }

    void arrived(UUID resident) {
        survey.read(resident);
    }

    void gone(UUID resident) {
        survey.forget(resident);
        kits.left(resident, inbox::raise);
        inbox.raise(new Message.Left(resident));
    }

    void idle(UUID resident) {
        survey.read(resident);
        inbox.raise(new Message.Idle(resident));
    }

    // A resident who finished work with nothing to put away: the plan reads their pack again all the same, since
    // what the work left there, such as goods they keep, is not cargo and would otherwise go unseen.
    void reread(UUID resident) {
        survey.read(resident);
    }

    // A piece handed out within work a body is in: run as the body's own, beside the plan, and told of as any work
    // is. Answers whether it can be begun.
    boolean inner(Node piece, UUID resident, ServerLevel level) {
        personal.entered(piece, level);
        if (!personal.ready(piece, level)) {
            personal.cancelled(piece.id());
            personal.forget(piece.id());
            return false;
        }
        personalRequests.put(piece.id(), new Personal(resident, null));
        return true;
    }

    // A resident met a refusal at the colony's work, whether the work fails for it or waits to be tried again.
    void balked(Node node, RefusalKind why, long now) {
        stalls.met(node.spec(), why, now);
    }

    void urgeRefused(ResourceLocation urge, RefusalKind why, long now) {
        readout.refused(why);
        urges.refused(urge, why, now);
    }

    void broke(UUID resident, String item) {
        readout.broke(item);
        survey.read(resident);
    }

    LaborCensus census(ServerLevel level) {
        Map<NodeState, Integer> counted = new java.util.EnumMap<>(NodeState.class);
        for (NodeState phase : NodeState.values()) {
            counted.put(phase, phases.in(phase).size());
        }
        int planned = 0;
        for (Tour tour : schedule.tours().values()) {
            planned += tour.nodes().size();
        }
        Map<String, Integer> unassigned = new java.util.TreeMap<>();
        for (Unassigned nobody : diagnosis.unassigned()) {
            unassigned.merge(nobody.why().name(), 1, Integer::sum);
        }
        Map<String, Long> missing = new java.util.TreeMap<>();
        for (Shortfall each : diagnosis.shortfalls()) {
            missing.merge(Doings.item(each.what()).map(ResourceLocation::toString).orElse(each.what().describe()),
                each.missing(), Long::sum);
        }
        Set<UUID> carriedOff = colony.holdings().carriedOff();
        Map<String, Integer> standing = new java.util.TreeMap<>();
        for (BodyRunner runner : runners.all()) {
            String said;
            if (carriedOff.contains(runner.resident())
                    || level.getEntity(runner.resident()) instanceof net.minecraft.world.entity.Entity body
                    && body.getVehicle() != null) {
                said = "riding";
            } else {
                said = runner.census();
            }
            standing.merge(said, 1, Integer::sum);
        }
        return new LaborCensus(submittedNodes, counted, planned, unassigned, missing, standing);
    }

    String heartbeat() {
        return readout.line(graph.vertices(), phases, schedule, diagnosis, runners,
            pipeline.busyFor(Util.getNanos())) + " limited=" + limitedSchedules;
    }

    LaborSnapshot snapshot(ServerLevel level) {
        Map<String, Integer> shortages = new java.util.TreeMap<>();
        for (Shortfall shortfall : diagnosis.shortfalls()) {
            shortages.merge(Readout.name(shortfall.why()), 1, Integer::sum);
        }
        List<String> examples = new ArrayList<>();
        for (Unassigned unassigned : diagnosis.unassigned()) {
            Vertex vertex = graph.vertex(unassigned.node());
            if (vertex != null) {
                examples.add(unassigned.why() + "/" + vertex.node().spec().owner() + "/"
                    + vertex.node().getClass().getSimpleName() + "@" + vertex.node().spec().site().where());
                if (examples.size() == 3) break;
            }
        }
        return new LaborSnapshot(census(level), pipeline.busyFor(Util.getNanos()) / 1_000_000,
            lastScheduleNanos / 1_000_000, limitedSchedules, shortages, examples);
    }

    long solvingFor(long now) {
        return pipeline.busyFor(now);
    }

    Diagnosis diagnosis() {
        return diagnosis;
    }

    Collection<String> stalls() {
        untaken.keySet().retainAll(graph.vertices().keySet());
        List<String> lines = new ArrayList<>(readout.stalls(graph.vertices(), phases, runners, this::untakenWhy, this::untakenKey));
        lines.addAll(readout.unassigned(graph, diagnosis, survey.hands()));
        // The whole route of the first few bodies whose queued work stalled bodies wait on.
        Set<UUID> holders = new LinkedHashSet<>();
        for (BodyRunner runner : runners.all()) {
            if (runner.stalledTicks() <= 0 || runner.queued().isEmpty()) {
                continue;
            }
            Untaken why = untaken.get(runner.queued().get(0));
            if (why == null || why.after() == null) {
                continue;
            }
            for (BodyRunner other : runners.all()) {
                if (other.queued().contains(why.after())) {
                    holders.add(other.resident());
                }
            }
            if (holders.size() >= 4) {
                break;
            }
        }
        for (UUID holder : holders) {
            BodyRunner runner = runners.of(holder);
            StringBuilder route = new StringBuilder("route body=").append(holder.toString(), 0, 8)
                .append(' ').append(runner.standing())
                .append(runner.inHand().map(node -> " hand=" + detailOf(node.id())).orElse(""))
                .append(" queued=").append(runner.queued().size()).append(':');
            int at = 0;
            for (UUID queued : runner.queued()) {
                route.append("\n      #").append(at++).append(' ').append(queued.toString(), 0, 8)
                    .append(' ').append(phases.of(queued)).append(' ').append(kindOf(queued))
                    .append(' ').append(detailOf(queued));
            }
            lines.add(route.toString());
        }
        return lines;
    }

    private Set<UUID> enrolled() {
        return colony.holdings().entities();
    }
}
