package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Urge;
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
import io.github.izakyl.folkways.core.engine.plan.Solved;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
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
import java.util.concurrent.Executor;
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

    private final Planner planner = new Planner(new Claims(), new Cooldowns());
    private Executor onPlanThread = Runnable::run;

    private final Phases phases = new Phases(this::ending);
    private record Ended(UUID node, Ending how) { }
    private final List<Ended> endings = new ArrayList<>();
    private final List<UUID> withdrawnGone = new ArrayList<>();
    private final Phases personal = new Phases();
    private record Personal(UUID resident, ResourceLocation urge) { }
    private final Map<UUID, Personal> personalRequests = new LinkedHashMap<>();
    private final Set<UUID> reportedFailures = new LinkedHashSet<>();
    private final Inbox inbox = new Inbox();
    private final Cycle cycle = new Cycle(inbox);
    private final Runners runners = new Runners(this::arrived);
    private final Urges urges = new Urges();
    private final Salvage salvage = new Salvage();
    private final Kits kits = new Kits();
    private final Survey survey = new Survey();
    private final Readout readout = new Readout();

    private long wakeAt = Long.MAX_VALUE;
    private boolean waysAnswered;
    private long waysAnsweredAt = Long.MIN_VALUE / 2;
    private long desertedSince = -1L;

    private boolean closed;
    private List<UUID> dropsToRead = List.of();

    private Map<UUID, Vertex> graph = Map.of();
    private final Set<UUID> declined = new LinkedHashSet<>();
    private Map<UUID, Set<UUID>> predecessors = Map.of();
    private Schedule schedule = Schedule.EMPTY;
    private Diagnosis diagnosis = Diagnosis.NOTHING;

    public ColonyLabor(ColonyData colony, ResourceKey<Level> dimension) {
        this.colony = colony;
        this.dimension = dimension;
        colony.works().listen(dimension, this);
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
        cycle.close();
        colony.works().unlisten(dimension, this);
        runners.close(this);
        phases.retain(Set.of());
        personal.retain(Set.of());
        personalRequests.clear();
        endings.clear();
        withdrawnGone.clear();
        onPlanThread.execute(planner::closed);
    }

    public void run(ServerLevel level, Executor onPlanThread) {
        if (closed) {
            return;
        }
        this.onPlanThread = onPlanThread;
        for (UUID drop : dropsToRead) {
            if (level.getEntity(drop) instanceof ItemEntity item && item.isAlive()) {
                spilled(item);
            }
        }
        dropsToRead = List.of();
        cycle.applyReady(level);
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
        runners.away(this, present.keySet(), level.getGameTime());
        ColonyView view = present.isEmpty() ? null : ColonySnapshot.of(colony, level);
        for (Body body : present.values()) {
            BodyRunner runner = runners.of(body.id());
            interrupt(level, view, body, runner);
            runner.tick(this, level, body);
        }
        answer(level, present);
        for (int round = 0; round < MOST_ROUNDS && !cycle.busy() && inbox.any(); round++) {
            plan(level);
            answer(level, present);
        }
        tellEndings();
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
        cycle.begin(level, onPlanThread, planner, known, level.getGameTime(),
            (batch, solved) -> apply(level, batch, solved));
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
            if (settled) readout.completed(node);
            if (settled || personal.of(id).terminal()) {
                Personal request = personalRequests.get(id);
                if (request != null) {
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
            runner.letGo(this, level, body);
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
        runner.letGo(this, level, body);
        personalRequests.put(doing.id(), new Personal(body.id(), keenest.get().id()));
        runner.offer(this, level, body, keenest.get(), doing);
    }

    private void apply(ServerLevel level, List<Message> batch, Solved solved) {
        Set<UUID> withdrawn = new LinkedHashSet<>();
        for (Message message : batch) {
            if (message instanceof Message.Withdrawn(var owner, UUID node)) {
                withdrawn.add(node);
            }
        }
        Map<UUID, Vertex> settled = new LinkedHashMap<>();
        for (Vertex vertex : solved.weave().vertices().values()) {
            settled.put(vertex.id(), vertex);
        }
        Set<UUID> fresh = solved.weave().fresh();
        Set<UUID> replaced = new LinkedHashSet<>();
        for (UUID had : graph.keySet()) {
            if (!settled.containsKey(had) || fresh.contains(had)) {
                replaced.add(had);
            }
        }
        runners.letGo(this, replaced);
        Set<UUID> regrew = new LinkedHashSet<>();
        for (UUID regrown : fresh) {
            if (graph.containsKey(regrown)) {
                phases.cancelled(regrown);
                phases.forget(regrown);
                declined.remove(regrown);
                reportedFailures.remove(regrown);
                regrew.add(regrown);
            }
        }
        if (!regrew.isEmpty()) {
            inbox.discard(message -> regrew.contains(about(message)));
        }
        List<Node> entered = settled.values().stream()
            .filter(vertex -> !graph.containsKey(vertex.id()) || fresh.contains(vertex.id()))
            .map(Vertex::node).toList();
        graph = Map.copyOf(settled);
        declined.retainAll(graph.keySet());
        reportedFailures.retainAll(graph.keySet());
        Map<UUID, Set<UUID>> before = new LinkedHashMap<>();
        for (Before link : solved.weave().orders()) {
            before.computeIfAbsent(link.to(), ignored -> new LinkedHashSet<>())
                .add(link.from());
        }
        predecessors = Map.copyOf(before);
        Map<UUID, Set<UUID>> grouped = new java.util.HashMap<>();
        for (Set<UUID> group : solved.weave().groups()) {
            group.forEach(member -> grouped.put(member, group));
        }
        groupOf = grouped;
        pins = solved.weave().pins();
        // Work the plan gave up ends as the plan says; work it holds off the plan for now has not ended.
        Map<UUID, Ending> given = new LinkedHashMap<>();
        Set<UUID> worked = new LinkedHashSet<>();
        for (Gone gone : solved.weave().gone()) {
            given.put(gone.node().id(), gone.how());
            if (phases.knows(gone.node().id())) {
                worked.add(gone.node().id());
            }
        }
        Set<UUID> live = new LinkedHashSet<>(graph.keySet());
        live.addAll(solved.weave().held());
        phases.retain(live, id -> withdrawn.contains(id) ? Ending.REVOKED : given.getOrDefault(id, Ending.DROPPED));
        for (UUID node : withdrawn) {
            if (!graph.containsKey(node)) {
                withdrawnGone.add(node);
            }
        }
        schedule = solved.schedule();
        diagnosis = solved.diagnosis();
        survey.asked(solved.asks());
        wakeAt = solved.asks().wakeAt();
        runners.follow(schedule.tours());
        for (Gone gone : solved.weave().gone()) {
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
            phases.entered(node, level);
            try {
                node.planned(level).ifPresent(why -> decline(node.id(), why));
            } catch (RuntimeException | LinkageError broken) {
                Labor.LOGGER.error("node {} threw accepting its plan", node.id(), broken);
                decline(node.id(), LaborRefusal.WORK_BROKE);
            }
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
        return graph.containsKey(node) ? Optional.of(phases.of(node)) : Optional.empty();
    }

    private void decline(UUID id, RefusalKind why) {
        declined.add(id);
        failed(id, why);
    }

    Optional<Node> takeable(UUID id, ServerLevel level) {
        if (declined.contains(id)) {
            untaken.put(id, new Untaken("declined", null));
            return Optional.empty();
        }
        for (UUID predecessor : predecessors.getOrDefault(id, Set.of())) {
            if (graph.containsKey(predecessor) && phases.of(predecessor) != NodeState.DONE) {
                untaken.put(id, new Untaken("after", predecessor));
                return Optional.empty();
            }
        }
        Vertex vertex = graph.get(id);
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
        Vertex vertex = graph.get(id);
        if (vertex == null) {
            return "[?]";
        }
        Node node = vertex.node();
        StringBuilder said = new StringBuilder("[").append(node.spec().doing().getPath())
            .append('@').append(node.spec().site().where().cell().toShortString())
            .append(" goods=").append(node.spec().about().orElse("-"));
        Set<UUID> group = groupOf.get(id);
        said.append(" group=").append(group == null ? 1 : group.size());
        UUID pin = pins.get(id);
        said.append(" pin=").append(pin == null ? "-" : pin.toString().substring(0, 8));
        node.worker().ifPresent(worker -> said.append(" worker=").append(worker.toString(), 0, 8));
        said.append(" in=").append(predecessors.getOrDefault(id, Set.of()).size());
        return said.append(']').toString();
    }

    private Map<UUID, Set<UUID>> groupOf = Map.of();
    private Map<UUID, UUID> pins = Map.of();

    private String kindOf(UUID id) {
        Vertex vertex = graph.get(id);
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
        int capacity = body.pack().plannableCells() - kit.keptCells(body);
        int used = capacity - body.pack().plannableRoom(item -> kit.holds(item.getDefaultInstance()));
        return PackLoad.fits(used, capacity, order);
    }

    boolean onTheGraph(UUID id) {
        return graph.containsKey(id) && !phases.of(id).terminal();
    }

    Placement placementOf(UUID id) {
        Vertex vertex = graph.get(id);
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
            Vertex vertex = graph.get(queued);
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
        inbox.raise(new Message.Started(node, resident));
    }

    void finished(UUID node) {
        Vertex finished = graph.get(node);
        readout.finished(finished == null ? null : finished.node());
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
        inbox.raise(new Message.Failed(node, why));
        if (salvage.ended(node)) {
            inbox.raise(new Message.Withdrawn(Salvage.DOMAIN, node));
        }
    }

    void released(UUID node) {
        readout.released();
        inbox.raise(new Message.Released(node));
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

    void urgeRefused(ResourceLocation urge, RefusalKind why, long now) {
        readout.refused(why);
        urges.refused(urge, why, now);
    }

    void broke(UUID resident, String item) {
        readout.broke(item);
        survey.read(resident);
    }

    String heartbeat() {
        return readout.line(graph, phases, schedule, diagnosis, runners,
            cycle.busyFor(Util.getNanos()));
    }

    long solvingFor(long now) {
        return cycle.busyFor(now);
    }

    Diagnosis diagnosis() {
        return diagnosis;
    }

    Collection<String> stalls() {
        untaken.keySet().retainAll(graph.keySet());
        List<String> lines = new ArrayList<>(readout.stalls(graph, phases, runners, this::untakenWhy, this::untakenKey));
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
