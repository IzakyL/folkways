package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Balk;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkNoise;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.WorkEffort;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import io.github.izakyl.folkways.core.api.work.Xp;
import io.github.izakyl.folkways.core.engine.travel.Trip;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class BodyRunner {

    private static final double PACE = 1.0;

    private static final long AWAY_TICKS = 100L;

    private static final int STALL_POLL_TICKS = 4;

    private record Own(ResourceLocation urge) {
    }

    private final UUID resident;
    private final Deque<UUID> route = new ArrayDeque<>();

    private Node current;
    private Body activeBody;
    private Placement placement = Placement.NOWHERE;
    private Stances footing;
    private Own own;
    private Trip carrying;
    private Workload.Continuous ongoing;
    private Node justDone;
    private boolean joining;
    private int windup;
    private int windupTicks;
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

    Optional<Node> inHand() {
        return current == null || own != null ? Optional.empty() : Optional.of(current);
    }

    boolean ownBusiness() {
        return own != null;
    }

    Optional<ResourceLocation> ownUrge() {
        return own == null ? Optional.empty() : Optional.of(own.urge());
    }

    boolean executing() {
        return current != null;
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

    String standing() {
        if (current == null) {
            return stalled > 0 ? "stalled" : "idle";
        }
        if (carrying != null) {
            return "walking";
        }
        if (own != null) {
            return "urge";
        }
        return ongoing != null ? "holding" : "working";
    }

    void follow(List<UUID> given) {
        rerouted = true;
        route.clear();
        for (UUID node : given) {
            if (current == null || own != null || !current.id().equals(node)) {
                route.add(node);
            }
        }
    }

    void offer(ColonyLabor labor, ServerLevel level, Body body, Urge urge, Node doing) {
        this.own = new Own(urge.id());
        begin(labor, level, body, doing, Placement.NOWHERE);
    }

    void tick(ColonyLabor labor, ServerLevel level, Body body) {
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
        Phases states = phases(labor);
        if (!states.check(current.id())) {
            Node stopped = current;
            states.failure(stopped.id()).ifPresentOrElse(why -> {
                putDown(body);
                failed(labor, level, body, stopped, why);
                clear(body);
            }, () -> letGo(labor, level, body));
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
        if (current == null) {
            return;
        }
        Node node = current;
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
        if (current != null && activeBody != null && activeBody.mob().level() instanceof ServerLevel level) {
            letGo(labor, level, activeBody);
        }
        labor.gone(resident);
    }

    void drop(ColonyLabor labor) {
        if (activeBody != null && activeBody.mob().level() instanceof ServerLevel level) {
            letGo(labor, level, activeBody);
        }
    }

    void abandon(ColonyLabor labor) {
        drop(labor);
        route.clear();
    }

    private void take(ColonyLabor labor, ServerLevel level, Body body) {
        Iterator<UUID> queued = route.iterator();
        boolean owed = false;
        while (queued.hasNext()) {
            UUID next = queued.next();
            Optional<Node> ready = labor.takeable(next, level);
            // Work may be taken ahead of work still waiting before it, but only while the pack holds the route
            // done in that order: the schedule counted its loads in route order, and a step taken early must not
            // fill the cells the waiting work needs.
            if (ready.isPresent() && owed && !labor.fitsAhead(body, next, route)) {
                labor.packBlocked(next);
                continue;
            }
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
        justDone = null;
        NodeSpec spec = node.spec();
        switch (spec.workload()) {
            case Workload.Once once -> {
                ongoing = null;
                windupTicks = Math.max(0, once.ticksFor(labor.hands(body, plan)));
            }
            case Workload.Continuous condition -> {
                ongoing = condition;
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
            startWork(level, body);
            return;
        }
        Optional<Trip> trip = setOut(labor, level, body);
        if (trip.isEmpty()) {
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
        return labor.ways(level)
            .map(ways -> Trip.to(() -> labor.ways(level).orElse(ways), body.resident(), body.kind().locomotion(), body.mob(),
                footings, speed, LaborRefusal.NO_WAY_THERE))
            .orElseGet(() -> Trip.afoot(body.kind().locomotion(), footings, speed,
                LaborRefusal.NO_WAY_THERE));
    }

    private void carry(ColonyLabor labor, ServerLevel level, Body body) {
        if (standing(body)) {
            putDown(body);
            startWork(level, body);
            return;
        }
        switch (carrying.step(level, body.mob(), footings())) {
            case Going.Arrived ignored -> {
                putDown(body);
                startWork(level, body);
            }
            case Going.Failed(RefusalKind why) -> {
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

    private void startWork(ServerLevel level, Body body) {
        carrying = null;
        windup = 0;
        body.mob().getNavigation().stop();
        present(body);
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
        perform(body);
        if (!ongoing.holds(level, labor.hands(body, placement))) {
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

    private void perform(Body body) {
        current.spec().gesture().perform(body.mob(), windup, cellOf(body));
        current.spec().focus().ifPresent(cell -> {
            WorldPos focus = current.spec().site().where().at(cell);
            WorldSpaces.world(body.mob().level(), focus)
                .map(BlockPos::containing).ifPresent(target -> Gaze.turnTo(body.mob(), target));
        });
    }

    private void commit(ColonyLabor labor, ServerLevel level, Body body) {
        committing = true;
        Node node = current;
        Worker who = labor.hands(body, placement);
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
                Stowing.put(who, placement, made);
                xp.ifPresent(gained -> reward(level, body, gained));
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
        if (own == null && labor.phases().waits(node.id(), why)) {
            letGo(labor, level, body);
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
            node.released(level, labor.hands(body, placement), how);
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw ending as {}", node.id(), how, broken);
        }
    }

    private static void heard(ServerLevel level, Body body, Outcome outcome) {
        for (WorkNoise noise : outcome.noises()) {
            noise.sound(level, body.mob());
        }
    }

    private static void reward(ServerLevel level, Body body, Xp gained) {
        Bodies.credit(body, gained.vocation(), gained.amount(), level.getRandom());
        body.refreshPerkEffects();
    }

    private void present(Body body) {
        NodeSpec spec = current.spec();
        List<ToolUse> uses = ResidentKinds.barehanded(body.kind().id(), spec.vocation()) ? List.of() : spec.tools();
        for (ToolUse use : uses) {
            ItemStack tool = Tools.from(body.pack().contents(), use.need()).orElse(ItemStack.EMPTY);
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
