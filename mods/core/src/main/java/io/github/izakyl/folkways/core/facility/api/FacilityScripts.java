package io.github.izakyl.folkways.core.facility.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.script.api.Profile;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.script.api.ScriptValues;
import io.github.izakyl.folkways.core.work.api.Amount;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;
import net.starlark.java.syntax.Location;

final class FacilityScripts {

    static final int CONDITION_ADMISSIONS = 32;
    private static final int MAX_ACTIONS = 32;
    private static final ResourceLocation FILE = ResourceLocation.fromNamespaceAndPath("folkways_core", "facility");
    private static final Gson GSON = new Gson();
    private static final StarlarkCallable DONE = action("done", 0);
    private static final Map<String, Object> ACTIONS = Map.of(
        "write", action("write", 2), "write_sequence", action("write_sequence", 1), "await", action("await", 3), "run", action("run", 2),
        "deliver", action("deliver", 2), "collect", action("collect", 2),
        "fail", action("fail", 1), "fail_permanent", action("fail_permanent", 1), "done", DONE,
        "recipes_of", WorkshopRecipeSnapshot.BUILTIN);

    private static final ScriptRuntime CONDITION = new ScriptRuntime(Profile.facilityCondition());
    private static final ScriptRuntime STEP = new ScriptRuntime(Profile.facilityStep(ACTIONS));

    private FacilityScripts() {
    }

    record Source(String text, String function) {
    }

    record Compiled(Source source, ScriptRuntime runtime, ScriptRuntime.Compiled program) {

        <T> T hook(String name, Map<String, Value> readings, Map<String, Value> args,
                Map<String, String> libraries, Function<Object, T> decode) {
            return hook(name, readings, args, libraries, decode, null);
        }

        <T> T hook(String name, Map<String, Value> readings, Map<String, Value> args,
                Map<String, String> libraries, Function<Object, T> decode, FacilityScriptContexts.Taken shots) {
            return runtime.evaluate(program, runtime.newBudget(), id ->
                Optional.ofNullable(libraries.get(ColonyScripts.name(id))), (module, thread) -> {
                if (shots != null) {
                    shots.install(thread);
                }
                var values = ScriptValues.facility();
                var options = FacilityScriptData.snapshot(values, args, thread.mutability());
                var input = FacilityScriptData.snapshot(values, readings, thread.mutability());
                List<Object> arguments = switch (name) {
                    case "check" -> List.of(options);
                    case "when", "keepout" -> List.of(input);
                    default -> List.of(input, options);
                };
                Object result = Starlark.call(thread, module.getGlobal(name), arguments, Map.of());
                try {
                    return decode.apply(GSON.fromJson(values.write(result), Object.class));
                } catch (IllegalArgumentException invalid) {
                    throw Starlark.errorf("invalid %s result: %s", name, invalid.getMessage());
                }
            });
        }

        ConditionResult matches(Map<String, Value> readings, boolean profile, Map<String, String> libraries) {
            long started = profile ? System.nanoTime() : 0;
            return runtime.evaluate(program, runtime.newBudget(), id ->
                Optional.ofNullable(libraries.get(ColonyScripts.name(id))), (module, thread) -> {
                Object result = Starlark.call(thread, module.getGlobal(source.function()),
                    List.of(FacilityScriptData.snapshot(ScriptValues.facility(), readings, thread.mutability())),
                    Map.of());
                if (result instanceof Boolean matches) {
                    return new ConditionResult(matches, thread.getExecutedSteps(),
                        profile ? System.nanoTime() - started : 0);
                }
                throw Starlark.errorf("when must return a bool");
            });
        }

        List<FacilityWorkshop.Batch> recipes(Map<String, Value> readings,
                Map<String, String> libraries, WorkshopRecipeSnapshot recipes, ResourceLocation station) {
            return runtime.evaluate(program, runtime.newBudget(), id ->
                Optional.ofNullable(libraries.get(ColonyScripts.name(id))), (module, thread) -> {
                Object function = module.getGlobal("recipes");
                thread.setThreadLocal(WorkshopRecipeSnapshot.class, recipes);
                var values = ScriptValues.facility();
                Object result = Starlark.call(thread, function,
                    List.of(FacilityScriptData.snapshot(values, readings, thread.mutability())), Map.of());
                try {
                    var output = ScriptValues.facilityRecipes().write(result);
                    var rows = DefinitionCodec.scriptRecipes(GSON.fromJson(output, Object.class), recipes.items());
                    return FacilityWorkshop.batches(rows, station, recipes.items());
                } catch (IllegalArgumentException invalid) {
                    throw Starlark.errorf("invalid workshop recipes: %s", invalid.getMessage());
                }
            });
        }

        Step step(String state, Map<String, Value> readings, Map<String, Value> args, String failure,
                Map<String, String> libraries, Failure handler) {
            return step(state, readings, args, failure, libraries, handler, Map.of());
        }

        Step step(String state, Map<String, Value> readings, Map<String, Value> args, String failure,
                Map<String, String> libraries, Failure handler, Map<String, Object> context) {
            return step(state, readings, args, failure, libraries, handler, context, null);
        }

        Step step(String state, Map<String, Value> readings, Map<String, Value> args, String failure,
                Map<String, String> libraries, Failure handler, Map<String, Object> context,
                FacilityScriptContexts.Taken shots) {
            return runtime.evaluate(program, runtime.newBudget(), id ->
                Optional.ofNullable(libraries.get(ColonyScripts.name(id))), (module, thread) -> {
                if (shots != null) {
                    shots.install(thread);
                }
                JsonElement plain = state == null ? JsonNull.INSTANCE : JsonParser.parseString(state);
                var decoder = ScriptValues.facility();
                var input = FacilityScriptData.readings(decoder, readings, thread.mutability());
                input.putEntry("_failure", failure == null ? Starlark.NONE : failure);
                Object function = module.getGlobal(handler == null ? source.function() : handler.function());
                if (function == null) {
                    throw Starlark.errorf("unknown failure handler %s", handler.function());
                }
                List<Object> arguments = new ArrayList<>();
                arguments.add(ScriptValues.readFacility(plain, thread.mutability()));
                if (handler != null) {
                    arguments.add(Dict.copyOf(thread.mutability(), Map.of(
                        "action_index", StarlarkInt.of(handler.index()),
                        "kind", handler.kind(), "reason", handler.reason(), "retryable", handler.retryable())));
                }
                arguments.add(input);
                var options = FacilityScriptData.snapshot(decoder, args, thread.mutability());
                for (var entry : decoder.snapshot(context, value -> value, thread.mutability()).entrySet()) {
                    options.putEntry(entry.getKey(), entry.getValue());
                }
                arguments.add(options);
                Object result = Starlark.call(thread, function, arguments, Map.of());
                if (!(result instanceof Tuple pair) || pair.size() != 2
                        || !(pair.get(1) instanceof Sequence<?> actions) || actions.size() > MAX_ACTIONS) {
                    throw Starlark.errorf("step must return (state, actions) with at most %d actions", MAX_ACTIONS);
                }
                List<Action> batch = new ArrayList<>();
                var encoder = ScriptValues.facility();
                for (Object item : actions) {
                    if (item instanceof Tuple wrapped && wrapped.size() == 2 && wrapped.getFirst() instanceof Tuple action
                            && wrapped.get(1) instanceof String handlerName) {
                        batch.add(new WithHandler(decode(action, encoder), handlerName));
                    } else {
                        batch.add(decode(item, encoder));
                    }
                }
                if (batch.size() != 1 && batch.stream().anyMatch(action -> action.base() instanceof Terminal)) {
                    throw Starlark.errorf("done and fail must be the only action of their step");
                }
                return new Step(ScriptValues.encodeFacility(encoder.write(pair.get(0))), List.copyOf(batch));
            });
        }
    }

    static Compiled compile(Source source) {
        ScriptRuntime runtime = source.function().equals("step") ? STEP : CONDITION;
        ScriptRuntime.Compiled program = runtime.compile(FILE, source.text());
        program.requireFunction(source.function());
        return new Compiled(source, runtime, program);
    }

    static <T> CompletableFuture<T> submit(boolean condition, Supplier<T> work) {
        try {
            return (condition ? CONDITION : STEP).submit(work);
        } catch (ScriptFault busy) {
            return null;
        }
    }

    static String code(ScriptFault fault) {
        return switch (fault.kind()) {
            case SYNTAX -> "script_syntax";
            case STEPS -> "script_steps";
            case MEMORY -> "memory_limit";
            case TIMEOUT, CANCELLED -> "script_timeout";
            case SOURCE_SIZE -> "script_size";
            case LOAD -> "script_load";
            case FUNCTION -> "script_function";
            case EVAL, CALLS, RESULT_SIZE, BUSY -> "script_eval";
        };
    }

    static String detail(ScriptFault fault) {
        if (fault.kind() == ScriptFault.Kind.SYNTAX && fault.location() != null) {
            Location location = fault.location();
            return Location.fromFileLineColumn("facility", location.line(), location.column()) + ": " + fault.detail();
        }
        return fault.detail();
    }

    private static StarlarkCallable action(String name, int arity) {
        return new StarlarkCallable() {

            @Override
            public String getName() {
                return name;
            }

            @Override
            public Object fastcall(StarlarkThread thread, Object[] positional, Object[] named) throws EvalException {
                boolean all = name.equals("collect") && positional.length == 1;
                if (positional.length != arity && !all) {
                    throw Starlark.errorf("%s expects %d positional arguments", name, arity);
                }
                List<Object> fields = new ArrayList<>(arity + 1);
                fields.add(name);
                fields.addAll(List.of(positional));
                if (all) {
                    fields.add(Starlark.NONE);
                }
                String function = null;
                for (int i = 0; i < named.length; i += 2) {
                    if (named[i].equals("within") && name.equals("await")) {
                        fields.add(named[i + 1]);
                    } else if (named[i].equals("on_fail") && named[i + 1] instanceof String handler
                            && handler.matches("[A-Za-z_][A-Za-z_0-9]{0,127}")) {
                        function = handler;
                    } else {
                        throw Starlark.errorf("%s accepts on_fail as a function name%s", name,
                            name.equals("await") ? " and within as a tolerance" : "");
                    }
                }
                Tuple tuple = Tuple.copyOf(fields);
                return function == null ? tuple : Tuple.of(tuple, function);
            }
        };
    }

    private static Action decode(Object input, ScriptValues encoder) throws EvalException {
        if (input == DONE) {
            return new Terminal(null, false);
        }
        if (!(input instanceof Tuple tuple) || tuple.isEmpty() || !(tuple.getFirst() instanceof String kind)) {
            throw Starlark.errorf("invalid facility action");
        }
        try {
            return switch (kind) {
                case "done" -> {
                    if (tuple.size() != 1) {
                        throw Starlark.errorf("invalid done");
                    }
                    yield new Terminal(null, false);
                }
                case "fail", "fail_permanent" -> new Terminal(name(tuple, 2), kind.equals("fail"));
                case "write_sequence" -> {
                    if (tuple.size() != 2) {
                        throw Starlark.errorf("write_sequence expects one argument");
                    }
                    var steps = GSON.fromJson(encoder.write(tuple.get(1)), Object.class);
                    var field = new DefinitionCodec.Field("steps", steps);
                    yield new Library(Routines.WRITE_SEQUENCE, Map.of("steps",
                        DefinitionRoutineCodec.routineArgument(field).resolve(Map.of())));
                }
                case "write" -> new Write(name(tuple, 3), value(tuple.get(2), encoder));
                case "await" -> {
                    String reading = name(tuple, tuple.size() == 5 ? 5 : 4);
                    int timeout = Starlark.toInt(tuple.get(3), "timeout");
                    if (timeout < 1 || timeout > 24_000) {
                        throw Starlark.errorf("await timeout must be between 1 and 24000 ticks");
                    }
                    double within = 0;
                    if (tuple.size() == 5) {
                        within = tuple.get(4) instanceof StarlarkInt whole ? whole.toDouble()
                            : tuple.get(4) instanceof net.starlark.java.eval.StarlarkFloat real ? real.toDouble() : -1;
                        if (!(within >= 0) || !Double.isFinite(within)) {
                            throw Starlark.errorf("await within must be a tolerance of at least 0");
                        }
                    }
                    yield new Await(reading, value(tuple.get(2), encoder), timeout, within);
                }
                case "deliver", "collect" -> {
                    String port = name(tuple, 3);
                    boolean collect = kind.equals("collect");
                    boolean all = collect && tuple.get(2) == Starlark.NONE;
                    List<Amount> items = all ? List.of()
                        : DefinitionCodec.scriptItems(GSON.fromJson(encoder.write(tuple.get(2)), Object.class));
                    yield new Items(collect, all, port, items);
                }
                case "run" -> {
                    ResourceLocation routine = ResourceLocation.tryParse(name(tuple, 3));
                    if (routine == null || routine.equals(Routines.SCRIPT)) {
                        throw Starlark.errorf("run requires a library routine id");
                    }
                    if (!(tuple.get(2) instanceof Map<?, ?>)) {
                        throw Starlark.errorf("run requires a dict of arguments");
                    }
                    Map<String, Value> arguments = new LinkedHashMap<>();
                    for (var each : encoder.write(tuple.get(2)).getAsJsonObject().entrySet()) {
                        arguments.put(each.getKey(), DefinitionCodec.argument(GSON.fromJson(each.getValue(), Object.class)));
                    }
                    yield new Library(routine, Map.copyOf(arguments));
                }
                default -> throw Starlark.errorf("unknown facility action");
            };
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("invalid %s action argument", kind);
        }
    }

    private static String name(Tuple tuple, int size) throws EvalException {
        if (tuple.size() != size || !(tuple.get(1) instanceof String name) || name.length() > 256) {
            throw Starlark.errorf("invalid action name or reason");
        }
        return name;
    }

    private static Value value(Object value, ScriptValues encoder) throws EvalException {
        return DefinitionCodec.argument(GSON.fromJson(encoder.write(value), Object.class));
    }

    record ConditionResult(boolean matches, long steps, long nanos) {
    }

    record Step(String state, List<Action> actions) {
    }

    record Failure(String function, int index, String kind, String reason, boolean retryable) {
    }

    record WithHandler(Action base, String function) implements Action {
    }

    sealed interface Action permits Write, Await, Library, Terminal, WithHandler, Items {
        default Action base() {
            return this;
        }
    }

    static String kind(Action action) {
        return switch (action.base()) {
            case Write ignored -> "write";
            case Await ignored -> "await";
            case Library library -> library.routine().equals(Routines.WRITE_SEQUENCE) ? "write_sequence" : "run";
            case Terminal ignored -> "terminal";
            case Items items -> items.collect() ? "collect" : "deliver";
            case WithHandler ignored -> throw new IllegalArgumentException("nested action wrapper");
        };
    }

    record Write(String control, Value value) implements Action {
    }

    /** Waits for a reading to agree with a value: by the signal's own tolerance, or each number within {@code within}. */
    record Await(String reading, Value value, int timeout, double within) implements Action {

        Await(String reading, Value value, int timeout) {
            this(reading, value, timeout, 0);
        }
    }

    record Library(ResourceLocation routine, Map<String, Value> arguments) implements Action {
    }

    record Items(boolean collect, boolean all, String port, List<Amount> items) implements Action {
    }

    record Terminal(String failure, boolean retryable) implements Action {
    }
}
