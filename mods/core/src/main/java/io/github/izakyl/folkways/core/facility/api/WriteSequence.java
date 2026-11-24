package io.github.izakyl.folkways.core.facility.api;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.ReservationPool;
import io.github.izakyl.folkways.core.work.api.ReservedInput;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stances;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** A series of native signal writes and item transfers with one clock. */
public final class WriteSequence implements Routine {

    private static final int MAX_TICKS = 24_000;

    @Override
    public ResourceLocation id() {
        return Routines.WRITE_SEQUENCE;
    }

    @Override
    public String description() {
        return "Write signals and transfer items on ticks relative to the first step.";
    }

    @Override
    public JsonObject argumentSchema() {
        return Routine.argumentsSchema(Map.of("steps", "array", "on_interrupt", "object"), "steps");
    }

    @Override
    public List<String> writeTargets() {
        return List.of("steps");
    }

    @Override
    public Set<String> writes(Map<String, Value> arguments) {
        return steps(arguments).stream().flatMap(step -> step.controls().stream()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void validate(ServerLevel level, FacilityDefinition facility, Use use) {
        validate(level, facility, use, Posts.declared(level, facility));
    }

    void validate(ServerLevel level, FacilityDefinition facility, Use use, List<Post> posts) {
        var call = new Call(facility.id(), DefinedFacilities.ID, facility.id(), facility.site(), facility.parameters(),
            use.with(), Map.of(), facility.rig(level));
        new Sequence(call).validate(level, posts);
    }

    @Override
    public Optional<Refinement.Change> expand(Call call) {
        return Optional.of(Refinement.Change.of(Grown.of(new Sequence(call))));
    }

    static List<Step> steps(Map<String, Value> arguments) {
        if (!(arguments.get("steps") instanceof Value.Sequence entries) || entries.values().isEmpty()) {
            throw invalid();
        }
        var steps = new ArrayList<Step>();
        int previous = 0;
        for (Value entry : entries.values()) {
            Map<String, Value> fields;
            try {
                fields = DefinitionCodec.fields(entry);
            } catch (IllegalArgumentException malformed) {
                throw invalid();
            }
            if (!(fields.get("atTick") instanceof Value.Real time) || time.value() != (int) time.value()
                    || time.value() < previous || time.value() > MAX_TICKS
                    || steps.isEmpty() && time.value() != 0) {
                throw invalid();
            }
            previous = (int) time.value();
            if (fields.get("control") instanceof Value.Text control && fields.containsKey("value")) {
                steps.add(new Step(List.of(control.value()), fields.get("value"), null, null, null, previous));
            } else {
                String from = endpoint(fields, "from");
                String into = endpoint(fields, "into");
                if (from == null && into == null || from != null && from.equals(into)
                        || !(fields.get("item") instanceof Value.Text item)
                        || !(fields.get("count") instanceof Value.Real count)
                        || count.value() < 1 || count.value() != (int) count.value()) {
                    throw invalid();
                }
                Amount amount;
                try {
                    amount = new Amount(FacilityWorkshop.spec(item.value(), true), (int) count.value());
                } catch (IllegalArgumentException malformed) {
                    throw invalid();
                }
                List<String> names = from == null ? List.of(into) : into == null ? List.of(from) : List.of(from, into);
                steps.add(new Step(names, null, from, into, amount, previous));
            }
        }
        return List.copyOf(steps);
    }

    private static String endpoint(Map<String, Value> fields, String key) {
        if (!fields.containsKey(key)) {
            return null;
        }
        if (fields.get(key) instanceof Value.Text name && !name.value().isBlank()) {
            return name.value();
        }
        throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(FacilityRefusal.SEQUENCE_STEPS.translationKey());
    }

    record Step(List<String> controls, Value value, String from, String into, Amount amount, int tick) {
    }

    record Write(ControlRef ref, Value value, int tick, PostTransfer transfer) {

        Write(ControlRef ref, Value value, int tick) {
            this(ref, value, tick, null);
        }

        List<ControlRef> controls() {
            return transfer == null ? List.of(ref) : transfer.controls();
        }
    }

    static List<ControlRef> controls(List<Write> writes) {
        return writes.stream().flatMap(write -> write.controls().stream()).distinct().toList();
    }

    static List<Post> covering(ServerLevel level, List<Post> posts, List<Write> writes) {
        var required = writes.stream().filter(write -> write.transfer() == null).map(write -> requiredPost(level, write.ref())).flatMap(Optional::stream)
            .collect(Collectors.toSet());
        return posts.stream().filter(post -> required.isEmpty() || required.equals(Set.of(post.key())))
            .filter(post -> controls(writes).stream().allMatch(ref -> post.covers(level, ref))).toList();
    }

    static Optional<PostKey> requiredPost(ServerLevel level, ControlRef ref) {
        return Controls.signal(level, ref.at(), ref.part()).flatMap(signal -> signal.requiredPost(level));
    }

    private static Optional<Stances> common(Stances left, Stances right) {
        if (left instanceof Stances.Wherever) {
            return Optional.of(right);
        }
        if (right instanceof Stances.Wherever || left.equals(right)) {
            return Optional.of(left);
        }
        if (left instanceof Stances.Cells a && right instanceof Stances.Cells b) {
            var cells = new LinkedHashSet<>(a.cells());
            cells.retainAll(b.cells());
            return Stances.of(cells);
        }
        return Optional.empty();
    }

    static final class Sequence implements Node, Workload.Continuous {

        private final NodeSpec spec;
        private final Optional<Stances> common;
        private final Clock clock;

        Sequence(Call call) {
            var writes = new ArrayList<Write>();
            Optional<Stances> stance = Optional.of(Stances.WHEREVER);
            Rig rig = call.rig().orElseThrow(WriteSequence::invalid);
            var steps = steps(call.arguments());
            for (Step step : steps) {
                for (String name : step.controls()) {
                    Rig.Part part = rig.part(name).orElseThrow(WriteSequence::invalid);
                    stance = stance.flatMap(shared -> common(shared, part.stances()));
                }
                if (step.amount() == null) {
                    Rig.Part part = rig.part(step.controls().getFirst()).orElseThrow(WriteSequence::invalid);
                    if (!(part.kind() instanceof Rig.Signalled signal) || signal.type().check(step.value()).isPresent()) {
                        throw invalid();
                    }
                    writes.add(new Write(part.ref(), step.value(), step.tick()));
                } else {
                    var transfer = new PostTransfer(itemPort(rig, step.from()), itemPort(rig, step.into()),
                        List.of(step.amount()), false);
                    writes.add(new Write(null, null, step.tick(), transfer));
                }
            }
            common = stance;
            Map<ControlRef, Value> stops = new LinkedHashMap<>();
            if (call.arguments().containsKey("on_interrupt")) {
                DefinitionCodec.fields(call.arguments().get("on_interrupt")).forEach((name, value) -> {
                    Rig.Part part = rig.part(name).orElseThrow(WriteSequence::invalid);
                    if (!controls(writes).contains(part.ref()) || !(part.kind() instanceof Rig.Signalled signal)
                            || value instanceof Value.Pulse || signal.type().check(value).isPresent()) {
                        throw invalid();
                    }
                    stops.put(part.ref(), value);
                });
            }
            clock = new Clock(writes, Map.copyOf(stops), false);
            spec = NodeSpec.of(call.id(), call.owner(), new WorkSite.AtBlock(call.site()),
                common.orElse(Stances.WHEREVER), this).effort(WorkEffort.NONE)
                .doing(Doings.OPERATING, steps.stream().flatMap(step -> step.controls().stream()).distinct()
                    .reduce((first, second) -> first + " / " + second))
                .done();
        }

        private static ControlRef itemPort(Rig rig, String name) {
            if (name == null) {
                return null;
            }
            var part = rig.part(name).orElseThrow(WriteSequence::invalid);
            if (!(part.kind() instanceof Rig.Ported port) || !port.resource().equals(ResourceKind.ITEM)) {
                throw invalid();
            }
            return part.ref();
        }

        void validate(ServerLevel level, List<Post> posts) {
            boolean needsPost = clock.writes.stream().anyMatch(write ->
                write.transfer() == null && requiredPost(level, write.ref()).isPresent());
            if ((needsPost || common.isEmpty()) && covering(level, posts, clock.writes).isEmpty()) {
                throw new IllegalArgumentException(FacilityRefusal.SEQUENCE_STANCE.translationKey());
            }
        }

        boolean local() {
            return common.isPresent();
        }

        List<Write> writes() {
            return clock.writes;
        }

        Clock clock() {
            return new Clock(clock.writes, clock.stops, false);
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public boolean mountedWork() {
            return spec.stances() instanceof Stances.Seat;
        }

        @Override
        public boolean staysAtWorkSite() {
            return true;
        }

        @Override
        public boolean admits(ServerLevel level, Mob body) {
            return controls(clock.writes).stream().allMatch(ref ->
                Controls.resolve(level, ref).filter(control -> control.admits(body)).isPresent());
        }

        @Override
        public boolean holds(ServerLevel level, Worker worker) {
            if (!local()) {
                clock.failure = FacilityRefusal.SEQUENCE_STANCE;
                return false;
            }
            return clock.tick(level, worker, worker.placement() == null ? null : worker.placement().reservations(),
                List.of());
        }

        @Override
        public Outcome commit(ServerLevel level, Worker worker) {
            return clock.failure == null ? Outcome.done()
                : Outcome.failed(new Routine.Failure(clock.failure.translationKey(), false));
        }

        @Override
        public void released(ServerLevel level, Worker worker, Ending ending) {
            clock.close(level);
        }
    }

    static Optional<Signal> signal(ServerLevel level, ControlRef ref) {
        return Controls.resolve(level, ref).filter(Signal.class::isInstance).map(Signal.class::cast)
            .filter(signal -> signal.valid(level));
    }

    static final class Clock {

        final List<Write> writes;
        private final Map<ControlRef, Value> stops;
        private final Set<ControlRef> written = new LinkedHashSet<>();
        private final boolean untilRead;
        private final Map<Object, Lease> leases = new LinkedHashMap<>();
        private final List<Lease> taken = new ArrayList<>();
        private long start = -1;
        private Worker writer;
        private int next;
        RefusalKind failure;

        Clock(List<Write> writes, boolean untilRead) {
            this(writes, Map.of(), untilRead);
        }

        Clock(List<Write> writes, Map<ControlRef, Value> stops, boolean untilRead) {
            this.writes = List.copyOf(writes);
            this.stops = stops;
            this.untilRead = untilRead;
        }

        private boolean acquire(ServerLevel level, Worker worker) {
            for (Write write : writes) {
                if (write.transfer() != null) {
                    continue;
                }
                Signal signal = signal(level, write.ref()).orElse(null);
                if (signal == null) {
                    return fail(FacilityRefusal.GONE);
                }
                if (!signal.admits(worker.body())) {
                    return fail(FacilityRefusal.REFUSED);
                }
                if (leases.containsKey(signal.leaseKey())) {
                    continue;
                }
                var existing = Lease.held(signal);
                var lease = Lease.take(signal, worker);
                if (lease.isEmpty()) {
                    return fail(FacilityRefusal.BUSY);
                }
                leases.put(signal.leaseKey(), lease.get());
                if (existing.isEmpty()) {
                    taken.add(lease.get());
                }
            }
            return true;
        }

        boolean tick(ServerLevel level, Worker worker, ReservationPool reservations, List<ReservedInput> reserved) {
            long now = level.getGameTime();
            if (failure != null || next == writes.size()) {
                return false;
            }
            if (start < 0) {
                if (!acquire(level, worker)) {
                    return false;
                }
                start = now;
                writer = worker;
            }
            if (writer.body() != worker.body() || leases.values().stream().anyMatch(lease -> !lease.open())) {
                return fail(FacilityRefusal.SEQUENCE_INTERRUPTED);
            }
            while (next < writes.size() && now - start >= writes.get(next).tick()) {
                Write write = writes.get(next++);
                if (!untilRead && now - start != write.tick()) {
                    return fail(FacilityRefusal.SEQUENCE_INTERRUPTED);
                }
                if (write.transfer() != null) {
                    var refused = write.transfer().move(level, worker, reservations, reserved);
                    if (refused != null) {
                        return fail(refused);
                    }
                    continue;
                }
                Signal signal = signal(level, write.ref()).orElse(null);
                if (signal == null || !leases.containsKey(signal.leaseKey())) {
                    return fail(FacilityRefusal.GONE);
                }
                if (signal.check(write.value()).isPresent()) {
                    return fail(FacilityRefusal.REFUSED);
                }
                switch (signal.write(level, worker.body(), write.value())) {
                    case BUSY -> {
                        return fail(FacilityRefusal.BUSY);
                    }
                    case REFUSED -> {
                        return fail(FacilityRefusal.REFUSED);
                    }
                    case WRITTEN -> {
                        if (stops.containsKey(write.ref())) {
                            written.add(write.ref());
                        }
                    }
                }
                if (untilRead && signal.commandFeedback().flatMap(reading -> reading.read(level))
                        .filter(value -> signal.type().agrees(write.value(), value)).isEmpty()) {
                    if (now - start >= 200) {
                        return fail(FacilityRefusal.UNSETTLED);
                    }
                    next--;
                    return true;
                }
            }
            if (next < writes.size()) {
                Write upcoming = writes.get(next);
                if (upcoming.value() instanceof Value.Pulse) {
                    signal(level, upcoming.ref()).ifPresent(pulse -> pulse.preparePulse(level, worker.body()));
                }
            }
            return next < writes.size();
        }

        private boolean fail(RefusalKind why) {
            failure = why;
            return false;
        }

        void close(ServerLevel level) {
            if (failure != null || next < writes.size()) {
                written.forEach(ref -> signal(level, ref)
                    .filter(signal -> Optional.ofNullable(leases.get(signal.leaseKey())).filter(Lease::open).isPresent())
                    .ifPresent(signal -> signal.write(level, writer.body(), stops.get(ref))));
            }
            written.clear();
            taken.forEach(Lease::close);
            taken.clear();
        }
    }
}
