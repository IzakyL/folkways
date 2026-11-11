package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.resident.api.Balk;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkBatch;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.WorkExecution.Event;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BodyRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final long AWAY_TICKS = 100L;

    private static final int STALL_POLL_TICKS = 4;

    private static final long SOFT_DEADLINE_TICKS = 20L;

    private record Own(ResourceLocation urge) {
    }

    private final UUID resident;
    private final WorkExecution labor;
    // Runtime selects from this queue; controller takeover clears it.
    public final Deque<UUID> route = new ArrayDeque<>();

    private Node current;
    private Body activeBody;
    private Body lastBody;
    private Placement placement = Placement.NOWHERE;
    private WorkHolding execution;
    private SoftPreempt soft;
    private long hardReleasedGeneration = -1;
    private final Deque<Suspended> suspended = new ArrayDeque<>();
    private Pending pending;
    private record Suspended(Node node, Placement placement, WorkHolding holding, Own own) {
    }

    private record Pending(Urge urge, Node node) {
    }

    private HoldingRegistry.Held held;
    private Own own;
    private Node justDone;
    private int batchLength;
    private int stalled;
    private boolean rerouted;
    private int skipped;
    private int busy;
    private long awaySince = -1L;
    private boolean gone;
    private boolean reportedGone;
    private boolean worked = true;

    public BodyRunner(UUID resident, WorkExecution labor) {
        this.resident = resident;
        this.labor = labor;
    }

    public UUID resident() {
        return resident;
    }

    public Optional<UUID> holding() {
        return inHand().map(Node::id);
    }

    // The colony work in hand; urges are tracked separately.
    public Optional<Node> inHand() {
        return current == null || own != null ? Optional.empty() : Optional.of(current);
    }

    // Whether the body is about an urge of its own.
    public boolean ownBusiness() {
        return own != null;
    }

    public Optional<ResourceLocation> ownUrge() {
        return own == null ? Optional.empty() : Optional.ofNullable(own.urge());
    }

    public boolean executing() {
        return current != null;
    }

    public Optional<Node> interruptedWork() {
        return Optional.ofNullable(current);
    }

    public List<UUID> queued() {
        return List.copyOf(route);
    }

    public int stalledTicks() {
        return stalled;
    }

    public int busyTicks() {
        return busy;
    }

    public int skippedCount() {
        return skipped;
    }

    public Optional<SoftPreempt> softPreempt() {
        return Optional.ofNullable(soft);
    }

    public record KeptWork(Node node, Placement placement) {
    }

    public List<KeptWork> suspendedWork() {
        return suspended.stream().map(work -> new KeptWork(work.node(), work.placement())).toList();
    }

    public List<Node> suspendedNodes() {
        return suspended.stream().map(Suspended::node).toList();
    }

    public Placement currentPlacement() {
        return placement;
    }

    public Optional<String> executionStage() {
        return execution == null ? Optional.empty() : execution.debugStage();
    }

    public int drainSkipped() {
        int said = skipped;
        skipped = 0;
        return said;
    }

    // What the census counts the body as: its standing, away when it has been out of the world a while, and its own
    // business told by the kind of work it is (eating, sleeping, mending...).
    public String census() {
        if (awaySince >= 0L) {
            return "away";
        }
        if (ownBusiness() && current != null && !travelling()) {
            return "urge/" + current.getClass().getSimpleName();
        }
        return standing();
    }

    public String standing() {
        if (current == null) {
            return stalled > 0 ? "stalled" : "idle";
        }
        if (travelling()) {
            return "walking";
        }
        if (ownBusiness()) {
            return "urge";
        }
        return current.spec().workload() instanceof Workload.Continuous ? "holding" : "working";
    }

    public boolean approaching(UUID node) {
        return current != null && current.id().equals(node) && travelling();
    }

    private boolean travelling() {
        return execution instanceof SuppliedHolding supplied && supplied.travelling();
    }

    public boolean preempting() {
        return soft != null || pending != null;
    }

    public Optional<Urge> pendingUrge() {
        return pending == null ? Optional.empty() : Optional.of(pending.urge());
    }

    public void discardPending() {
        if (pending != null) {
            labor.personal().cancelled(pending.node().id());
            labor.report(new Event.Forgotten(pending.node().id()));
            pending = null;
        }
    }

    // Takes up the route as laid, less the work in hand. A route just as it already stands changes nothing: the
    // runner keeps its pace of polling rather than looking again at once.
    public void follow(List<UUID> given) {
        List<UUID> laid = new ArrayList<>(given.size());
        for (UUID node : given) {
            if (current == null || own != null || !current.id().equals(node)) {
                laid.add(node);
            }
        }
        if (laid.size() == route.size() && laid.equals(new ArrayList<>(route))) {
            return;
        }
        rerouted = true;
        route.clear();
        route.addAll(laid);
    }

    public void offer(ServerLevel level, Body body, Urge urge, Node doing) {
        this.own = new Own(urge.id());
        begin(level, body, doing, Placement.NOWHERE);
    }

    public boolean tickChecked(ServerLevel level, Body body) {
        if (current == null && pending != null && soft == null) {
            startPending(level, body);
        }
        if (current == null && !suspended.isEmpty() && pending == null) {
            resume(level, body);
        }
        if (current == null) {
            busy = 0;
            if (stalled > 0 && !rerouted && stalled % STALL_POLL_TICKS != 0) {
                stalled++;
                return false;
            }
            rerouted = false;
            return true;
        }
        // The same resident may come back as a new entity, as a train's rider does when its carriage is put back.
        activeBody = body;
        busy++;
        advance(level, body, execution.tick(context(level, body)));
        return false;
    }

    private HoldingContext context(ServerLevel level, Body body) {
        return labor.context(level, body, placement);
    }

    private void advance(ServerLevel level, Body body, HoldingStep step) {
        switch (step) {
            case HoldingStep.Running ignored -> { }
            case HoldingStep.Yielded ignored -> {
                if (soft != null) {
                    finishPreempt(level, body, soft.result(), soft.reason(), false);
                    startPending(level, body);
                }
            }
            case HoldingStep.Done ignored -> {
                handIn(level, body, current);
                clear();
                startPending(level, body);
            }
            case HoldingStep.Failed(RefusalKind why) -> {
                release(level, body, PreemptReason.OTHER);
                failed(level, body, current, why);
                clear();
                startPending(level, body);
            }
        }
    }

    // Validate before interrupt can reserve the body and skip the runner altogether.
    public void check(ServerLevel level, Body body) {
        check(level, body, true);
    }

    private void check(ServerLevel level, Body body, boolean present) {
        lastBody = body;
        checkSuspended(level, body);
        if (current != null) {
            activeBody = body;
        }
        if (current == null) {
            if (present) {
                startPending(level, body);
            }
            return;
        }
        Phases states = phases();
        if (!states.check(current.id())) {
            Node stopped = current;
            states.failure(stopped.id()).ifPresentOrElse(why -> {
                release(level, body, PreemptReason.REVOKED);
                failed(level, body, stopped, why);
                clear();
            }, () -> letGo(level, body, "check:" + states.of(stopped.id())
                + (states.waiting(stopped.id()) ? ":waiting" : "")));
        }
        if (current == null && present) {
            startPending(level, body);
        }
        checkDeadline(level, body, level.getGameTime(), present);
    }

    public void checkDeadline(ServerLevel level, Body body, long now, boolean present) {
        if (current != null && soft != null && now >= soft.deadlineTick()) {
            SoftPreempt expired = soft;
            finishPreempt(level, body, expired.result(), expired.reason(), true);
            if (present) {
                startPending(level, body);
            }
        }
    }

    public void letGo(ServerLevel level, Body body) {
        letGo(level, body, "other");
    }

    public void letGo(ServerLevel level, Body body, String why) {
        if (current == null) {
            return;
        }
        Node node = current;
        if (why.equals("urge-gone")) {
            LaborIncident.work(labor.colony().id(), level, body, node, why,
                () -> net.minecraft.network.chat.Component.translatable("folkways.debug.incident.urge_ended"));
        }
        boolean onGraph = own == null;
        PreemptReason reason = why.startsWith("urge") ? PreemptReason.URGE
            : why.equals("replaced") ? PreemptReason.REPLACED
            : why.startsWith("check:") ? PreemptReason.REVOKED : PreemptReason.OTHER;
        release(level, body, reason);
        Ending how = why.startsWith("check:") || why.equals("replaced") ? Ending.REVOKED : Ending.DROPPED;
        ended(level, body, node, placement, how);
        finish(node, onGraph ? how : Ending.REVOKED, onGraph,
            why.startsWith("waits:") || why.endsWith(":waiting"));
        clear();
    }

    public void here() {
        awaySince = -1L;
        if (gone) {
            gone = false;
            if (reportedGone) {
                reportedGone = false;
                labor.report(new Event.Read(resident));
            }
        }
    }

    public void away(long now) {
        if (awaySince < 0L) {
            awaySince = now;
            return;
        }
        if (gone || now - awaySince < AWAY_TICKS) {
            return;
        }
        if (execution instanceof SuppliedHolding) {
            return;
        }
        gone = true;
        Body last = activeBody;
        if (last != null && last.mob().level() instanceof ServerLevel level) {
            letGo(level, last, "away");
        }
        if (suspended.isEmpty()) {
            reportedGone = true;
            labor.report(new Event.Gone(resident));
        }
    }

    public void drop() {
        drop("drop");
    }

    public void drop(String why) {
        Body last = activeBody;
        if (last != null && last.mob().level() instanceof ServerLevel level) {
            letGo(level, last, why);
        }
    }

    public void abandon() {
        discardPending();
        drop();
        if (lastBody != null && lastBody.mob().level() instanceof ServerLevel level) {
            for (Suspended work : List.copyOf(suspended)) {
                endSuspended(level, lastBody, work, Ending.REVOKED, PreemptReason.REVOKED);
            }
        }
        route.clear();
    }

    public void cancelNodes(Set<UUID> nodes) {
        if (lastBody == null || !(lastBody.mob().level() instanceof ServerLevel level)) {
            return;
        }
        for (Suspended work : List.copyOf(suspended)) {
            if (nodes.contains(work.node().id())) {
                endSuspended(level, lastBody, work, Ending.REVOKED, PreemptReason.REVOKED);
            }
        }
        if (holding().filter(nodes::contains).isPresent()) {
            drop("replaced");
        }
    }

    public void checkAbsent(ServerLevel level) {
        if (lastBody != null) {
            check(level, lastBody, false);
        }
    }

    public Node lastDone() {
        return justDone;
    }

    public WorkBatch batch(Body body) {
        WorkBatch batch = justDone == null ? null : justDone.batch(body).orElse(null);
        return batch == null || batchLength >= batch.limit() ? null : batch;
    }

    public void skipped() {
        skipped++;
    }

    public void waiting() {
        stalled++;
    }

    public boolean rested() {
        stalled = 0;
        boolean hadWork = worked;
        worked = false;
        return hadWork;
    }

    public void take(ServerLevel level, Body body, Node node, Placement plan) {
        stalled = 0;
        own = null;
        begin(level, body, node, plan);
    }

    private void begin(ServerLevel level, Body body, Node node, Placement plan) {
        busy = 0;
        worked = true;
        body.clearBalk();
        current = node;
        activeBody = body;
        lastBody = body;
        placement = plan;
        boolean joining = justDone != null && node.joins(justDone);
        WorkBatch batch = node.batch(body).orElse(null);
        boolean continuing = batch != null && justDone != null
            && justDone.batch(body).filter(batch::accepts).isPresent();
        batchLength = batch == null ? 0 : continuing ? batchLength + 1 : 1;
        justDone = null;
        if (held != null) {
            labor.holdings().release(held);
        }
        held = labor.holdings().acquire(resident, node.id(), node.spec().owner());
        phases().started(node.id());
        if (own == null) {
            labor.report(new Event.Started(node.id(), resident));
        }
        HoldingContext context = context(level, body);
        execution = new SuppliedHolding(node, plan, new HoldingContext(level, body, context.worker(),
            context.actions(), context.settlement(), context.holdings(), joining));
    }

    private void release(ServerLevel level, Body body, PreemptReason reason) {
        hardRelease(level, body, Disposition.END, reason);
    }

    public boolean preemptsSuspended(Urge urge, List<Urge> offered) {
        if (suspended.isEmpty()) {
            return true;
        }
        if (!urge.preempts()) {
            return false;
        }
        Own previous = suspended.peek().own();
        return previous == null || offered.stream().filter(rest -> rest.id().equals(previous.urge()))
            .allMatch(urge::outranks);
    }

    public void takeover(ServerLevel level, Body body, Urge urge, Node node) {
        pending = new Pending(urge, node);
        if (!executing()) {
            startPending(level, body);
            return;
        }
        if (soft != null && soft.reason() == PreemptReason.CONTROLLER) {
            if (urge.urgent()) {
                preemptHard(level, body, Disposition.END, PreemptReason.CONTROLLER);
            }
            return;
        }
        if (urge.urgent()) {
            preemptHard(level, body, Disposition.SUSPEND, PreemptReason.URGE);
        } else {
            requestSoft(level, body, new SoftPreempt(Disposition.SUSPEND, PreemptReason.URGE,
                level.getGameTime() + SOFT_DEADLINE_TICKS));
        }
    }

    public void controllerTakeover(ServerLevel level, Body body, boolean ordered) {
        route.clear();
        for (Suspended work : List.copyOf(suspended)) {
            if (work.own() == null) {
                controllerIncident(level, body, work.node());
                endSuspended(level, body, work, Ending.DROPPED, PreemptReason.CONTROLLER);
            }
        }
        if (inHand().isEmpty()) {
            return;
        }
        if (soft == null || soft.reason() != PreemptReason.CONTROLLER) {
            controllerIncident(level, body, inHand().orElseThrow());
        }
        if (ordered) {
            preemptHard(level, body, Disposition.END, PreemptReason.CONTROLLER);
        } else if (soft == null || soft.reason() != PreemptReason.CONTROLLER) {
            requestSoft(level, body, new SoftPreempt(Disposition.END, PreemptReason.CONTROLLER,
                level.getGameTime() + SOFT_DEADLINE_TICKS));
        }
    }

    private void controllerIncident(ServerLevel level, Body body, Node node) {
        LaborIncident.work(labor.colony().id(), level, body, node, PreemptReason.CONTROLLER.name(),
            () -> net.minecraft.network.chat.Component.translatable("folkways.debug.incident.controller",
                PreemptReason.CONTROLLER.name()));
    }

    public void requestSoft(ServerLevel level, Body body, SoftPreempt request) {
        if (soft != null && request.reason() != PreemptReason.CONTROLLER) {
            return;
        }
        if (execution == null) {
            return;
        }
        soft = request;
        execution.requestSoftPreempt(context(level, body), request);
    }

    public void preemptHard(ServerLevel level, Body body, Disposition result, PreemptReason reason) {
        if (!executing()) {
            return;
        }
        finishPreempt(level, body, result, reason, true);
        startPending(level, body);
    }

    private void hardRelease(ServerLevel level, Body body, Disposition result,
            PreemptReason reason) {
        if (execution == null || held == null || hardReleasedGeneration == held.generation()
                || labor.holdings().byResident(resident).filter(held::equals).isEmpty()) {
            return;
        }
        hardReleasedGeneration = held.generation();
        execution.hardRelease(context(level, body), result, reason);
    }

    private void finishPreempt(ServerLevel level, Body body, Disposition result,
            PreemptReason reason, boolean hard) {
        if (current == null || held == null) {
            return;
        }
        HoldingRegistry.Held generation = held;
        if (hard) {
            hardRelease(level, body, result, reason);
            if (!generation.equals(held)
                    || labor.holdings().byResident(resident).filter(generation::equals).isEmpty()) {
                return;
            }
        }
        if (result == Disposition.SUSPEND) {
            Suspended work = new Suspended(current, placement, execution, own);
            labor.holdings().suspend(held, level.getGameTime(), reason);
            held = null;
            suspended.push(work);
            execution.suspended(context(level, body), reason);
            reset();
        } else {
            Node node = current;
            Ending how = reason == PreemptReason.REVOKED || reason == PreemptReason.REPLACED
                ? Ending.REVOKED : Ending.DROPPED;
            ended(level, body, node, placement, how);
            if (own == null && reason == PreemptReason.CONTROLLER) {
                requeue(node);
            } else {
                phases().cancelled(node.id(), how);
                notifyEnd(node, own == null);
            }
            clear();
        }
    }

    private void startPending(ServerLevel level, Body body) {
        if (pending == null || execution != null) {
            return;
        }
        Pending next = pending;
        pending = null;
        offer(level, body, next.urge(), next.node());
    }

    public void resume(ServerLevel level, Body body) {
        if (current != null || suspended.isEmpty() || pending != null) {
            return;
        }
        Suspended work = suspended.pop();
        current = work.node();
        placement = work.placement();
        execution = work.holding();
        own = work.own();
        activeBody = body;
        held = labor.holdings().resume(current.id());
        execution.resumed(context(level, body));
    }

    public void checkSuspended(ServerLevel level, Body body) {
        for (Suspended work : List.copyOf(suspended)) {
            Phases states = work.own() == null ? labor.phases() : labor.personal();
            if (!states.check(work.node().id()) && !states.waiting(work.node().id())) {
                endSuspended(level, body, work, states.failure(work.node().id())
                    .<Ending>map(why -> new Ending.Failed(work.node().id(), why)).orElse(Ending.REVOKED),
                    PreemptReason.REVOKED);
            }
        }
    }

    public void cancelMissingUrges(ServerLevel level, Body body, List<Urge> offered) {
        for (Suspended work : List.copyOf(suspended)) {
            if (work.own() != null
                    && offered.stream().noneMatch(urge -> urge.id().equals(work.own().urge()))) {
                endSuspended(level, body, work, Ending.REVOKED, PreemptReason.REVOKED);
            }
        }
    }

    private void endSuspended(ServerLevel level, Body body, Suspended work, Ending how,
            PreemptReason reason) {
        if (!suspended.remove(work)) {
            return;
        }
        labor.holdings().forgetSuspended(work.node().id());
        ended(level, body, work.node(), work.placement(), how);
        finish(work.node(), how, work.own() == null, reason == PreemptReason.CONTROLLER);
    }

    private void finish(Node node, Ending how, boolean onGraph, boolean retry) {
        if (onGraph && retry) {
            requeue(node);
        } else if (onGraph && how instanceof Ending.Failed failed) {
            labor.report(new Event.Failed(node.id(), failed.why()));
        } else {
            (onGraph ? labor.phases() : labor.personal()).cancelled(node.id(), how);
            notifyEnd(node, onGraph);
        }
    }

    private void requeue(Node node) {
        labor.phases().letGo(node.id());
        labor.report(new Event.Released(node.id()));
    }

    private void notifyEnd(Node node, boolean onGraph) {
        labor.report(onGraph ? new Event.Cancelled(node) : new Event.Forgotten(node.id()));
    }

    private Phases phases() {
        return own == null ? labor.phases() : labor.personal();
    }

    private void handIn(ServerLevel level, Body body, Node node) {
        justDone = node;
        body.clearBalk();
        ended(level, body, node, placement, Ending.DONE);
        phases().handedOver(node.id());
    }

    private void failed(ServerLevel level, Body body, Node node,
            RefusalKind why) {
        LaborIncident.work(labor.colony().id(), level, body, node, why.translationKey(),
            () -> net.minecraft.network.chat.Component.translatable("folkways.debug.incident.refused",
                net.minecraft.network.chat.Component.translatable(why.translationKey())));
        NodeSpec spec = node.spec();
        body.balked(new Balk(Doing.open(spec.doing()).about(spec.about()).with(node.wares()), why,
            level.getGameTime()));
        if (own == null) {
            labor.report(new Event.Balked(node, why, level.getGameTime()));
        }
        if (own == null && labor.phases().waits(node.id(), why)) {
            letGo(level, body, "waits:" + why);
            return;
        }
        ended(level, body, node, placement, new Ending.Failed(node.id(), why));
        if (own != null) {
            labor.personal().failed(node.id(), why);
            labor.report(new Event.Forgotten(node.id()));
            if (own.urge() != null) {
                labor.report(new Event.Refused(body.id(), own.urge(), why, level.getGameTime()));
            }
            return;
        }
        labor.report(new Event.Failed(node.id(), why));
    }

    private void ended(ServerLevel level, Body body, Node node, Placement plan, Ending how) {
        try {
            node.released(level, labor.hands(body, plan), how);
        } catch (RuntimeException | LinkageError broken) {
            LOGGER.error("node {} threw ending as {}", node.id(), how, broken);
        }
    }

    private void clear() {
        if (held != null) {
            labor.holdings().release(held);
            held = null;
        }
        reset();
    }

    private void reset() {
        current = null;
        execution = null;
        soft = null;
        activeBody = null;
        placement = Placement.NOWHERE;
        own = null;
    }
}
