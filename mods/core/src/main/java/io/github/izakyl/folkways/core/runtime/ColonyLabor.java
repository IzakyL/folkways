package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.WorkListener;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.colony.internal.ColonySnapshot;
import io.github.izakyl.folkways.core.colony.internal.ColonyWorks;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.plan.internal.Asks;
import io.github.izakyl.folkways.core.plan.internal.Claim;
import io.github.izakyl.folkways.core.plan.internal.Claims;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.Diagnosis;
import io.github.izakyl.folkways.core.plan.internal.Gone;
import io.github.izakyl.folkways.core.plan.internal.Known;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.plan.internal.Planner;
import io.github.izakyl.folkways.core.plan.internal.Refused;
import io.github.izakyl.folkways.core.plan.internal.Schedule;
import io.github.izakyl.folkways.core.plan.internal.Scheduler;
import io.github.izakyl.folkways.core.plan.internal.Shortfall;
import io.github.izakyl.folkways.core.plan.internal.Tour;
import io.github.izakyl.folkways.core.plan.internal.Unassigned;
import io.github.izakyl.folkways.core.plan.internal.Vertex;
import io.github.izakyl.folkways.core.plan.internal.WeaveDelta;
import io.github.izakyl.folkways.core.plan.internal.WeaveView;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.BodyActions;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkProgress;
import io.github.izakyl.folkways.core.work.api.WorkBatch;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.work.internal.WorkExecution;
import io.github.izakyl.folkways.core.work.internal.WorkExecution.Event;
import io.github.izakyl.folkways.core.work.internal.HoldingActions;
import io.github.izakyl.folkways.core.work.internal.Hands;
import io.github.izakyl.folkways.core.work.internal.HoldingRegistry;
import io.github.izakyl.folkways.core.work.internal.Kit;
import io.github.izakyl.folkways.core.work.internal.LaborIncident;
import io.github.izakyl.folkways.core.work.internal.Phases;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.api.notice.DebugText;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.plan.internal.Resource;
import io.github.izakyl.folkways.core.combat.api.DefenseControl;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands;
import io.github.izakyl.folkways.core.rts.combat.control.CombatSelectionOwnership;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.Util;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;

public final class ColonyLabor implements WorkListener {

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
    private final HoldingRegistry holdings = new HoldingRegistry();
    private final WorkExecution execution;
    private final Runners runners;
    private final Urges urges = new Urges();
    private final Map<UUID, List<Urge>> offeredUrges = new LinkedHashMap<>();
    final DebugHistory history = new DebugHistory();
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

    private final ColonyWorks works;

    private boolean closed;
    private List<UUID> dropsToRead = List.of();

    // The plan as the colony keeps it, change by change, and the routes laid on it.
    private final WeaveView graph = new WeaveView();
    // Actual execution survives route changes while its physical output awaits the planner hand-off.
    private final Map<UUID, UUID> inputWorkers = new LinkedHashMap<>();
    private Map<UUID, List<Claim>> plannedClaims = Map.of();
    private final Set<UUID> declined = new LinkedHashSet<>();
    private final Map<UUID, Optional<EquipmentProfile>> equipmentRead = new LinkedHashMap<>();
    private final Map<UUID, Tour> tours = new LinkedHashMap<>();
    private Schedule schedule = Schedule.EMPTY;
    private Diagnosis diagnosis = Diagnosis.NOTHING;
    private long submittedNodes;
    private long lastScheduleNanos;
    private long limitedSchedules;

    public ColonyLabor(ColonyData colony, ResourceKey<Level> dimension) {
        this.colony = colony;
        this.works = colony.works();
        this.dimension = dimension;
        execution = new WorkExecution(works, phases, personal, holdings, this::hands,
            new HoldingActions(level -> Optional.of(ColonyWays.of(colony, level)), (resident, item) -> {
                readout.broke(item);
                survey.read(resident);
            }), this::executionChanged);
        runners = new Runners(execution, survey::read);
        UUID colonyId = colony.colonyId();
        // Query the active runtime site even while a listener is being replaced or closed.
        works.reservations(at -> Labor.reservedInputs(colonyId, at));
        works.listen(dimension, this);
    }

    private void planningProblem(String stage, String error) {
        LaborIncident.publish(colony.colonyId(), dimension, "planning/" + stage,
            () -> net.minecraft.network.chat.Component.translatable("folkways.debug.incident.planning",
                dimension.location().toString() + "/" + stage, error));
    }

    @Override
    public Optional<UUID> holder(UUID node) {
        return holdings.byNode(node).map(HoldingRegistry.Held::resident);
    }

    @Override
    public boolean isApproaching(UUID node) {
        return holder(node).map(resident -> runners.of(resident).approaching(node)).orElse(false);
    }

    @Override
    public boolean isSuspended(UUID node) {
        return holdings.suspended(node).isPresent();
    }

    @Override
    public void controllerOrder(ServerLevel level, Body body) {
        runners.of(body.id()).controllerTakeover(level, body, true);
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

    public ColonyLabor current() {
        if (!closed) {
            return this;
        }
        ColonyLabor fresh = new ColonyLabor(colony, dimension);
        fresh.dropsToRead = salvage.drops();
        return fresh;
    }

    public boolean desertedLongEnough(boolean deserted, long now) {
        if (!deserted || holdings.hasSuspended()) {
            desertedSince = -1L;
            return false;
        }
        if (desertedSince < 0L) {
            desertedSince = now;
            return false;
        }
        return now - desertedSince >= DESERTED_TICKS;
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pipeline.close(lanes.weave(), lanes.schedule());
        works.unlisten(dimension, this);
        runners.close();
        phases.retain(Set.of());
        inputWorkers.clear();
        plannedClaims = Map.of();
        personal.retain(Set.of());
        personalRequests.clear();
        endings.clear();
        withdrawnGone.clear();
        stalls.clear();
        offeredUrges.clear();
        history.clear();
        works.stalled(dimension, List.of());
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
        for (Body body : colony.claims().bodiesIn(level)) {
            if (!body.mob().isRemoved()) {
                present.put(body.id(), body);
            }
        }
        runners.checkAbsent(level, present.keySet());
        runners.away(present.keySet(), colony.claims().carriedOff(), level.getGameTime());
        ColonyView view = present.isEmpty() ? null : ColonySnapshot.of(colony, level);
        Set<UUID> reserved = new LinkedHashSet<>();
        var equipment = works.service(EquipmentProfiles.ID, EquipmentProfiles.class);
        for (Body body : present.values()) {
            Optional<EquipmentProfile> profile = equipment.flatMap(profiles -> profiles.assigned(body.id()));
            Optional<EquipmentProfile> previous = equipmentRead.put(body.id(), profile);
            if (previous != null && !previous.equals(profile)) {
                // A reduced personal allowance can turn an idle resident's protected stock into cargo.
                // Re-read the hand and ask the ordinary hauling planner to return that surplus.
                survey.read(body.id());
                inbox.raise(new Message.Idle(body.id()));
            }
            BodyRunner runner = runners.of(body.id());
            runner.check(level, body);
            boolean controlled = interrupt(level, view, body, runner);
            if (controlled) {
                reserved.add(body.id());
            }
            // Personal needs and commands still execute; an idle reserved body cannot claim a
            // planned node.
            if (!controlled || runner.executing()) {
                tickChecked(runner, level, body);
            }
        }
        survey.reserved(reserved);
        answer(level, present);
        for (int round = 0; round < MOST_ROUNDS && !pipeline.weaving() && inbox.any(); round++) {
            if (survey.waitingForInventory()) {
                break;
            }
            plan(level);
            answer(level, present);
        }
        if (level.getGameTime() >= scheduleWakeAt) {
            scheduleWakeAt = Long.MAX_VALUE;
            pipeline.wake(lanes.schedule(), level.getGameTime(), this::laid);
            pipeline.applyReady(level);
        }
        tellEndings();
        stalls.changed(level.getGameTime()).ifPresent(told -> works.stalled(dimension, told));
    }

    public void tick(BodyRunner runner, ServerLevel level, Body body) {
        runner.check(level, body);
        tickChecked(runner, level, body);
    }

    public void tickChecked(BodyRunner runner, ServerLevel level, Body body) {
        // check() has already run revocation, cancellation and soft deadlines; a gate only pauses execution.
        if (!BodyActions.allowed(body)) {
            body.mob().getNavigation().stop();
            return;
        }
        if (runner.tickChecked(level, body)) {
            take(runner, level, body);
        }
        history.observe(body);
    }

    private void take(BodyRunner runner, ServerLevel level, Body body) {
        gather(runner, level, body);
        Deque<UUID> route = runner.route;
        Iterator<UUID> queued = route.iterator();
        boolean owed = false;
        while (queued.hasNext()) {
            UUID next = queued.next();
            // Readiness may enter an owner's execution queue (shared kitchen tools, for example).
            // Check pack safety first, so work we cannot start never reserves ahead of runnable work.
            if (owed && onTheGraph(next) && !fitsAhead(body, next, route)) {
                packBlocked(next);
                continue;
            }
            Optional<Node> ready = takeable(next, level);
            if (ready.isPresent()) {
                queued.remove();
                runner.take(level, body, ready.get(), placementOf(next));
                return;
            }
            if (onTheGraph(next)) {
                owed = true;
                continue;
            }
            runner.skipped();
            queued.remove();
        }
        if (owed) {
            runner.waiting();
            return;
        }
        if (runner.rested()) {
            survey.read(runner.resident());
            if (!Kit.of(works, body).cargoIn(body).isEmpty()) {
                inbox.raise(new Message.Idle(runner.resident()));
            }
        }
    }

    // Gather compatible loads sharing a source or nearby destination. Every moved step is ready and its
    // inventory effect must fit the entire remaining route; reservations still commit separately.
    void gather(BodyRunner runner, ServerLevel level, Body body) {
        WorkBatch batch = runner.batch(body);
        if (batch == null) {
            return;
        }
        Node justDone = runner.lastDone();
        Deque<UUID> route = runner.route;
        int checked = 0;
        for (UUID id : List.copyOf(route)) {
            if (++checked > 32) {
                break;
            }
            // Inspect batch hints before readiness, which may reserve resources for unrelated work.
            Node next = node(id).orElse(null);
            if (next == null || next.batch(body).filter(batch::accepts).isEmpty()
                    || !(next.joins(justDone) || sharesDelivery(justDone.id(), id, route, body))
                    || !fitsAhead(body, id, route)) {
                continue;
            }
            if (takeable(id, level).isPresent()) {
                route.remove(id);
                route.addFirst(id);
                return;
            }
        }
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
                works.ended(dimension, one.node(), one.how());
            }
            for (UUID node : gone) {
                works.withdrawnGone(dimension, node);
            }
        }
    }

    private void answer(ServerLevel level, Map<UUID, Body> present) {
        if (survey.answer(
            level, colony, present, (body, supply) -> kits.read(works, body, supply, inbox::raise))) {
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
        List<Workshop> workshops = new ArrayList<>(works.workshopsIn(dimension));
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
                if (request != null) {
                    personal.failure(id).ifPresent(why ->
                        urgeRefused(request.resident(), request.urge(), why, level.getGameTime()));
                }
                forgetPersonal(id);
            }
        }
    }

    private boolean interrupt(ServerLevel level, ColonyView view, Body body, BodyRunner runner) {
        Worker who = hands(body, Placement.NOWHERE);
        history.observe(body);
        List<Urge> offered = urges.offered(colony, view, body, who);
        offeredUrges.put(body.id(), offered);
        runner.cancelMissingUrges(level, body, offered);
        boolean reserved = offered.stream().anyMatch(Urge::reservation);
        if (reserved) {
            runner.controllerTakeover(level, body, false);
        }
        Optional<Urge> current = runner.ownUrge().flatMap(id -> offered.stream()
            .filter(urge -> urge.id().equals(id)).findFirst());
        if (runner.ownBusiness() && current.isEmpty()) {
            runner.letGo(level, body, "urge-gone");
        }
        Optional<Urge> pending = runner.pendingUrge();
        if (pending.isPresent() && !offered.stream().map(Urge::id).toList().contains(pending.get().id())) {
            runner.discardPending();
            pending = Optional.empty();
        }
        Optional<Urge> waiting = pending;
        Optional<Urge> keenest =
            urges.keenest(
            body.id(),
            offered,
            level.getGameTime(),
            urge ->
                !personalRequests.containsValue(new Personal(body.id(), urge.id()))
                    && (!runner.preempting() || urge.urgent() && waiting.map(urge::outranks).orElse(true))
                    && (runner.executing()
                    ? urge.preempts() && current.map(urge::outranks).orElse(true)
                    : runner.preemptsSuspended(urge, offered)));
        if (keenest.isEmpty()) {
            if (!runner.preempting()) {
                runner.resume(level, body);
            }
            return reserved;
        }
        Node doing;
        try {
            doing = keenest.get().take().get();
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("urge {} threw asking for its work", keenest.get().id(), broken);
            urges.refused(body.id(), keenest.get().id(), LaborRefusal.WORK_BROKE, level.getGameTime());
            return reserved;
        }
        personal.entered(doing, level);
        try {
            doing.planned(level).ifPresent(why -> personal.failed(doing.id(), why));
        } catch (RuntimeException | LinkageError broken) {
            personal.failed(doing.id(), LaborRefusal.WORK_BROKE);
        }
        if (!personal.ready(doing, level)) {
            personal.failure(doing.id()).ifPresent(why ->
                urgeRefused(body.id(), keenest.get().id(), why, level.getGameTime()));
            personal.cancelled(doing.id());
            personal.forget(doing.id());
            return reserved;
        }
        runner.interruptedWork().ifPresent(interrupted -> LaborIncident.work(colony.colonyId(), level, body,
            interrupted, "urge-preempts/" + keenest.get().id(),
            () -> net.minecraft.network.chat.Component.translatable("folkways.debug.incident.preempted",
                keenest.get().id().toString(), LaborIncident.job(doing), LaborIncident.target(level, doing),
                LaborIncident.surroundings(body))));
        runner.discardPending();
        personalRequests.put(doing.id(), new Personal(body.id(), keenest.get().id()));
        runner.takeover(level, body, keenest.get(), doing);
        return reserved;
    }

    public List<io.github.izakyl.folkways.core.work.api.ReservedInput> reservedInputs() {
        Map<UUID, UUID> assigned = new LinkedHashMap<>();
        for (Tour tour : schedule.tours().values()) {
            for (UUID node : tour.nodes()) {
                assigned.put(node, tour.resident());
            }
        }
        return InputReservations.read(graph, phases::of, node -> {
            UUID actual = inputWorkers.get(node);
            if (actual != null) {
                return actual;
            }
            Vertex vertex = graph.vertex(node);
            if (vertex != null && vertex.node().worker().isPresent()) {
                return vertex.node().worker().get();
            }
            UUID pinned = graph.pin(node);
            return pinned != null ? pinned : assigned.get(node);
        });
    }

    // Work that went or was grown afresh ends as the plan says; new work enters it.
    private void woven(ServerLevel level, List<Message> batch, Planner.Woven woven) {
        WeaveDelta delta = woven.delta();
        plannedClaims = woven.claims();
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
        runners.letGo(replaced);
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
                () -> net.minecraft.network.chat.Component.translatable("folkways.debug.incident.fallback",
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
        for (UUID resident : laid.stow()) {
            inbox.raise(new Message.PackBusy(resident));
        }
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

    public ResidentDebug debugResident(ServerLevel level, UUID resident) {
        List<Line> lines = new ArrayList<>();
        requireDebugThread(level);
        BodyRunner runner = runners.all().stream().filter(one -> one.resident().equals(resident))
            .findFirst().orElse(null);
        Body body = colony.claims().bodiesIn(level).stream().filter(one -> one.id().equals(resident))
            .findFirst().orElse(null);
        List<DebugWork> route = debugRoute(runner);
        section(lines, "standing");
        Component state = word(runner == null ? "idle" : runner.standing());
        // The census usually repeats the standing; name it only when it says something else.
        MutableComponent standing = runner == null || runner.census().equals(runner.standing())
            ? Component.empty().append(state) : text("standing", state, runner.census());
        if (body != null && body.mob().isPassenger()) {
            standing.append(" · ").append(DebugText.tone(text("standing.riding"), DebugText.WAIT));
        }
        if (colony.claims().carriedOff().contains(resident)) {
            standing.append(" · ").append(DebugText.tone(text("standing.carried_off"), DebugText.BAD));
        }
        if (runner != null) {
            if (runner.stalledTicks() > 0) {
                standing.append(" · ").append(DebugText.tone(text("standing.stalled", runner.stalledTicks()),
                    DebugText.WAIT));
            }
            if (runner.busyTicks() > 0) {
                standing.append(" · ").append(text("standing.busy", runner.busyTicks()));
            }
            if (runner.skippedCount() > 0) {
                standing.append(" · ").append(text("standing.skipped", runner.skippedCount()));
            }
        }
        lines.add(Line.text(standing));
        if (runner != null && (runner.executing() || runner.pendingUrge().isPresent()
                || !runner.suspendedNodes().isEmpty())) {
            section(lines, "current");
            runner.interruptedWork().ifPresent(node -> lines.add(workLine(level,
                debugWork(node, runner.currentPlacement(), Optional.of(resident), 1))));
            List<Component> execution = new ArrayList<>();
            runner.executionStage().ifPresent(stage -> execution.add(text("execution", word(stage))));
            runner.ownUrge().ifPresent(urge -> execution.add(text("own_urge", DebugText.work(
                Component.literal(urge.toString())))));
            runner.pendingUrge().ifPresent(urge -> execution.add(text("pending_urge", DebugText.work(
                Component.literal(urge.id().toString())))));
            if (!execution.isEmpty()) {
                lines.add(Line.text(join(execution)));
            }
            runner.softPreempt().ifPresent(soft -> lines.add(debugLine("preempt", word(soft.reason().name()),
                Math.max(0L, soft.deadlineTick() - level.getGameTime()))));
            for (Node node : runner.suspendedNodes()) {
                lines.add(debugLine("suspended", DebugText.work(job(node.spec())),
                    DebugText.where(position(level, node.spec().site().where()))));
            }
        }
        int current = runner != null && runner.interruptedWork().isPresent() ? 1 : 0;
        if (route.size() > current) {
            section(lines, "route");
            route.stream().skip(current).limit(6).map(work -> workLine(level, work)).forEach(lines::add);
            more(lines, route.size() - current - 6);
            UUID head = runner == null || runner.queued().isEmpty() ? null : runner.queued().getFirst();
            if (head != null && untaken.containsKey(head)) {
                Untaken why = untaken.get(head);
                if (why.after() != null) {
                    Node before = node(why.after()).orElse(null);
                    lines.add(debugLine("waits", before == null ? DebugText.where(why.after().toString())
                        : DebugText.work(job(before.spec())), DebugText.where(before == null ? "-"
                            : position(level, before.spec().site().where())),
                        debugAssignee(why.after()).map(id -> name(level, id)).orElseGet(() -> word("nobody"))));
                } else {
                    MutableComponent untakenWhy = text("untaken",
                        word(why.why().startsWith("terminal:") ? "terminal" : why.why()));
                    long backoff = phases.waitTicks(head, level.getGameTime());
                    if (backoff > 0) {
                        untakenWhy.append(" · ").append(text("untaken.backoff", backoff));
                    }
                    lines.add(Line.text(untakenWhy));
                }
            }
        }
        List<Urge> offered = offeredUrges.getOrDefault(resident, List.of());
        if (!offered.isEmpty()) {
            section(lines, "urges");
            for (Urge urge : offered) {
                // Only the flags that are set and a running cooldown are worth reading.
                MutableComponent detail = text("urge", DebugText.work(Component.literal(urge.id().toString())),
                    urge.weight());
                if (urge.urgent()) {
                    detail.append(" · ").append(DebugText.tone(text("urge.urgent"), DebugText.WAIT));
                }
                if (urge.preempts()) {
                    detail.append(" · ").append(DebugText.tone(text("urge.preempts"), DebugText.WAIT));
                }
                long cooldown = urges.remaining(resident, urge.id(), level.getGameTime());
                if (cooldown > 0) {
                    detail.append(" · ").append(text("urge.cooldown", cooldown));
                }
                lines.add(Line.text(detail));
            }
        }
        section(lines, "carrying");
        List<Claim> carrying = new ArrayList<>();
        for (var entry : plannedClaims.entrySet()) {
            for (Claim claim : entry.getValue()) {
                boolean carried = claim.on() instanceof Resource.Cargo cargo && cargo.resident().equals(resident);
                boolean handed = claim.on() instanceof Resource.Handed cargo
                    && debugAssignee(cargo.by()).filter(resident::equals).isPresent();
                if (carried || handed) {
                    carrying.add(claim);
                }
            }
        }
        List<ItemStack> pack = body == null ? List.of() : body.pack().contents();
        List<ItemStack> cargo = body == null ? List.of() : Kit.of(works, body).cargoIn(body);
        lines.addAll(DebugGoods.carrying(pack, cargo, carrying));
        var observed = works.inventory().snapshot().residents().get(resident);
        // An observed snapshot is the normal case; only a missing or stale one is worth a line.
        if (observed == null || observed.state() != InventorySnapshot.State.OBSERVED) {
            lines.add(debugLine("inventory", observed == null ? word("unobserved") : word(observed.state().name())));
        }
        for (var hand : survey.hands()) {
            if (hand.id().equals(resident) && body != null && !sameCargo(hand.cargo(), cargo)) {
                lines.add(debugLine("planner_cargo"));
                goodsLines(lines, hand.cargo());
            }
        }
        lines.addAll(claimLines(level, carrying));
        var events = history.lines(resident, level.getGameTime());
        if (!events.isEmpty()) {
            section(lines, "events");
            lines.addAll(events);
        }
        DefenseControl control = DefenseControl.of(works);
        var order = ResidentCommands.of(works).current(resident);
        boolean takeover = CombatSelectionOwnership.of(works).reserved(resident);
        if (order.isPresent() || takeover || control.reserved(resident) || control.current(resident).isPresent()) {
            section(lines, "combat");
            List<Component> flags = new ArrayList<>();
            if (takeover) {
                flags.add(DebugText.tone(text("combat.takeover"), DebugText.WAIT));
            }
            if (control.reserved(resident)) {
                flags.add(DebugText.tone(text("combat.reserved"), DebugText.WAIT));
            }
            if (control.current(resident).isPresent()) {
                flags.add(DebugText.tone(text("combat.directed"), DebugText.WAIT));
            }
            if (!flags.isEmpty()) {
                lines.add(Line.text(join(flags)));
            }
            order.ifPresent(one -> {
                MutableComponent detail = text("order", word(one.mode().name()), word(one.status().name()));
                one.failure().ifPresent(why -> detail.append(" · ").append(text("order.failure",
                    DebugText.tone(Component.translatable(why.translationKey()), DebugText.BAD))));
                lines.add(Line.text(detail));
            });
        }
        return new ResidentDebug(lines, route);
    }

    public SiteDebug debugSite(ServerLevel level, WorldPos at) {
        requireDebugThread(level);
        List<Line> lines = new ArrayList<>();
        var store = works.inventory().snapshot().store(at);
        Set<WorldPos> aliases = new LinkedHashSet<>();
        aliases.add(at);
        store.ifPresent(one -> {
            aliases.add(one.stash().pos());
            aliases.addAll(one.aliases());
        });
        List<Claim> claims = new ArrayList<>();
        for (var entry : plannedClaims.entrySet()) {
            for (Claim claim : entry.getValue()) {
                Stash stash = claim.on() instanceof Resource.Lot lot ? lot.where()
                    : claim.on() instanceof Resource.Space space ? space.where()
                    : claim.on() instanceof Resource.Transit transit ? transit.where() : null;
                if (stash != null && aliases.contains(stash.pos())) {
                    claims.add(claim);
                }
            }
        }
        store.ifPresent(one -> lines.addAll(StoreDebug.lines(level, one, works.inventory(), claims)));
        if (!claims.isEmpty()) {
            section(lines, "reservations");
            lines.addAll(claimLines(level, claims));
        }
        Map<UUID, DebugWork> work = new LinkedHashMap<>();
        for (Tour tour : tours.values()) {
            for (int index = 0; index < tour.nodes().size(); index++) {
                int atIndex = index + 1;
                UUID id = tour.nodes().get(index);
                node(id).ifPresent(node -> work.put(id, debugWork(node, placementOf(id),
                    Optional.of(tour.resident()), atIndex)));
            }
        }
        for (BodyRunner runner : runners.all()) {
            for (DebugWork one : debugRoute(runner)) {
                work.put(one.spec().id(), one);
            }
            for (var kept : runner.suspendedWork()) {
                work.put(kept.node().id(), debugWork(kept.node(), kept.placement(), Optional.of(runner.resident()), 0));
            }
        }
        for (Unassigned unassigned : diagnosis.unassigned()) {
            node(unassigned.node()).ifPresent(node -> work.putIfAbsent(node.id(),
                debugWork(node, placementOf(node.id()), Optional.empty(), 0)));
        }
        List<DebugWork> here = work.values().stream().filter(one -> touches(one, aliases)).toList();
        if (!here.isEmpty()) {
            section(lines, "work");
            Map<WorkGroup, List<DebugWork>> groups = new LinkedHashMap<>();
            for (DebugWork one : here) {
                var key = new WorkGroup(job(one.spec()), one.phase(), one.resident());
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(one);
            }
            groups.entrySet().stream().limit(10).forEach(entry -> {
                WorkGroup key = entry.getKey();
                String indices = entry.getValue().stream().map(DebugWork::index).filter(index -> index > 0)
                    .distinct().sorted().map(index -> "#" + index).collect(java.util.stream.Collectors.joining(","));
                MutableComponent detail = text("assigned", DebugText.work(key.doing()), word(key.phase().name()),
                    key.resident().map(id -> name(level, id)).orElseGet(() -> word("nobody")), indices);
                if (key.resident().isEmpty()) {
                    List<Component> reasons = entry.getValue().stream().flatMap(one -> diagnosis.unassigned().stream()
                        .filter(unassigned -> unassigned.node().equals(one.spec().id())))
                        .map(unassigned -> word(unassigned.why().name())).distinct().toList();
                    if (!reasons.isEmpty()) {
                        detail.append(" · ").append(join(reasons));
                    }
                }
                lines.add(Line.text(detail));
            });
            more(lines, groups.size() - 10);
        }
        return new SiteDebug(lines, here);
    }

    private List<DebugWork> debugRoute(BodyRunner runner) {
        if (runner == null) {
            return List.of();
        }
        List<DebugWork> result = new ArrayList<>();
        runner.interruptedWork().ifPresent(node -> result.add(debugWork(node, runner.currentPlacement(),
            Optional.of(runner.resident()), 1)));
        for (UUID id : runner.queued()) {
            node(id).ifPresent(node -> result.add(debugWork(node, placementOf(id),
                Optional.of(runner.resident()), result.size() + 1)));
        }
        return List.copyOf(result);
    }

    private DebugWork debugWork(Node node, Placement placement, Optional<UUID> resident, int index) {
        return new DebugWork(node.spec(), placement, personal.knows(node.id()) ? personal.of(node.id())
            : phases.of(node.id()), resident, index, declined.contains(node.id()));
    }

    private Optional<UUID> debugAssignee(UUID node) {
        Optional<UUID> held = holder(node);
        if (held.isPresent()) {
            return held;
        }
        UUID worker = inputWorkers.get(node);
        if (worker != null) {
            return Optional.of(worker);
        }
        return runners.all().stream().filter(runner -> runner.queued().contains(node)
            || runner.suspendedNodes().stream().anyMatch(one -> one.id().equals(node)))
            .map(BodyRunner::resident).findFirst();
    }

    private record ClaimGroup(Object item, Component doing, Optional<UUID> resident) {
    }

    private record WorkGroup(Component doing, NodeState phase, Optional<UUID> resident) {
    }

    private List<Line> claimLines(ServerLevel level, List<Claim> claims) {
        Map<ClaimGroup, List<Claim>> groups = new LinkedHashMap<>();
        for (Claim claim : claims) {
            Object item = switch (claim.on()) {
                case Resource.Lot lot -> lot.spec();
                case Resource.Transit transit -> transit.spec();
                case Resource.Space space -> space.spec();
                case Resource.Cargo cargo -> cargo.spec();
                case Resource.Handed handed -> handed.spec();
                case Resource.Transport transport -> transport.resource();
            };
            Component doing = node(claim.owner()).map(node -> job(node.spec()))
                .orElseGet(() -> Component.literal(claim.owner().toString()));
            var key = new ClaimGroup(item, doing, debugAssignee(claim.owner()));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(claim);
        }
        List<Line> lines = new ArrayList<>();
        groups.entrySet().stream().limit(10).forEach(entry -> {
            ClaimGroup key = entry.getKey();
            long amount = entry.getValue().stream().mapToLong(Claim::amount).sum();
            long works = entry.getValue().stream().map(Claim::owner).distinct().count();
            Component item = DebugText.item(key.item() instanceof ItemSpec spec ? goods(spec)
                : Component.literal(key.item().toString()));
            MutableComponent detail = text("claim", item, amount, DebugText.work(key.doing()),
                key.resident().map(id -> name(level, id)).orElseGet(() -> word("nobody")));
            if (works > 1) {
                detail.append(" ").append(text("works", works));
            }
            lines.add(Line.text(detail));
        });
        more(lines, groups.size() - 10);
        return lines;
    }

    private static boolean touches(DebugWork one, Set<WorldPos> aliases) {
        var spec = one.spec();
        return aliases.contains(spec.site().where())
            || spec.focus().map(cell -> aliases.contains(spec.site().where().at(cell))).orElse(false)
            || one.placement().from().stream().anyMatch(stash -> aliases.contains(stash.pos()))
            || one.placement().into().stream().anyMatch(stash -> aliases.contains(stash.pos()))
            || one.placement().draws().stream().anyMatch(draw -> draw.from()
                .filter(stash -> aliases.contains(stash.pos())).isPresent());
    }

    private Line workLine(ServerLevel level, DebugWork work) {
        return debugLine(work.declined() ? "node.declined" : "node", work.index(), DebugText.work(job(work.spec())),
            DebugText.where(position(level, work.spec().site().where())), word(work.phase().name()));
    }

    static String position(ServerLevel level, WorldPos at) {
        return at.in(level.dimension()) ? at.cell().toShortString() : at.toString();
    }

    private static MutableComponent join(List<Component> parts) {
        return DebugText.join(parts);
    }

    public static Component job(NodeSpec spec) {
        String key = "folkways.doing." + spec.doing().getNamespace() + "." + spec.doing().getPath();
        return spec.about().<Component>map(about ->
            Component.translatable(key + ".about", Component.translatable(about)))
            .orElseGet(() -> Component.translatable(key));
    }

    private static Component name(ServerLevel level, UUID resident) {
        var entity = level.getEntity(resident);
        return DebugText.name(entity == null ? Component.literal(resident.toString()) : entity.getDisplayName());
    }

    private static void goodsLines(List<Line> lines, List<ItemStack> contents) {
        List<Line.Stack> stacks = contents.stream().filter(stack -> !stack.isEmpty())
            .map(stack -> new Line.Stack(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount())).toList();
        for (int from = 0; from < stacks.size(); from += Line.Goods.MOST_STACKS) {
            lines.add(Line.goods(stacks.subList(from, Math.min(from + Line.Goods.MOST_STACKS, stacks.size()))));
        }
    }

    private static boolean sameCargo(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (!ItemStack.matches(first.get(index), second.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static void requireDebugThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("debug snapshots belong to the server thread");
        }
    }

    private static Component goods(ItemSpec spec) {
        return Doings.about(spec).<Component>map(Component::translatable)
            .orElseGet(() -> Component.literal(spec.describe()));
    }

    private static Component word(String key) {
        return DebugText.word(key);
    }

    private static MutableComponent text(String key, Object... args) {
        return DebugText.text(key, args);
    }

    private static Line debugLine(String key, Object... args) {
        return DebugText.line(key, args);
    }

    private static void section(List<Line> lines, String key) {
        lines.add(DebugText.section(key));
    }

    private static void more(List<Line> lines, int count) {
        if (count > 0) {
            lines.add(debugLine("more", count));
        }
    }

    public WorkExecution execution() {
        return execution;
    }

    private void executionChanged(Event event) {
        switch (event) {
            case Event.Started(UUID node, UUID resident) -> started(node, resident);
            case Event.Failed(UUID node, RefusalKind why) -> failed(node, why);
            case Event.Cancelled(Node node) -> inbox.raise(new Message.Withdrawn(node.spec().owner(), node.id()));
            case Event.Released(UUID node) -> released(node);
            case Event.Forgotten(UUID node) -> forgetPersonal(node);
            case Event.Gone(UUID resident) -> gone(resident);
            case Event.Read(UUID resident) -> survey.read(resident);
            case Event.Refused(UUID resident, ResourceLocation urge, RefusalKind why, long now) ->
                urgeRefused(resident, urge, why, now);
            case Event.Balked(Node node, RefusalKind why, long now) -> stalls.met(node.spec(), why, now);
        }
    }

    public Colony colony() {
        return works;
    }

    public void forgetPersonal(UUID node) {
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

    public Optional<Node> node(UUID id) {
        return Optional.ofNullable(graph.vertex(id)).map(Vertex::node);
    }

    public Optional<Node> takeable(UUID id, ServerLevel level) {
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
    public record Untaken(String why, UUID after) { }

    private final Map<UUID, Untaken> untaken = new java.util.HashMap<>();

    // Ready, but taking it ahead of the work still waiting before it would overfill the pack.
    public void packBlocked(UUID id) {
        untaken.put(id, new Untaken("pack_order", null));
    }

    // One line on why a stalled body's queued node cannot be taken: the check that turned it down and, for an
    // order, where the work it waits on stands and who holds it.
    public String untakenWhy(UUID id) {
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

    public String untakenKey(UUID id) {
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
    public boolean fitsAhead(Body body, UUID next, Collection<UUID> route) {
        List<List<Amount>> order = new ArrayList<>();
        order.add(placementOf(next).carrying());
        for (UUID queued : route) {
            if (!queued.equals(next) && onTheGraph(queued)) {
                order.add(placementOf(queued).carrying());
            }
        }
        Kit kit = Kit.of(works, body);
        return io.github.izakyl.folkways.core.plan.api.PackSpace.of(body.pack(), kit.cargoIn(body)).fits(order);
    }

    // Two pending deliveries may share a destination even when their source stores differ. Only
    // explicit graph dependencies identify their cargo: matching item names alone is ambiguous.
    public boolean sharesDelivery(UUID previous, UUID candidate, Collection<UUID> route, Body body) {
        WorldPos destination = null;
        WorldPos nextDestination = null;
        for (UUID id : route) {
            Vertex vertex = graph.vertex(id);
            if (vertex == null
                || !(vertex.node() instanceof io.github.izakyl.folkways.core.work.internal.TransferNode transfer)
                    || transfer.fetching() || !onTheGraph(id)) { continue; }
            if (graph.before(id).contains(previous)) { destination = transfer.spec().site().where(); }
            if (graph.before(id).contains(candidate)) { nextDestination = transfer.spec().site().where(); }
        }
        Vertex take = graph.vertex(candidate);
        if (destination == null || !destination.equals(nextDestination) || take == null) { return false; }
        WorldPos here = io.github.izakyl.folkways.core.world.api.WorldSpaces.at(body.mob());
        WorldPos source = take.node().spec().site().where();
        if (!here.sameRealm(source) || !here.sameRealm(destination)) { return false; }
        double detour = Math.sqrt(here.cell().distSqr(source.cell()))
            + Math.sqrt(source.cell().distSqr(destination.cell()))
            - Math.sqrt(here.cell().distSqr(destination.cell()));
        return detour <= 8;
    }

    public boolean onTheGraph(UUID id) {
        return graph.has(id) && !phases.of(id).terminal();
    }

    public Placement placementOf(UUID id) {
        Vertex vertex = graph.vertex(id);
        return vertex == null ? Placement.NOWHERE : vertex.plan();
    }

    public Hands hands(Body body, Placement placement) {
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

    private void spilled(ItemEntity drop) {
        salvage.dropped(drop).ifPresent(message -> {
            inbox.raise(message);
            inbox.raise(Message.OFFERED);
        });
    }

    public void started(UUID node, UUID resident) {
        if (graph.has(node)) {
            inputWorkers.put(node, resident);
        }
        progress(node, WorkProgress.Step.STARTED, Optional.of(resident));
        inbox.raise(new Message.Started(node, resident));
    }

    public void finished(UUID node) {
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
    public void failed(UUID node, RefusalKind why) {
        phases.fail(node, why);
        if (!reportedFailures.add(node)) {
            return;
        }
        readout.refused(why);
        progress(node, WorkProgress.Step.FAILED, Optional.empty());
        inbox.raise(new Message.Failed(node, why));
        if (salvage.ended(node)) {
            inbox.raise(new Message.Withdrawn(io.github.izakyl.folkways.core.work.internal.PickUp.DOMAIN, node));
        }
    }

    public void released(UUID node) {
        inputWorkers.remove(node);
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

    public void gone(UUID resident) {
        equipmentRead.remove(resident);
        urges.gone(resident);
        offeredUrges.remove(resident);
        history.gone(resident);
        survey.forget(resident);
        kits.left(resident, inbox::raise);
        inbox.raise(new Message.Left(resident));
    }

    public void urgeRefused(UUID resident, ResourceLocation urge, RefusalKind why, long now) {
        readout.refused(why);
        urges.refused(resident, urge, why, now);
    }

    public LaborCensus census(ServerLevel level) {
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
        Set<UUID> carriedOff = colony.claims().carriedOff();
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

    public String heartbeat() {
        return readout.line(graph.vertices(), phases, schedule, diagnosis, runners,
            pipeline.busyFor(Util.getNanos())) + " limited=" + limitedSchedules;
    }

    public LaborSnapshot snapshot(ServerLevel level) {
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
                if (examples.size() == 3) {
                    break;
                }
            }
        }
        var inputs = reservedInputs();
        List<LaborSnapshot.Suspended> suspended = holdings.suspendedWorks().stream()
            .map(work -> new LaborSnapshot.Suspended(work,
                inputs.stream().filter(input -> input.node().equals(work.node())).toList(),
                plannedClaims.getOrDefault(work.node(), List.of())))
            .toList();
        return new LaborSnapshot(census(level), pipeline.busyFor(Util.getNanos()) / 1_000_000,
            lastScheduleNanos / 1_000_000, limitedSchedules, shortages, examples, suspended);
    }

    public long solvingFor(long now) {
        return pipeline.busyFor(now);
    }

    public Diagnosis diagnosis() {
        return diagnosis;
    }

    public Collection<String> stalls() {
        untaken.keySet().retainAll(graph.vertices().keySet());
        List<String> lines = new ArrayList<>(readout.stalls(graph.vertices(), phases, runners, this::untakenWhy,
            this::untakenKey));
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
        return colony.claims().entities();
    }
}
