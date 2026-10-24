package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.KitRules;
import io.github.izakyl.folkways.core.api.work.Balk;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.WorkYield;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.core.engine.travel.Trip;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

final class BodyRunner {

    private static final double PACE = 1.0;

    private static final long AWAY_TICKS = 100L;

    private static final boolean LET_GO_DEBUG = Boolean.getBoolean("folkways.letGoDebug") || true;

    private static final int STALL_POLL_TICKS = 4;

    // How often a body waiting within its work for a piece to come up asks for one.
    private static final int WAIT_POLL_TICKS = 10;

    // How long one beat of work held open lasts before the body turns to the next store or back to the work.
    private static final int BEAT_TICKS = 10;

    // One turn of the body while it works: toward a store it reaches into, or toward the work itself.
    private record Beat(Optional<BlockPos> at, boolean store) {
    }

    private record Own(ResourceLocation urge) {
    }

    private final UUID resident;
    private final Deque<UUID> route = new ArrayDeque<>();

    private Node current;
    private Body activeBody;
    private Placement placement = Placement.NOWHERE;
    private Stances footing;
    // Work the body is in, pieces of which it is handed one after another, and how the plan placed that work.
    private Node within;
    private Placement withinPlan = Placement.NOWHERE;
    private Body withinBody;
    private int waited;
    private Own own;
    private Trip carrying;
    private Workload.Continuous ongoing;
    private Node justDone;
    private int haulBatch;
    private boolean joining;
    private int windup;
    private int windupTicks;
    private List<Beat> beats = List.of();
    private boolean committing;
    private int stalled;
    private boolean rerouted;
    private int skipped;
    private int busy;
    private long awaySince = -1L;
    private boolean gone;
    private boolean worked = true;

    BodyRunner(UUID resident) {
        this.resident = resident;
    }

    UUID resident() {
        return resident;
    }

    Optional<UUID> holding() {
        return inHand().map(Node::id);
    }

    // The work in hand as the plan knows it: work the body is in, whatever piece of it it is doing.
    Optional<Node> inHand() {
        if (within != null) {
            return Optional.of(within);
        }
        return current == null || own != null ? Optional.empty() : Optional.of(current);
    }

    // Whether the body is about an urge of its own; a piece of the work it is in is not that.
    boolean ownBusiness() {
        return own != null && own.urge() != null;
    }

    Optional<ResourceLocation> ownUrge() {
        return own == null ? Optional.empty() : Optional.ofNullable(own.urge());
    }

    boolean executing() {
        return current != null || within != null;
    }

    List<UUID> queued() {
        return List.copyOf(route);
    }

    int stalledTicks() {
        return stalled;
    }

    int busyTicks() {
        return busy;
    }

    int drainSkipped() {
        int said = skipped;
        skipped = 0;
        return said;
    }

    // What the census counts the body as: its standing, away when it has been out of the world a while, and its own
    // business told by the kind of work it is (eating, sleeping, mending...).
    String census() {
        if (awaySince >= 0L) {
            return "away";
        }
        if (ownBusiness() && current != null && carrying == null) {
            return "urge/" + current.getClass().getSimpleName();
        }
        return standing();
    }

    String standing() {
        if (current == null) {
            return within != null ? "working" : stalled > 0 ? "stalled" : "idle";
        }
        if (carrying != null) {
            return "walking";
        }
        if (ownBusiness()) {
            return "urge";
        }
        return ongoing != null ? "holding" : "working";
    }

    // Takes up the route as laid, less the work in hand. A route just as it already stands changes nothing: the
    // runner keeps its pace of polling rather than looking again at once.
    void follow(List<UUID> given) {
        List<UUID> laid = new ArrayList<>(given.size());
        for (UUID node : given) {
            if ((current == null || own != null || !current.id().equals(node))
                    && (within == null || !within.id().equals(node))) {
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

    void offer(ColonyLabor labor, ServerLevel level, Body body, Urge urge, Node doing) {
        this.own = new Own(urge.id());
        begin(labor, level, body, doing, Placement.NOWHERE);
    }

    void tick(ColonyLabor labor, ServerLevel level, Body body) {
        if (within != null && (own == null || own.urge() == null)) {
            try {
                ((Workload.Within) within.spec().workload()).tick(level, labor.hands(body, withinPlan, within.spec()));
            } catch (RuntimeException | LinkageError broken) {
                Labor.LOGGER.error("node {} threw during its presentation", within.id(), broken);
            }
        }
        if (current == null && within != null) {
            inside(labor, level, body);
            return;
        }
        if (current == null) {
            busy = 0;
            if (stalled > 0 && !rerouted && stalled % STALL_POLL_TICKS != 0) {
                stalled++;
                return;
            }
            rerouted = false;
            take(labor, level, body);
            return;
        }
        // The same resident may come back as a new entity, as a train's rider does when its carriage is put back.
        activeBody = body;
        Phases states = phases(labor);
        if (!states.check(current.id())) {
            Node stopped = current;
            states.failure(stopped.id()).ifPresentOrElse(why -> {
                putDown(body);
                failed(labor, level, body, stopped, why);
                clear(body);
            }, () -> letGo(labor, level, body, "check:" + states.of(stopped.id()) + (states.waiting(stopped.id()) ? ":waiting" : "")));
            return;
        }
        busy++;
        if (carrying != null) {
            carry(labor, level, body);
            return;
        }
        if (ongoing != null) {
            hold(labor, level, body);
            return;
        }
        windUp(labor, level, body);
    }

    void letGo(ColonyLabor labor, ServerLevel level, Body body) {
        letGo(labor, level, body, "other");
    }

    // An urge takes the body from the piece in hand only: it stays in the work it is in, and goes back to it after.
    // Anything else that lets go takes it out of that work as well.
    void letGo(ColonyLabor labor, ServerLevel level, Body body, String why) {
        boolean urged = why.startsWith("urge");
        if (current == null) {
            if (within == null || urged) {
                return;
            }
            comeOut();
        }
        Node node = current;
        if (LET_GO_DEBUG) {
            Labor.LOGGER.info("let go node={} doing={} body={} at={} why={}", node.id(), node.spec().doing(),
                body.mob().getUUID(), body.mob().blockPosition().toShortString(), why);
        }
        boolean onGraph = own == null;
        node.spec().gesture().release(body.mob());
        putDown(body);
        ended(labor, level, body, node, Ending.DROPPED);
        if (onGraph) {
            labor.phases().letGo(node.id());
            labor.released(node.id());
        } else {
            labor.personal().cancelled(node.id());
            labor.forgetPersonal(node.id());
        }
        clear(body);
        if (within != null && !urged) {
            letGo(labor, level, body, why);
        }
    }

    // The work the body is in becomes the work in hand again, to be ended as any other.
    private void comeOut() {
        current = within;
        activeBody = withinBody;
        placement = withinPlan;
        own = null;
        within = null;
        withinBody = null;
        waited = 0;
    }

    private void enter(Body body) {
        Node entered = current;
        Placement plan = placement;
        body.mob().getNavigation().stop();
        clear(body);
        within = entered;
        withinBody = body;
        // Where the plan stood the body for the whole of the work is not where each piece of it is done from.
        withinPlan = new Placement(Set.of(), plan.carrying(), plan.from(), plan.into(), plan.draws());
        waited = 0;
    }

    // In its work and free: the owner is asked what the body does next. A piece is run as the body's own, beside
    // the plan; with none to come the body comes out, and the work is done.
    private void inside(ColonyLabor labor, ServerLevel level, Body body) {
        withinBody = body;
        if (!labor.phases().check(within.id())) {
            Node stopped = within;
            comeOut();
            labor.phases().failure(stopped.id()).ifPresentOrElse(why -> {
                failed(labor, level, body, stopped, why);
                clear(body);
            }, () -> letGo(labor, level, body, "check:" + labor.phases().of(stopped.id())));
            return;
        }
        if (waited > 0 && waited % WAIT_POLL_TICKS != 0) {
            waited++;
            return;
        }
        Workload.Within shift = (Workload.Within) within.spec().workload();
        Workload.Within.Turn turn;
        try {
            turn = shift.next(level, labor.hands(body, withinPlan, within.spec()));
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw asked for its next piece", within.id(), broken);
            turn = Workload.Within.LEAVE;
        }
        switch (turn) {
            case Workload.Within.Turn.Piece(Node piece) -> {
                waited = 0;
                if (!labor.inner(piece, resident, level)) {
                    told(level, piece, Ending.DROPPED);
                    return;
                }
                own = new Own(null);
                begin(labor, level, body, piece, withinPlan);
            }
            case Workload.Within.Turn.Wait ignored -> waited++;
            case Workload.Within.Turn.Leave ignored -> leave(labor, level, body);
        }
    }

    // What the work needed went into its pieces as they were done, so nothing is drawn for it now.
    private void leave(ColonyLabor labor, ServerLevel level, Body body) {
        comeOut();
        activeBody = body;
        Node node = current;
        Outcome outcome;
        try {
            outcome = node.commit(level, labor.hands(body, placement, current.spec()));
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw on commit", node.id(), broken);
            refuse(labor, level, body, LaborRefusal.WORK_BROKE);
            return;
        }
        switch (outcome) {
            case Outcome.Done done -> handIn(labor, level, body, node);
            case Outcome.Failed(List<WorkNoise> ignored, RefusalKind why) -> failed(labor, level, body, node, why);
        }
        clear(body);
    }

    private void told(ServerLevel level, Node piece, Ending how) {
        try {
            ((Workload.Within) within.spec().workload()).ended(level, piece, how);
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw told its piece {} ended as {}", within.id(), piece.id(), how, broken);
        }
    }

    void here(ColonyLabor labor) {
        awaySince = -1L;
        if (gone) {
            gone = false;
            labor.arrived(resident);
        }
    }

    void away(ColonyLabor labor, long now) {
        if (awaySince < 0L) {
            awaySince = now;
            return;
        }
        if (gone || now - awaySince < AWAY_TICKS) {
            return;
        }
        gone = true;
        Body last = activeBody != null ? activeBody : withinBody;
        if (last != null && last.mob().level() instanceof ServerLevel level) {
            letGo(labor, level, last, "away");
        }
        labor.gone(resident);
    }

    void drop(ColonyLabor labor) {
        drop(labor, "drop");
    }

    void drop(ColonyLabor labor, String why) {
        Body last = activeBody != null ? activeBody : withinBody;
        if (last != null && last.mob().level() instanceof ServerLevel level) {
            letGo(labor, level, last, why);
        }
    }

    void abandon(ColonyLabor labor) {
        drop(labor);
        route.clear();
    }

    private void take(ColonyLabor labor, ServerLevel level, Body body) {
        gather(labor, level, body);
        Iterator<UUID> queued = route.iterator();
        boolean owed = false;
        while (queued.hasNext()) {
            UUID next = queued.next();
            // Readiness may enter an owner's execution queue (shared kitchen tools, for example).
            // Check pack safety first, so work we cannot start never reserves ahead of runnable work.
            if (owed && labor.onTheGraph(next) && !labor.fitsAhead(body, next, route)) {
                labor.packBlocked(next);
                continue;
            }
            Optional<Node> ready = labor.takeable(next, level);
            if (ready.isPresent()) {
                queued.remove();
                stalled = 0;
                own = null;
                begin(labor, level, body, ready.get(), labor.placementOf(next));
                return;
            }
            if (labor.onTheGraph(next)) {
                owed = true;
                continue;
            }
            skipped++;
            queued.remove();
        }
        if (owed) {
            stalled++;
            return;
        }
        stalled = 0;
        if (worked) {
            worked = false;
            if (!Kit.of(labor.colony(), body).cargoIn(body).isEmpty()) {
                labor.idle(resident);
            } else {
                labor.reread(resident);
            }
        }
    }

    // Gather compatible loads sharing a source or nearby destination. Every moved step is ready and its
    // inventory effect must fit the entire remaining route; reservations still commit separately.
    private void gather(ColonyLabor labor, ServerLevel level, Body body) {
        if (!(justDone instanceof TransferNode previous) || !previous.fetching()
                || haulBatch >= (int) body.effects().value(Haul.BATCH_SIZE)) {
            return;
        }
        int checked = 0;
        for (UUID id : List.copyOf(route)) {
            if (++checked > 32) { break; }
            // Inspect only actual compatible fetches. Merely probing a non-hauling node's readiness
            // can reserve its shared tools even though gathering will never execute that node.
            if (!(labor.node(id).orElse(null) instanceof TransferNode next) || !next.fetching()
                    || !(next.joins(previous) || labor.sharesDelivery(previous.id(), id, route, body))
                    || !labor.fitsAhead(body, id, route)) {
                continue;
            }
            if (labor.takeable(id, level).isPresent()) {
                route.remove(id);
                route.addFirst(id);
                return;
            }
        }
    }

    private void begin(ColonyLabor labor, ServerLevel level, Body body, Node node,
            Placement plan) {
        busy = 0;
        worked = true;
        body.clearBalk();
        current = node;
        activeBody = body;
        placement = plan;
        footing = footingOf(node, plan);
        joining = justDone != null && node.joins(justDone);
        haulBatch = node instanceof TransferNode transfer && transfer.fetching()
            ? justDone instanceof TransferNode previous && previous.fetching() ? haulBatch + 1 : 1 : 0;
        justDone = null;
        NodeSpec spec = node.spec();
        switch (spec.workload()) {
            case Workload.Once once -> {
                ongoing = null;
                windupTicks = Math.max(0, once.ticksFor(labor.hands(body, plan, node.spec())));
            }
            case Workload.Continuous condition -> {
                ongoing = condition;
                windupTicks = 0;
            }
            case Workload.Within ignored -> {
                ongoing = null;
                windupTicks = 0;
            }
        }
        phases(labor).started(node.id());
        if (own == null) {
            labor.started(node.id(), resident);
        }
        setOff(labor, level, body);
    }

    private void setOff(ColonyLabor labor, ServerLevel level, Body body) {
        windup = 0;
        carrying = null;
        if (standing(body)) {
            if (joining) {
                windupTicks = 0;
            }
            startWork(labor, level, body);
            return;
        }
        Optional<Trip> trip = setOut(labor, level, body);
        if (trip.isEmpty()) {
            if (LET_GO_DEBUG) {
                Labor.LOGGER.info("no trip node={} body={} at={}", current.id(), body.mob().getUUID(),
                    body.mob().blockPosition().toShortString());
            }
            refuse(labor, level, body, LaborRefusal.NO_WAY_THERE);
            return;
        }
        carrying = trip.get();
        body.setDoing(heading());
        carrying.setOut(level, body.mob(), footings());
        underway(body);
    }

    private void underway(Body body) {
        carrying.doing()
            .map(doing -> doing.what().equals(Doings.WALKING) ? heading()
                : doing.headingFor(current.spec().doing(), current.spec().about(), current.wares()))
            .ifPresent(body::setDoing);
    }

    private Doing heading() {
        NodeSpec spec = current.spec();
        return Doing.heading(spec.doing(), spec.about(), current.wares());
    }

    private Optional<Trip> setOut(ColonyLabor labor, ServerLevel level, Body body) {
        Set<WorldPos> footings = footings();
        double speed = PACE * current.spec().pace();
        if (current.spec().vocation().filter(trade -> trade.id().equals(Vocations.HAULING)).isPresent()) {
            speed *= body.effects().value(Haul.TRAVEL_SPEED);
        }
        double travelSpeed = speed;
        return labor.ways(level)
            .map(ways -> Trip.to(() -> labor.ways(level).orElse(ways), body.resident(), body.kind().locomotion(), body.mob(),
                footings, travelSpeed, LaborRefusal.NO_WAY_THERE))
            .orElseGet(() -> Trip.afoot(body.kind().locomotion(), footings, travelSpeed,
                LaborRefusal.NO_WAY_THERE));
    }

    private void carry(ColonyLabor labor, ServerLevel level, Body body) {
        if (standing(body)) {
            putDown(body);
            startWork(labor, level, body);
            return;
        }
        switch (carrying.step(level, body.mob(), footings())) {
            case Going.Arrived ignored -> {
                putDown(body);
                startWork(labor, level, body);
            }
            case Going.Failed(RefusalKind why) -> {
                if (LET_GO_DEBUG) {
                    Labor.LOGGER.info("trip failed node={} body={} at={} why={} trip={} stage={}", current.id(), body.mob().getUUID(),
                        body.mob().blockPosition().toShortString(), why, carrying.doing().map(d -> d.what().toString()).orElse("-"),
                        carrying.describe());
                }
                putDown(body);
                refuse(labor, level, body,
                    why instanceof LaborRefusal named ? named : LaborRefusal.NO_WAY_THERE);
            }
            case Going.Underway ignored -> underway(body);
        }
    }

    private void putDown(Body body) {
        if (carrying != null && body.mob().level() instanceof ServerLevel level) {
            carrying.release(level, body.mob());
        }
        carrying = null;
    }

    private void startWork(ColonyLabor labor, ServerLevel level, Body body) {
        if (own == null && current.spec().workload() instanceof Workload.Within) {
            enter(body);
            return;
        }
        carrying = null;
        windup = 0;
        body.mob().getNavigation().stop();
        beats = beatsOf(level, body);
        present(labor, body);
        NodeSpec spec = current.spec();
        if (ongoing != null) {
            body.setDoing(Doing.open(spec.doing()).about(spec.about()).with(current.wares()));
            return;
        }
        long from = level.getGameTime();
        body.setDoing(Doing.over(spec.doing(), from, from + windupTicks).about(spec.about()).with(current.wares()));
    }

    private void windUp(ColonyLabor labor, ServerLevel level, Body body) {
        perform(body);
        if (windup >= windupTicks) {
            commit(labor, level, body);
            return;
        }
        windup++;
    }

    private void hold(ColonyLabor labor, ServerLevel level, Body body) {
        Worker who = labor.hands(body, placement, current.spec());
        if (!ResidentKinds.barehanded(body.kind().id(), current.spec().vocation())
                && current.spec().tools().stream().anyMatch(use ->
                    !(use.need() instanceof ToolNeed.None)
                        && who.held(use.need()).isEmpty())) {
            refuse(labor, level, body, LaborRefusal.NO_ONE_FIT);
            return;
        }
        perform(body);
        if (!ongoing.holds(level, labor.hands(body, placement, current.spec()))) {
            commit(labor, level, body);
            return;
        }
        windup++;
    }

    private void reportEffort(Body body, Node node) {
        WorkEffort effort = node.spec().effort();
        if (effort instanceof WorkEffort.None) {
            return;
        }
        double done = switch (effort) {
            case WorkEffort.Total total -> total.amount() * (windupTicks > 0
                ? Math.min(1.0D, (double) windup / windupTicks) : committing ? 1.0D : 0.0D);
            case WorkEffort.PerTick rate -> rate.amount() * windup;
            case WorkEffort.None ignored -> 0;
        };
        WorkExertion.performed(body.mob(), done, windup, 0);
    }

    // Work that reaches into stores where it stands, rather than having goods carried to it, shows each reach in
    // the one wind-up: the body turns to a store it takes from, back to the work, and on to a store it puts into,
    // reaching each time. Work without stores at hand plays its gesture once, as it always has.
    private void perform(Body body) {
        if (beats.size() <= 1) {
            current.spec().gesture().perform(body.mob(), windup, cellOf(body));
            focusOf(body).ifPresent(target -> Gaze.turnTo(body.mob(), target));
            return;
        }
        int length = ongoing != null ? BEAT_TICKS : Math.max(1, (windupTicks + 1) / beats.size());
        int index = windup / length;
        Beat beat = beats.get(ongoing != null ? index % beats.size() : Math.min(index, beats.size() - 1));
        int into = ongoing != null || index < beats.size() ? windup % length : windup - (beats.size() - 1) * length;
        beat.at().ifPresent(target -> Gaze.turnTo(body.mob(), target));
        if (!beat.store()) {
            current.spec().gesture().perform(body.mob(), into, cellOf(body));
        } else if (into == 0) {
            body.mob().swing(InteractionHand.MAIN_HAND);
        }
    }

    private Optional<BlockPos> focusOf(Body body) {
        return current.spec().focus()
            .flatMap(cell -> WorldSpaces.world(body.mob().level(), current.spec().site().where().at(cell)))
            .map(BlockPos::containing);
    }

    // The stores the work takes from and puts into where it stands, with the work between each: from one store to
    // the work, to the next store, back to the work, and on to the store what it makes goes into.
    private List<Beat> beatsOf(ServerLevel level, Body body) {
        Set<BlockPos> from = new LinkedHashSet<>();
        for (Placement.Draw draw : placement.draws()) {
            draw.from().flatMap(stash -> cellOf(level, stash)).ifPresent(from::add);
        }
        Set<BlockPos> into = new LinkedHashSet<>();
        for (Stash stash : placement.into()) {
            cellOf(level, stash).ifPresent(into::add);
        }
        Optional<BlockPos> focus = focusOf(body);
        Optional<BlockPos> work = focus.isPresent() ? focus : Optional.of(cellOf(body))
            .filter(cell -> current.spec().site() instanceof WorkSite.AtBlock);
        work.ifPresent(cell -> {
            from.remove(cell);
            into.remove(cell);
        });
        Beat working = new Beat(work, false);
        List<Beat> beats = new ArrayList<>();
        for (BlockPos store : from) {
            beats.add(new Beat(Optional.of(store), true));
            beats.add(working);
        }
        if (beats.isEmpty()) {
            beats.add(working);
        }
        for (BlockPos store : into) {
            if (beats.getLast().store()) {
                beats.add(working);
            }
            beats.add(new Beat(Optional.of(store), true));
        }
        // Work over before every turn could be shown, such as one joined onto the last, shows only its own.
        if (ongoing == null && windupTicks + 1 < beats.size()) {
            return List.of(working);
        }
        return List.copyOf(beats);
    }

    private static Optional<BlockPos> cellOf(ServerLevel level, Stash stash) {
        return stash.pos().in(level) ? WorldSpaces.world(level, stash.pos()).map(BlockPos::containing)
            : Optional.empty();
    }

    private void commit(ColonyLabor labor, ServerLevel level, Body body) {
        committing = true;
        Node node = current;
        Worker who = labor.hands(body, placement, current.spec());
        Outcome outcome;
        try {
            outcome = Commit.run(level, node, who);
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw on commit", node.id(), broken);
            refuse(labor, level, body, LaborRefusal.WORK_BROKE);
            return;
        }
        node.spec().gesture().release(body.mob());
        heard(level, body, outcome);
        switch (outcome) {
            case Outcome.Done(List<WorkNoise> ignored, List<ItemStack> made, Optional<Xp> xp,
                              List<ItemStack> returned) -> {
                Wear.from(level, body, node.spec(), who, item -> labor.broke(resident, item));
                WorkYield.gave(body.mob(), node, made);
                Stowing.put(who, placement, made);
                xp.ifPresent(gained -> Bodies.worked(body, gained.vocation(), windup, level.getRandom()));
                handIn(labor, level, body, node);
            }
            case Outcome.Failed(List<WorkNoise> ignored, RefusalKind why) ->
                failed(labor, level, body, node, why);
        }
        clear(body);
    }

    private Phases phases(ColonyLabor labor) {
        return own == null ? labor.phases() : labor.personal();
    }

    private void handIn(ColonyLabor labor, ServerLevel level, Body body, Node node) {
        justDone = node;
        body.clearBalk();
        ended(labor, level, body, node, Ending.DONE);
        phases(labor).handedOver(node.id());
    }

    private void failed(ColonyLabor labor, ServerLevel level, Body body, Node node,
            RefusalKind why) {
        NodeSpec spec = node.spec();
        body.balked(new Balk(Doing.open(spec.doing()).about(spec.about()).with(node.wares()), why,
            level.getGameTime()));
        if (own == null) {
            labor.balked(node, why, level.getGameTime());
        }
        if (own == null && labor.phases().waits(node.id(), why)) {
            letGo(labor, level, body, "waits:" + why);
            return;
        }
        ended(labor, level, body, node, new Ending.Failed(node.id(), why));
        if (own != null) {
            labor.personal().failed(node.id(), why);
            labor.forgetPersonal(node.id());
            if (own.urge() != null) {
                labor.urgeRefused(own.urge(), why, level.getGameTime());
            }
            return;
        }
        labor.failed(node.id(), why);
    }

    private void refuse(ColonyLabor labor, ServerLevel level, Body body, LaborRefusal why) {
        current.spec().gesture().release(body.mob());
        failed(labor, level, body, current, why);
        clear(body);
    }

    private void ended(ColonyLabor labor, ServerLevel level, Body body, Node node, Ending how) {
        // Every execution leaves here once, including interruptions, failures and shutdown.
        // Use actual active progress, not wall time spent travelling, waiting or unloaded.
        reportEffort(body, node);
        try {
            node.released(level, labor.hands(body, placement, node.spec()), how);
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw ending as {}", node.id(), how, broken);
        }
        if (within != null && own != null && own.urge() == null) {
            told(level, node, how);
        }
    }

    private static void heard(ServerLevel level, Body body, Outcome outcome) {
        for (WorkNoise noise : outcome.noises()) {
            noise.sound(level, body.mob());
        }
    }



    private void present(ColonyLabor labor, Body body) {
        NodeSpec spec = current.spec();
        List<ToolUse> uses = ResidentKinds.barehanded(body.kind().id(), spec.vocation()) ? List.of() : spec.tools();
        for (ToolUse use : uses) {
            ItemStack tool = Tools.from(body.pack().contents(), use.need(),
                item -> KitRules.allows(labor.colony(), spec.vocation(), item)).orElse(ItemStack.EMPTY);
            if (!tool.isEmpty()) {
                body.presentHeldItem(tool.copy());
                break;
            }
        }
        spec.gesture().present(body, spec.focus());
    }

    private void clear(Body body) {
        current = null;
        activeBody = null;
        placement = Placement.NOWHERE;
        footing = null;
        own = null;
        carrying = null;
        ongoing = null;
        joining = false;
        windup = 0;
        windupTicks = 0;
        beats = List.of();
        committing = false;
        body.clearDoing();
        body.clearHandPresentation();
    }

    private static Stances footingOf(Node node, Placement plan) {
        if (plan.stands().isEmpty()) {
            return node.spec().stances();
        }
        return Stances.of(plan.stands().stream().map(Stand::cell).toList())
            .orElseGet(() -> node.spec().stances());
    }

    private Set<WorldPos> footings() {
        return footing.cells();
    }

    private boolean standing(Body body) {
        return footing instanceof Stances.Wherever || Reach.standingIn(body.mob(), footing);
    }

    private BlockPos cellOf(Body body) {
        return current.spec().site() instanceof WorkSite.AtBlock(WorldPos pos)
            ? WorldSpaces.world(body.mob().level(), pos)
                .map(BlockPos::containing).orElse(body.mob().blockPosition())
            : body.mob().blockPosition();
    }
}
