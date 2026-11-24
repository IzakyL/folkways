package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.work.api.Amount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Runs facility logic on a caller-provided, isolated level. Tick and close on the server thread. */
public final class RoutineTrial implements AutoCloseable {

    private static final int LOG_LIMIT = 32;
    private static final int PATIENCE = 200;
    private static final String UNSETTLED = FacilityRefusal.UNSETTLED.translationKey();

    private final ServerLevel level;
    private final Predicate<BoundingBox> observed;
    private final FacilityDefinition facility;
    private final Map<String, Value> arguments;
    private final Map<String, String> libraries;
    private final FacilityScripts.Compiled program;
    private final BiFunction<Signal, Value, Signal.Write> writeSignal;
    private final List<Map<String, Object>> log = new ArrayList<>();
    private final Map<String, Long> collected = new LinkedHashMap<>();
    private List<FacilityScripts.Action> batch = List.of();
    private CompletableFuture<FacilityScripts.Step> pending;
    private FacilityScripts.Failure handler;
    private Work work;
    private String state;
    private String failure;
    private String ending;
    private String reason;
    private int steps;
    private int handled;
    private int index;
    private int started;

    @FunctionalInterface
    private interface Work {
        boolean tick(int age);
    }

    public RoutineTrial(ServerLevel level, FacilityDefinition facility, String routine, Map<String, Value> arguments,
            Map<String, String> libraries, BiFunction<Signal, Value, Signal.Write> writeSignal,
            Predicate<BoundingBox> observed) {
        this.level = level;
        this.observed = observed;
        this.writeSignal = writeSignal;
        this.facility = facility;
        this.libraries = Map.copyOf(libraries);
        Routine.Use use = facility.routines().get(routine);
        if (use == null) {
            throw new IllegalArgumentException("unknown_routine");
        }
        var args = use.resolve(facility.parameters()).arguments(arguments);
        if (use.routine().equals(Routines.SCRIPT)) {
            var source = FacilityScriptRuns.source(use);
            if (source == null) {
                throw new IllegalArgumentException("missing_script_source");
            }
            program = FacilityScripts.compile(source);
            args.remove("source");
        } else {
            program = null;
            batch = List.of(new FacilityScripts.Library(use.routine(), Map.copyOf(args)));
        }
        this.arguments = Map.copyOf(args);
    }

    /** World cells the trial must copy for the routine's target reading, including the border and the initial approach when it is observed. */
    public static Optional<BoundingBox> targetBounds(ServerLevel level, FacilityDefinition facility, String routine,
            Map<String, Value> arguments) {
        var use = facility.routines().get(routine);
        if (use == null || !use.routine().equals(Routines.SCRIPT)) {
            return Optional.empty();
        }
        var resolved = use.resolve(facility.parameters()).arguments(arguments);
        return FacilityScriptRuns.targetBounds(level, resolved.get("target")).map(box ->
            FacilityScriptRuns.approachBounds(level, box, Readings.snapshot(level, facility.readings(level)), resolved)
                .orElse(box));
    }

    public boolean finished() {
        return ending != null;
    }

    public void tick(int tick) {
        if (finished()) {
            return;
        }
        if (index == batch.size()) {
            if (program == null) {
                end(failure == null ? "done" : "failed", failure);
                return;
            }
            if (!step()) {
                return;
            }
        }
        while (index < batch.size()) {
            var action = batch.get(index);
            try {
                if (work == null) {
                    started = tick;
                    record(tick, action.base());
                    work = start(action.base(), tick);
                }
                if (finished() || !work.tick(tick - started)) {
                    return;
                }
                work = null;
                index++;
            } catch (IllegalArgumentException refused) {
                actionFailed(action, refused.getMessage());
                return;
            }
        }
    }

    private boolean step() {
        if (pending == null) {
            var readings = FacilityScriptRuns.scriptInput(level, facility, arguments, observed);
            String previous = state;
            String failed = failure;
            var recover = handler;
            // A trial copy launches nothing, so no shot of it reaches the colony.
            var shots = FacilityScriptContexts.trial(program);
            pending = FacilityScripts.submit(false, () ->
                program.step(previous, readings, arguments, failed, libraries, recover, Map.of(), shots));
            return false;
        }
        if (!pending.isDone()) {
            return false;
        }
        try {
            var next = ScriptRuntime.result(pending);
            state = next.state();
            batch = next.actions();
            steps++;
            index = 0;
            handler = null;
            failure = null;
            return true;
        } catch (ScriptFault fault) {
            end("failed_permanent", FacilityScripts.code(fault) + ": " + FacilityScripts.detail(fault));
            return false;
        } finally {
            pending = null;
        }
    }

    private void actionFailed(FacilityScripts.Action action, String why) {
        failure = why;
        work = null;
        if (action instanceof FacilityScripts.WithHandler recover) {
            if (handled++ >= 16) {
                end("failed_permanent", why);
                return;
            }
            handler = new FacilityScripts.Failure(recover.function(), index, FacilityScripts.kind(action), why, true);
        }
        index = batch.size();
    }

    private Work start(FacilityScripts.Action action, int tick) {
        return switch (action) {
            case FacilityScripts.Write write -> writer(write.control(), write.value(), false);
            case FacilityScripts.Await await -> {
                ScriptAwait.validate(level, facility, await);
                yield age -> waitFor(ScriptAwait.matches(level, facility, await), age, await.timeout());
            }
            case FacilityScripts.Library library -> library(library.routine(), library.arguments(), tick);
            case FacilityScripts.Items items -> {
                transfer(items);
                yield age -> true;
            }
            case FacilityScripts.Terminal terminal -> {
                end(terminal.failure() == null ? "done" : terminal.retryable() ? "failed" : "failed_permanent",
                    terminal.failure());
                yield age -> true;
            }
            case FacilityScripts.WithHandler ignored -> throw new IllegalArgumentException("nested action handler");
        };
    }

    private Work library(ResourceLocation id, Map<String, Value> args, int tick) {
        if (!id.equals(Routines.WRITE_ALL) && !id.equals(Routines.MOVE_TO) && !id.equals(Routines.WRITE_SEQUENCE)) {
            throw new IllegalArgumentException("routine is unavailable in trials: " + id);
        }
        Routines.libraryRun(level, facility, id.toString(), id, args, DefinedFacilities.ID);
        if (id.equals(Routines.MOVE_TO)) {
            String drive = args.get("drive") instanceof Value.Text name ? name.value() : "destination";
            return writer(drive, args.get("target"), false);
        }
        if (id.equals(Routines.WRITE_ALL)) {
            var writes = args.entrySet().stream().map(entry -> writer(entry.getKey(), entry.getValue(), true))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            return age -> {
                writes.removeIf(write -> write.tick(age));
                return writes.isEmpty();
            };
        }
        var sequence = WriteSequence.steps(args);
        if (sequence.stream().anyMatch(step -> step.amount() != null)) {
            throw new IllegalArgumentException("write_sequence item transfers are unavailable in trials");
        }
        return new Work() {
            private int next;

            @Override
            public boolean tick(int age) {
                while (next < sequence.size() && sequence.get(next).tick() <= age) {
                    var step = sequence.get(next++);
                    String control = step.controls().getFirst();
                    log(tick + age, "write", control, DefinitionCodec.readout(step.value()));
                    write(signal(control), step.value());
                }
                return next == sequence.size();
            }
        };
    }

    private Work writer(String name, Value value, boolean confirm) {
        return new Work() {
            private boolean written;

            @Override
            public boolean tick(int age) {
                Signal signal = signal(name);
                boolean continuous = signal.cost() instanceof Control.Cost.UntilRead;
                var feedback = signal.commandFeedback();
                if ((written || continuous || confirm) && !(value instanceof Value.Pulse)
                        && feedback.flatMap(reading -> reading.read(level))
                            .filter(read -> signal.type().agrees(value, read)).isPresent()) {
                    return true;
                }
                if (!written || continuous) {
                    write(signal, value);
                    written = true;
                }
                return waitFor(!continuous && (!confirm || feedback.isEmpty() || value instanceof Value.Pulse),
                    age, PATIENCE);
            }
        };
    }

    private Signal signal(String name) {
        if (facility.resolve(level).get(name) instanceof Signal signal && signal.valid(level)) {
            return signal;
        }
        throw new IllegalArgumentException("no usable signal " + name);
    }

    private void write(Signal signal, Value value) {
        signal.check(value).ifPresent(reason -> {
            throw new IllegalArgumentException(reason);
        });
        var result = writeSignal.apply(signal, value);
        if (result != Signal.Write.WRITTEN) {
            throw new IllegalArgumentException(result == Signal.Write.BUSY
                ? FacilityRefusal.BUSY.translationKey() : FacilityRefusal.REFUSED.translationKey());
        }
    }

    private static boolean waitFor(boolean matched, int age, int timeout) {
        if (!matched && age >= timeout) {
            throw new IllegalArgumentException(UNSETTLED);
        }
        return matched;
    }

    private void transfer(FacilityScripts.Items action) {
        var control = facility.resolve(level).get(action.port());
        if (!(control instanceof Port<?> found) || !ResourceKind.ITEM.equals(found.resource()) || !found.valid(level)) {
            throw new IllegalArgumentException("no usable item port " + action.port());
        }
        @SuppressWarnings("unchecked")
        Port<ItemStack> port = (Port<ItemStack>) found;
        if (action.collect()) {
            collect(port, action);
        } else {
            for (Amount amount : action.items()) {
                ItemStack stack = Goods.display(amount.spec());
                if (stack.isEmpty()) {
                    throw new IllegalArgumentException("no item matches " + amount.spec().describe());
                }
                stack.setCount(Math.toIntExact(amount.count()));
                port.prepareDeposit(level, null, stack);
                if (port.deposit(level, null, stack, true).getCount() != stack.getCount()
                        || port.deposit(level, null, stack, false).getCount() != stack.getCount()) {
                    throw new IllegalArgumentException("item port could not accept the requested items");
                }
            }
        }
    }

    private void collect(Port<ItemStack> port, FacilityScripts.Items action) {
        var available = port.contents(level).stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
        List<ItemStack> selected = new ArrayList<>();
        if (action.all()) {
            selected.addAll(available);
        } else {
            for (Amount amount : action.items()) {
                long remaining = amount.count();
                for (ItemStack stack : available) {
                    if (remaining > 0 && !stack.isEmpty() && Goods.matches(stack, amount.spec())) {
                        int count = (int) Math.min(remaining, stack.getCount());
                        selected.add(stack.copyWithCount(count));
                        stack.shrink(count);
                        remaining -= count;
                    }
                }
                if (remaining > 0) {
                    throw new IllegalArgumentException("item port is missing requested items");
                }
            }
        }
        for (ItemStack stack : selected) {
            ItemStack drawn = port.draw(level, null, stack, false);
            if (!drawn.isEmpty()) {
                collected.merge(BuiltInRegistries.ITEM.getKey(drawn.getItem()).toString(),
                    (long) drawn.getCount(), Long::sum);
            }
            if (drawn.getCount() != stack.getCount()) {
                throw new IllegalArgumentException("item port could not supply the requested items");
            }
        }
    }

    private void record(int tick, FacilityScripts.Action action) {
        switch (action) {
            case FacilityScripts.Write write -> log(tick, "write", write.control(), DefinitionCodec.readout(write.value()));
            case FacilityScripts.Await await -> log(tick, "await", await.reading(), DefinitionCodec.readout(await.value()));
            case FacilityScripts.Library library -> log(tick, FacilityScripts.kind(action), null,
                library.routine().toString());
            case FacilityScripts.Items items -> log(tick, FacilityScripts.kind(action), items.port(), null);
            case FacilityScripts.Terminal terminal -> log(tick, terminal.failure() == null ? "done"
                : terminal.retryable() ? "fail" : "fail_permanent", null, terminal.failure());
            case FacilityScripts.WithHandler ignored -> { }
        }
    }

    private void log(int tick, String kind, String control, Object value) {
        if (log.size() < LOG_LIMIT) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tick", tick);
            row.put("kind", kind);
            if (control != null) {
                row.put("control", control);
            }
            if (value != null) {
                row.put("value", value);
            }
            log.add(row);
        }
    }

    private void end(String ending, String reason) {
        this.ending = ending;
        this.reason = reason;
    }

    public Map<String, Object> report() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ending", ending == null ? "timeout" : ending);
        if (reason != null) {
            result.put("reason", reason);
        }
        result.put("steps", steps);
        result.put("actions", List.copyOf(log));
        if (!collected.isEmpty()) {
            result.put("collected", Map.copyOf(collected));
        }
        return result;
    }

    @Override
    public void close() {
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
    }
}
