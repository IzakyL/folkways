package io.github.izakyl.folkways.core.script.api;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.starlark.java.eval.AllocationBudget;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.syntax.DefStatement;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.Program;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.SyntaxError;

public final class ScriptRuntime {

    // Source equality resolves hash collisions. A Program also contains its file name and resolved names.
    private record Key(ResourceLocation id, String source, Set<String> names, boolean autoIndent) {
    }

    private static final Cache<Key, Compiled> PROGRAMS = CacheBuilder.newBuilder().maximumSize(256).build();

    private final Profile profile;

    public ScriptRuntime(Profile profile) {
        this.profile = profile;
    }

    public <T> CompletableFuture<T> submit(Supplier<T> work) {
        try {
            return profile.lane().workers.submit(work, profile.time().toNanos(), TimeUnit.NANOSECONDS)
                .thenApply(result -> switch (result) {
                    case Workers.Done<T> done -> done.value();
                    case Workers.Timeout<T> timeout -> throw new ScriptFault(ScriptFault.Kind.TIMEOUT, "", null);
                    case Workers.Failed<T> failed -> throw fault(failed.cause());
                });
        } catch (RejectedExecutionException busy) {
            throw new ScriptFault(ScriptFault.Kind.BUSY, "Script lane is busy", null);
        }
    }

    public static <T> T result(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException | CancellationException failure) {
            throw fault(failure);
        }
    }

    public static ScriptFault fault(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ScriptFault fault) {
                return fault;
            }
            if (cause instanceof AllocationBudget.Exceeded exceeded) {
                return new ScriptFault(ScriptFault.Kind.MEMORY, exceeded.getMessage(), null, exceeded);
            }
            if (cause instanceof Workers.TimedOut) {
                return new ScriptFault(ScriptFault.Kind.TIMEOUT, "", null);
            }
            if (cause instanceof CancellationException || cause instanceof InterruptedException) {
                return new ScriptFault(ScriptFault.Kind.CANCELLED, "Execution interrupted", null, cause);
            }
        }
        if (failure instanceof CompletionException completion && completion.getCause() != null) {
            failure = completion.getCause();
        }
        return new ScriptFault(ScriptFault.Kind.EVAL, String.valueOf(failure.getMessage()), null, failure);
    }

    public static void checkTime() {
        Workers.checkTime();
    }

    public void checkSource(String source) {
        if (source.length() > profile.maxSource()) {
            throw new ScriptFault(ScriptFault.Kind.SOURCE_SIZE, Integer.toString(profile.maxSource()), null);
        }
    }

    public Compiled compile(ResourceLocation id, String source) {
        // The size cap is for what a script's author sends; the standard modules ship with the mod and may exceed it.
        if (!ScriptStd.isStandard(id)) {
            checkSource(source);
        }
        Key key = new Key(id, source, profile.predeclared().keySet(), profile.autoIndent());
        Compiled compiled = PROGRAMS.getIfPresent(key);
        if (compiled == null) {
            compiled = compile(key);
            PROGRAMS.put(key, compiled);
        }
        if (!profile.allowsLoad() && compiled.load != null) {
            throw new ScriptFault(ScriptFault.Kind.LOAD, "", compiled.load);
        }
        return compiled;
    }

    private Compiled compile(Key key) {
        ScriptIndent.Wrapped wrapped = null;
        try {
            StarlarkFile file = StarlarkFile.parse(ParserInput.fromString(key.source(), key.id().toString()));
            boolean parsed = file.ok();
            Program program;
            try {
                program = Program.compileFile(file, module(key.id()));
            } catch (SyntaxError.Exception invalid) {
                wrapped = parsed && key.autoIndent() ? ScriptIndent.wrap(key.source(), file, invalid) : null;
                if (wrapped == null) {
                    throw invalid;
                }
                file = StarlarkFile.parse(ParserInput.fromString(wrapped.source(), key.id().toString()));
                program = Program.compileFile(file, module(key.id()));
            }
            Set<String> functions = new LinkedHashSet<>();
            Location load = null;
            for (var statement : file.getStatements()) {
                if (statement instanceof DefStatement def) {
                    functions.add(def.getIdentifier().getName());
                } else if (statement instanceof LoadStatement importing && load == null) {
                    load = importing.getStartLocation();
                }
            }
            return new Compiled(key.id(), program, wrapped, Set.copyOf(functions), load);
        } catch (SyntaxError.Exception invalid) {
            SyntaxError error = invalid.errors().getFirst();
            throw new ScriptFault(ScriptFault.Kind.SYNTAX, error.message(), original(wrapped, error.location()),
                invalid);
        } catch (StackOverflowError invalid) {
            throw new ScriptFault(ScriptFault.Kind.SYNTAX, "nesting", null, invalid);
        }
    }

    public Loaded load(ResourceLocation id, String source, Function<ResourceLocation, Optional<String>> libraries) {
        try (Mutability scope = Mutability.create("script", id)) {
            Map<ResourceLocation, Module> loaded = new LinkedHashMap<>();
            Module root = withMemory(() -> execute(compile(id, source), libraries, scope, loaded,
                new LinkedHashSet<>(), (module, thread) -> module, newBudget()));
            return new Loaded(root, loaded);
        }
    }

    public <T> T evaluate(Compiled compiled, Evaluation<T> work) {
        return evaluate(compiled, newBudget(), work);
    }

    public <T> T run(ResourceLocation id, Work<T> work) {
        try (Mutability scope = Mutability.create("script", id)) {
            StarlarkThread thread = thread(id, scope);
            return withMemory(() -> run(thread, null, newBudget(), () -> work.run(thread)));
        }
    }

    public BindingBudget newBudget() {
        return new BindingBudget(profile.calls());
    }

    public <T> T evaluate(Compiled compiled, BindingBudget budget, Evaluation<T> work) {
        return evaluate(compiled, budget, ignored -> Optional.empty(), work);
    }

    public <T> T evaluate(Compiled compiled, BindingBudget budget,
            Function<ResourceLocation, Optional<String>> libraries, Evaluation<T> work) {
        try (Mutability scope = Mutability.create("script", compiled.id)) {
            return withMemory(() -> execute(compiled, libraries, scope, new LinkedHashMap<>(),
                new LinkedHashSet<>(), work, budget));
        }
    }

    // A run is synchronous on its executing thread. Start measuring there, after any worker hand-off.
    // Library evaluation recurses within the same scope, so it shares both budgets.
    private <T> T withMemory(Supplier<T> work) {
        try (var memory = AllocationBudget.open(profile.allocationBytes(), profile.cumulativeAllocationBytes())) {
            T result = work.get();
            AllocationBudget.checkNow();
            return result;
        } catch (AllocationBudget.Exceeded exceeded) {
            throw fault(exceeded);
        }
    }

    private Module module(ResourceLocation id) {
        Map<String, Object> natives = ScriptStd.bindings(id);
        if (natives.isEmpty()) {
            return Module.withPredeclared(profile.semantics(), profile.predeclared());
        }
        return Module.withPredeclared(profile.semantics(), natives);
    }

    private StarlarkThread thread(ResourceLocation id, Mutability scope) {
        StarlarkThread thread = new StarlarkThread(scope, profile.semantics());
        long steps = profile.steps();
        if (steps > 0) {
            thread.setMaxExecutionSteps(steps);
            thread.setThreadLocal(ScriptSteps.class, new ScriptSteps(steps));
        }
        thread.setPrintHandler((printing, message) -> profile.print().accept(id, message));
        return thread;
    }

    private <T> T execute(Compiled compiled, Function<ResourceLocation, Optional<String>> libraries,
            Mutability scope, Map<ResourceLocation, Module> loaded, Set<ResourceLocation> loading,
            Evaluation<T> work, BindingBudget budget) {
        ResourceLocation id = compiled.id;
        loading.add(id);
        StarlarkThread thread = thread(id, scope);
        thread.setLoader(written -> {
            ResourceLocation wanted = profile.allowsLoad() ? profile.libraryResolver().apply(written) : null;
            if (wanted == null) {
                return null;
            }
            Module done = loaded.get(wanted);
            if (done != null) {
                return done;
            }
            if (loading.size() >= 32) {
                throw new ScriptFault(ScriptFault.Kind.LOAD, "Library nesting exceeds 32 modules", null);
            }
            if (loading.contains(wanted)) {
                throw new ScriptFault(ScriptFault.Kind.LOAD,
                    id + " loads " + wanted + ", which is still loading: a load cycle", null);
            }
            if (ScriptStd.routineOnly(wanted) && !profile.predeclared().containsKey("write_sequence")) {
                throw new ScriptFault(ScriptFault.Kind.LOAD, written + " works only inside a facility routine's "
                    + "source; trial the routine with probe(routine=...)", null);
            }
            Optional<String> text = ScriptStd.source(wanted).or(() -> libraries.apply(wanted));
            if (text.isEmpty()) {
                return null;
            }
            try {
                Module library = execute(compile(wanted, text.get()), libraries, scope, loaded, loading,
                    (module, libraryThread) -> module, budget);
                loaded.put(wanted, library);
                return library;
            } catch (ScriptFault failed) {
                var kind = profile.lane() == Profile.Lane.DRAFT_PATTERN || failed.kind() == ScriptFault.Kind.STEPS
                    || failed.kind() == ScriptFault.Kind.MEMORY
                    ? failed.kind() : ScriptFault.Kind.LOAD;
                throw new ScriptFault(kind, wanted + ": " + failed.detail(), failed.location(), failed);
            }
        });
        Module module = module(id);
        return run(thread, compiled, budget, () -> {
            Starlark.execFileProgram(compiled.program, module, thread);
            loading.remove(id);
            return work.run(module, thread);
        });
    }

    private <T> T run(StarlarkThread thread, Compiled compiled, BindingBudget budget, StarlarkWork<T> work) {
        try {
            return work.run();
        } catch (EvalException broken) {
            if (broken.getCause() != null && fault(broken.getCause()).kind() == ScriptFault.Kind.MEMORY) {
                throw fault(broken.getCause());
            }
            Location location = broken.getCallStack().stream().map(entry -> entry.location)
                .filter(at -> at.line() > 0).reduce((before, after) -> after)
                .orElse(broken.getDeprecatedLocation());
            ScriptSteps steps = thread.getThreadLocal(ScriptSteps.class);
            ScriptFault.Kind kind = budget.exceeded ? ScriptFault.Kind.CALLS
                : steps != null && steps.remaining(thread) <= 0 ? ScriptFault.Kind.STEPS
                : broken.getMessage().startsWith("file ") && broken.getMessage().contains(" was not correctly loaded")
                    ? ScriptFault.Kind.LOAD : ScriptFault.Kind.EVAL;
            throw new ScriptFault(kind, broken.getMessage(),
                original(compiled == null ? null : compiled.wrapped, location), broken);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ScriptFault(ScriptFault.Kind.CANCELLED, "Execution interrupted", null, interrupted);
        } catch (RuntimeException broken) {
            throw fault(broken);
        }
    }

    private static Location original(ScriptIndent.Wrapped wrapped, Location location) {
        return wrapped == null ? location : wrapped.original(location);
    }

    public static final class Compiled {

        private final ResourceLocation id;
        private final Program program;
        private final ScriptIndent.Wrapped wrapped;
        private final Set<String> functions;
        private final Location load;

        private Compiled(ResourceLocation id, Program program, ScriptIndent.Wrapped wrapped, Set<String> functions,
                Location load) {
            this.id = id;
            this.program = program;
            this.wrapped = wrapped;
            this.functions = functions;
            this.load = load;
        }

        public boolean definesFunction(String name) {
            return functions.contains(name);
        }

        public void requireFunction(String name) {
            if (!definesFunction(name)) {
                throw new ScriptFault(ScriptFault.Kind.FUNCTION, name, null);
            }
        }
    }

    public static final class BindingBudget {

        private final int limit;
        private int count;
        private boolean exceeded;

        private BindingBudget(int limit) {
            this.limit = limit;
        }

        public void charge() throws EvalException {
            AllocationBudget.checkNow();
            if (limit > 0 && ++count > limit) {
                exceeded = true;
                throw Starlark.errorf("Binding call limit exceeded (%d)", limit);
            }
        }
    }

    // Libraries follow their dependencies in completion order. The root is kept apart.
    public record Loaded(Module module, Map<ResourceLocation, Module> libraries) {

        public Loaded {
            libraries = Collections.unmodifiableMap(new LinkedHashMap<>(libraries));
        }
    }

    @FunctionalInterface
    public interface Work<T> {

        T run(StarlarkThread thread) throws EvalException, InterruptedException;
    }

    @FunctionalInterface
    public interface Evaluation<T> {

        T run(Module module, StarlarkThread thread) throws EvalException, InterruptedException;
    }

    @FunctionalInterface
    private interface StarlarkWork<T> {

        T run() throws EvalException, InterruptedException;
    }
}
