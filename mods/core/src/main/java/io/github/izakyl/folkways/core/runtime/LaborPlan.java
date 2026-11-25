package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.Diagnosis;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.plan.internal.PlanInputs;
import io.github.izakyl.folkways.core.plan.internal.PlanTask;
import io.github.izakyl.folkways.core.plan.internal.Planner;
import io.github.izakyl.folkways.core.plan.internal.Schedule;
import io.github.izakyl.folkways.core.plan.internal.Unassigned;
import io.github.izakyl.folkways.core.plan.internal.WeaveView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

// This reader belongs to the server thread; only immutable summaries leave it.
public final class LaborPlan {

    private static final long DEMAND_TICKS = 6000;

    private static final Map<ColonyData, Deque<Event>> EVENTS = new WeakHashMap<>();

    private final WeaveView graph = new WeaveView();
    private record Seed(NodeSpec spec, UUID worker) {
    }

    private final Map<UUID, Seed> requested = new LinkedHashMap<>();
    private Map<UUID, PlanTask> attempted = Map.of();
    private final Deque<Event> events;
    private Map<UUID, Cooldowns.Bar> cooldowns = Map.of();
    private Diagnosis diagnosis = Diagnosis.NOTHING;
    private List<Reason> reasons;
    private Schedule schedule = Schedule.EMPTY;
    private PlanInputs inputs = PlanInputs.EMPTY;
    private long tick = -1;
    private long scheduleTick = -1;
    private long rounds;
    private long demandUntil = Long.MIN_VALUE;
    private long demandBegan = Long.MAX_VALUE;
    private long summaryTick = -1;

    LaborPlan(ColonyData colony) {
        events = EVENTS.computeIfAbsent(colony, ignored -> new ArrayDeque<>());
    }

    public record Reason(UUID node, String kind, String code, boolean structural, String item, long missing,
                         List<UUID> holes, UUID blockedBy, UUID root) {

        public Reason {
            holes = List.copyOf(holes);
        }
    }

    public enum Type {
        SUBMITTED, WITHDRAWN, OFFERED, NOT_READY, READY, STARTED, FINISHED, FAILED,
        RELEASED, LEFT, IDLE, PACK_BUSY, ANSWERED
    }

    public record Event(Type type, UUID node, UUID resident, RefusalKind why, long tick, ResourceKey<Level> dimension) {
    }

    void woven(List<Message> batch, Planner.Woven woven, long now, ResourceKey<Level> dimension) {
        rounds++;
        tick = woven.tick() < 0 ? now : woven.tick();
        for (Message message : batch) {
            record(message, tick, dimension);
            switch (message) {
                case Message.Withdrawn withdrawn -> requested.remove(withdrawn.node());
                case Message.Finished finished -> requested.remove(finished.node());
                default -> { }
            }
            if (message instanceof Message.Submitted submitted) {
                submitted.work().nodes().forEach(node -> {
                    requested.put(node.id(), new Seed(node.spec(), node.worker().orElse(null)));
                });
            }
        }
        graph.apply(woven.delta());
        woven.delta().gone().forEach(gone -> requested.remove(gone.node().id()));
        if (woven.wanted() && wanted(now)) {
            Set<UUID> consumers = new LinkedHashSet<>();
            woven.shortfalls().forEach(shortfall -> consumers.add(shortfall.consumer()));
            Map<UUID, PlanTask> kept = new LinkedHashMap<>();
            attempted.forEach((id, task) -> {
                if (consumers.contains(id)) {
                    kept.put(id, task);
                }
            });
            woven.attempted().forEach(task -> kept.put(task.id(), task));
            attempted = kept;
            cooldowns = woven.cooldowns();
            inputs = woven.inputs();
            summaryTick = tick;
        } else {
            forgetSummaries();
        }
    }

    public void demand(long now) {
        if (!wanted(now)) {
            demandBegan = now;
            forgetSummaries();
        }
        demandUntil = now + DEMAND_TICKS;
    }

    public boolean wanted(long now) {
        return now < demandUntil;
    }

    public boolean warming() {
        return summaryTick < demandBegan;
    }

    private void forgetSummaries() {
        attempted = Map.of();
        cooldowns = Map.of();
        inputs = PlanInputs.EMPTY;
        summaryTick = -1;
    }

    void diagnosed(Diagnosis diagnosis) {
        this.diagnosis = diagnosis;
        reasons = null;
    }

    private List<Reason> reasons() {
        if (reasons != null) {
            return reasons;
        }
        List<Reason> result = new ArrayList<>();
        diagnosis.shortfalls().forEach(shortfall -> result.add(new Reason(shortfall.consumer(), "shortfall",
            Readout.name(shortfall.why()), false, shortfall.what().describe(), shortfall.missing(),
            shortfall.holes(), null, null)));
        diagnosis.refused().forEach(refused -> result.add(new Reason(null, "refused", refused.why().translationKey(),
            false, refused.asked().map(item -> item.describe()).orElse(null), 0, List.of(), null, null)));
        Map<UUID, Unassigned> waiting = new LinkedHashMap<>();
        diagnosis.unassigned().forEach(each -> waiting.put(each.node(), each));
        diagnosis.unassigned().forEach(each -> {
            UUID root = each.node();
            Set<UUID> seen = new LinkedHashSet<>();
            while (seen.add(root)) {
                Unassigned held = waiting.get(root);
                if (held == null || held.blockedBy() == null) {
                    break;
                }
                root = held.blockedBy();
            }
            result.add(new Reason(each.node(), "unassigned", each.why().name(), each.why().structural(),
                null, 0, List.of(), each.blockedBy(), root));
        });
        reasons = List.copyOf(result);
        return reasons;
    }

    void laid(Schedule schedule, long tick) {
        this.schedule = schedule;
        scheduleTick = tick;
    }

    private void record(Message message, long tick, ResourceKey<Level> dimension) {
        UUID node = switch (message) {
            case Message.Withdrawn withdrawn -> withdrawn.node();
            case Message.NotReady pending -> pending.node();
            case Message.Ready ready -> ready.node();
            case Message.Started started -> started.node();
            case Message.Finished finished -> finished.node();
            case Message.Failed failed -> failed.node();
            case Message.Released released -> released.node();
            default -> null;
        };
        UUID resident = switch (message) {
            case Message.Started started -> started.resident();
            case Message.Left left -> left.resident();
            case Message.Idle idle -> idle.resident();
            case Message.PackBusy busy -> busy.resident();
            default -> null;
        };
        RefusalKind why = message instanceof Message.Failed failed ? failed.why() : null;
        Type type = switch (message) {
            case Message.Submitted ignored -> Type.SUBMITTED;
            case Message.Withdrawn ignored -> Type.WITHDRAWN;
            case Message.Offered ignored -> Type.OFFERED;
            case Message.NotReady ignored -> Type.NOT_READY;
            case Message.Ready ignored -> Type.READY;
            case Message.Started ignored -> Type.STARTED;
            case Message.Finished ignored -> Type.FINISHED;
            case Message.Failed ignored -> Type.FAILED;
            case Message.Released ignored -> Type.RELEASED;
            case Message.Left ignored -> Type.LEFT;
            case Message.Idle ignored -> Type.IDLE;
            case Message.PackBusy ignored -> Type.PACK_BUSY;
            case Message.Answered ignored -> Type.ANSWERED;
        };
        if (message instanceof Message.Submitted submitted) {
            for (var task : submitted.work().nodes()) {
                append(new Event(type, task.id(), null, null, tick, dimension));
            }
        } else {
            append(new Event(type, node, resident, why, tick, dimension));
        }
    }

    private void append(Event event) {
        if (events.size() == 512) {
            events.removeFirst();
        }
        events.addLast(event);
    }

    public static Optional<LaborPlan> of(ServerLevel level, UUID colony) {
        return Labor.site(colony, level.dimension()).map(labor -> labor.planView());
    }

    public List<PlanTask> tasks() {
        Map<UUID, PlanTask> tasks = new LinkedHashMap<>(attempted);
        requested.forEach((id, seed) -> {
            if (!graph.has(id) && !tasks.containsKey(id)) {
                tasks.put(id, PlanTask.of(seed.spec(), seed.worker()));
            }
        });
        graph.vertices().forEach((id, vertex) ->
            tasks.put(id, PlanTask.of(vertex.node().spec(), vertex.node().worker().orElse(null))));
        return List.copyOf(tasks.values());
    }

    public Set<UUID> before(UUID id) {
        return Set.copyOf(graph.before(id));
    }

    public Set<UUID> after(UUID id) {
        return Set.copyOf(graph.after(id));
    }

    public Set<UUID> group(UUID id) {
        return Set.copyOf(graph.group(id));
    }

    public boolean onGraph(UUID id) {
        return graph.has(id);
    }

    public boolean held(UUID id) {
        return graph.held().contains(id);
    }

    public UUID resident(PlanTask task) {
        if (task.worker() != null) {
            return task.worker();
        }
        UUID pin = graph.pin(task.id());
        if (pin != null) {
            return pin;
        }
        return schedule.tours().values().stream().filter(tour -> tour.nodes().contains(task.id()))
            .map(tour -> tour.resident()).findFirst().orElse(null);
    }

    public boolean assigned(UUID id) {
        return schedule.tours().values().stream().anyMatch(tour -> tour.nodes().contains(id));
    }

    public List<Reason> reasons(PlanTask task) {
        return reasons().stream().filter(reason -> task.id().equals(reason.node()) || reason.holes().contains(task.id())
            || reason.node() == null && reason.item() != null && task.needs().stream()
                .anyMatch(need -> need.item().equals(reason.item()))).toList();
    }

    public Map<UUID, Cooldowns.Bar> cooldowns() {
        return cooldowns;
    }

    public PlanInputs inputs() {
        return inputs;
    }

    public List<Event> events() {
        return List.copyOf(events);
    }

    public long tick() {
        return tick;
    }

    public long scheduleTick() {
        return scheduleTick;
    }

    public long rounds() {
        return rounds;
    }
}
