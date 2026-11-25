package io.github.izakyl.folkways.core.script.api;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ScriptGameTests {

    private static final ResourceLocation ROOT = id("root");
    private static final long STEPS = 10_000;
    private static final Workers WORKERS = new Workers("folkways-script-test", 1);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ScriptGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void refusesLargeAllocationsBeforeMaterializing(GameTestHelper helper) {
        ScriptRuntime scripts = new ScriptRuntime(profile(Map.of()).withMemoryLimits(64 * 1024, 256 * 1024));
        for (String expression : List.of("[0] * 100000", "'x' * 100000", "100000 * (0,)",
                "list(range(100000))", "' '.join(['x' * 600] * 100)", "'{0}{0}{0}'.format('x' * 10000)",
                "('x' * 10000) + ('y' * 10000)")) {
            String source = "value = " + expression;
            var compiled = scripts.compile(ROOT, source);
            try {
                scripts.evaluate(compiled, (module, thread) -> module.getGlobal("value"));
                helper.fail("expected a memory refusal for " + expression);
            } catch (ScriptFault expected) {
                helper.assertTrue(expected.kind() == ScriptFault.Kind.MEMORY
                    && expected.detail().contains("pre-allocation"), expression + ": " + expected.detail());
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void cumulativeAllocationStopsGradualGrowthAndResets(GameTestHelper helper) {
        ScriptRuntime scripts = new ScriptRuntime(profile(Map.of())
            .withLimits(1_000_000, Duration.ofSeconds(5), 0).withMemoryLimits(64L << 20, 1L << 20));
        var growing = scripts.compile(ROOT, """
            def grow():
                items = []
                for i in range(100000):
                    items.append({i: i})
                return items
            value = grow()
            """);
        try {
            scripts.evaluate(growing, (module, thread) -> module.getGlobal("value"));
            helper.fail("gradual allocation should hit the JVM allocation limit");
        } catch (ScriptFault expected) {
            helper.assertTrue(expected.kind() == ScriptFault.Kind.MEMORY
                && expected.detail().contains("cumulative JVM allocation"), expected.detail());
        }
        var small = scripts.compile(ROOT, "value = ','.join(['ok'] * 3)");
        Object value = scripts.evaluate(small, (module, thread) -> module.getGlobal("value"));
        helper.assertTrue("ok,ok,ok".equals(value), "a fresh budget permits an ordinary script");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void callbackAllocationCountsInsideJavaBuiltin(GameTestHelper helper) {
        StarlarkCallable callback = new StarlarkCallable() {
            @Override
            public String getName() {
                return "callback";
            }

            @Override
            public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
                    throws EvalException, InterruptedException {
                return Starlark.call(thread, args.get(0), List.of(), Map.of());
            }
        };
        ScriptRuntime scripts = new ScriptRuntime(profile(Map.of("callback", callback))
            .withLimits(1_000_000, Duration.ofSeconds(5), 0).withMemoryLimits(64L << 20, 1L << 20));
        var compiled = scripts.compile(ROOT, """
            def grow():
                return [{i: i} for i in range(100000)]
            value = callback(grow)
            """);
        try {
            scripts.evaluate(compiled, (module, thread) -> module.getGlobal("value"));
            helper.fail("callback allocation should hit the JVM allocation limit");
        } catch (ScriptFault expected) {
            helper.assertTrue(expected.kind() == ScriptFault.Kind.MEMORY
                && expected.detail().contains("cumulative JVM allocation"), expected.detail());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void diamondLibrariesCompleteOnceInDependencyOrder(GameTestHelper helper) throws Exception {
        ScriptRuntime scripts = scripts(Map.of());
        Map<ResourceLocation, String> sources = Map.of(
            id("left"), "load('test:leaf', 'value')\nleft = value\n",
            id("right"), "load('test:leaf', 'value')\nright = value\n",
            id("leaf"), "value = [1]\n");
        Map<ResourceLocation, Integer> reads = new LinkedHashMap<>();
        Function<ResourceLocation, Optional<String>> libraries = wanted -> {
            reads.merge(wanted, 1, Integer::sum);
            return Optional.ofNullable(sources.get(wanted));
        };
        String source = "load('test:left', 'left')\nload('test:right', 'right')\nvalue = left\n";
        ScriptRuntime.Loaded loaded = scripts.load(ROOT, source, libraries);
        helper.assertTrue(new ArrayList<>(loaded.libraries().keySet())
            .equals(List.of(id("leaf"), id("left"), id("right"))), "dependencies finish before their users");
        helper.assertTrue(reads.values().stream().allMatch(count -> count == 1), "each library is read once");
        helper.assertTrue(loaded.libraries().get(id("left")).getGlobal("left")
            == loaded.libraries().get(id("right")).getGlobal("right"), "the diamond shares one module value");
        try {
            loaded.libraries().clear();
            helper.fail("the returned library map must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // The result keeps its completion order after loading has ended.
        }
        ScriptRuntime.Loaded next = scripts.load(ROOT, source, libraries);
        helper.assertTrue(reads.values().stream().allMatch(count -> count == 2), "each load has a fresh cache");
        helper.assertTrue(next.module().getGlobal("value") != loaded.module().getGlobal("value"),
            "a later load has its own values");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unresolvedNamesAndMissingSourcesRefuseLoads(GameTestHelper helper) throws Exception {
        ScriptRuntime scripts = scripts(Map.of());
        for (String wanted : List.of("bad name", "test:missing")) {
            try {
                scripts.load(ROOT, "load('" + wanted + "', 'value')\n", missing -> {
                    helper.assertTrue(missing.equals(id("missing")), "unresolved names never ask for source");
                    return Optional.empty();
                });
                helper.fail("an unresolved load must fail");
            } catch (ScriptFault expected) {
                helper.assertTrue(expected.getMessage().contains(wanted), "the error names the missing library");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void loadCyclesKeepTheirMessage(GameTestHelper helper) throws Exception {
        try {
            scripts(Map.of()).load(ROOT, "load('test:other', 'value')\n", wanted ->
                Optional.of("load('test:root', 'value')\n"));
            helper.fail("a cycle must fail");
        } catch (ScriptFault expected) {
            helper.assertTrue(expected.kind() == ScriptFault.Kind.LOAD
                && expected.detail().contains("test:other loads test:root, which is still loading: a load cycle"),
                "the cycle names both libraries");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void eachModuleGetsItsOwnStepLimit(GameTestHelper helper) throws Exception {
        ScriptRuntime scripts = new ScriptRuntime(profile(Map.of()).withLimits(100, Duration.ofSeconds(5), 0));
        StringBuilder work = new StringBuilder("value = 1\n");
        for (int i = 0; i < 30; i++) {
            work.append("part").append(i).append(" = 1\n");
        }
        ScriptRuntime.Loaded loaded = scripts.load(ROOT, "load('test:other', other = 'value')\n" + work,
            wanted -> Optional.of((wanted.equals(id("leaf")) ? "" : "load('test:leaf', leaf = 'value')\n")
                + work));
        helper.assertTrue(loaded.libraries().size() == 2, "the limit starts afresh for each module");
        String tooMuch = "value = [i for i in range(10000)]\n";
        try {
            scripts.load(ROOT, tooMuch, missing -> Optional.empty());
            helper.fail("root execution must be bounded");
        } catch (ScriptFault expected) {
            helper.assertTrue(expected.kind() == ScriptFault.Kind.STEPS, "the root reached its step limit");
        }
        try {
            scripts.load(ROOT, "load('test:other', 'value')\n", wanted -> Optional.of(tooMuch));
            helper.fail("library execution must be bounded");
        } catch (ScriptFault expected) {
            helper.assertTrue(expected.kind() == ScriptFault.Kind.STEPS
                && expected.detail().startsWith("test:other: "), "library errors keep their id and kind");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void printCallbacksKnowTheirModule(GameTestHelper helper) throws Exception {
        List<String> printed = new ArrayList<>();
        ScriptRuntime scripts = new ScriptRuntime(Profile.draftPattern(Duration.ofSeconds(5), Map.of(),
            ResourceLocation::tryParse, (id, message) -> printed.add(id + ": " + message)));
        scripts.load(ROOT, "load('test:other', 'value')\nprint('root')\n",
            wanted -> Optional.of("print('library')\nvalue = 1\n"));
        helper.assertTrue(printed.equals(List.of("test:other: library", "test:root: root")),
            "each print is attributed to the module executing it");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void scriptBindingsStayWithTheirInstance(GameTestHelper helper) throws Exception {
        ScriptRuntime first = scripts(Map.of("binding", "first"));
        ScriptRuntime second = scripts(Map.of("binding", "second"));
        String source = "load('test:other', 'value')\nanswer = binding + value\n";
        Function<ResourceLocation, Optional<String>> libraries = wanted -> Optional.of("value = binding\n");
        helper.assertTrue(first.load(ROOT, source, libraries).module().getGlobal("answer")
            .equals("firstfirst"), "the first instance supplies its bindings to root and library");
        helper.assertTrue(second.load(ROOT, source, libraries).module().getGlobal("answer")
            .equals("secondsecond"), "the second instance supplies its own bindings");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void runUsesFreshThreadsAndReportsSteps(GameTestHelper helper) throws Exception {
        ScriptRuntime scripts = scripts(Map.of());
        ScriptRuntime.Loaded loaded = scripts.load(ROOT, "def answer():\n    print('running')\n    return [1]\n",
            missing -> Optional.empty());
        Object function = loaded.module().getGlobal("answer");
        Object first = new ScriptRuntime(profile(Map.of()).withLimits(0, Duration.ofSeconds(5), 0))
            .run(ROOT, thread -> {
                Object result = Starlark.call(thread, function, List.of(), Map.of());
                helper.assertTrue(thread.getExecutedSteps() > 0, "work can read its executed steps");
                return result;
            });
        Object second = scripts.run(ROOT, thread -> {
            helper.assertTrue(thread.getExecutedSteps() == 0, "another run starts with a fresh thread");
            return Starlark.call(thread, function, List.of(), Map.of());
        });
        helper.assertTrue(first != second, "each run makes its own values");
        try {
            new ScriptRuntime(profile(Map.of()).withLimits(1, Duration.ofSeconds(5), 0))
                .run(ROOT, thread -> Starlark.call(thread, function, List.of(), Map.of()));
            helper.fail("run must honor an optional step limit");
        } catch (ScriptFault expected) {
            helper.assertTrue(expected.kind() == ScriptFault.Kind.STEPS, "run reached its step limit");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void workersReturnValuesAndFailures(GameTestHelper helper) throws Exception {
        Workers.Result<String> done = WORKERS.submit(() -> "done", 5, TimeUnit.SECONDS).get(5, TimeUnit.SECONDS);
        helper.assertTrue(done instanceof Workers.Done<String> value && value.value().equals("done"),
            "completed work returns its value");
        RuntimeException broken = new IllegalStateException("broken");
        Workers.Result<Object> failed = WORKERS.submit(() -> { throw broken; }, 5, TimeUnit.SECONDS)
            .get(5, TimeUnit.SECONDS);
        helper.assertTrue(failed instanceof Workers.Failed<Object> failure && failure.cause() == broken,
            "a runtime failure keeps its cause");
        StackOverflowError overflow = new StackOverflowError("deep");
        Workers.Result<Object> deep = WORKERS.submit(() -> { throw overflow; }, 5, TimeUnit.SECONDS)
            .get(5, TimeUnit.SECONDS);
        helper.assertTrue(deep instanceof Workers.Failed<Object> failure && failure.cause() == overflow,
            "stack overflow is also a failure");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void javaLoopsTimeOutAndLeaveTheNextTaskUninterrupted(GameTestHelper helper) throws Exception {
        Thread[] running = new Thread[1];
        Workers.Result<Object> timed = WORKERS.submit(() -> {
            running[0] = Thread.currentThread();
            while (true) {
                Workers.checkTime();
            }
        }, 25, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
        helper.assertTrue(timed instanceof Workers.Timeout<Object>, "a Java loop stops when its time is up");
        Workers.Result<Boolean> next = WORKERS.submit(() ->
            Thread.currentThread() == running[0] && !Thread.currentThread().isInterrupted(), 5, TimeUnit.SECONDS)
            .get(5, TimeUnit.SECONDS);
        helper.assertTrue(next instanceof Workers.Done<Boolean> done && done.value(),
            "the same worker starts its next task without an interrupt");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void alarmWinsOverLaterFailuresAndValues(GameTestHelper helper) throws Exception {
        Workers.Result<Object> failed = WORKERS.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.onSpinWait();
            }
            throw new IllegalStateException("after the alarm");
        }, 25, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
        helper.assertTrue(failed instanceof Workers.Timeout<Object>, "a failure after the alarm is a timeout");
        Workers.Result<String> done = WORKERS.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.onSpinWait();
            }
            return "late";
        }, 25, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
        helper.assertTrue(done instanceof Workers.Timeout<String>, "a value after the alarm is a timeout");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void starlarkWorkAlsoTimesOut(GameTestHelper helper) throws Exception {
        ScriptRuntime scripts = scripts(Map.of());
        ScriptRuntime.Loaded loaded = scripts.load(ROOT,
            "def work():\n    for i in range(1000000000):\n        pass\n", missing -> Optional.empty());
        ScriptRuntime drawing = new ScriptRuntime(profile(Map.of()).withLimits(0, Duration.ofMillis(25), 0));
        try {
            ScriptRuntime.result(drawing.submit(() -> drawing.run(ROOT,
                thread -> Starlark.call(thread, loaded.module().getGlobal("work"), List.of(), Map.of()))));
            helper.fail("Starlark must observe the worker's interrupt");
        } catch (ScriptFault expected) {
            helper.assertTrue(expected.kind() == ScriptFault.Kind.TIMEOUT, "the alarm reports a timeout");
        }
        helper.succeed();
    }

    private static ScriptRuntime scripts(Map<String, Object> bindings) {
        return new ScriptRuntime(profile(bindings));
    }

    private static Profile profile(Map<String, Object> bindings) {
        Duration time = Duration.ofSeconds(5);
        return Profile.draftPattern(time, bindings, ResourceLocation::tryParse, (id, message) -> {
        }).withLimits(STEPS, time, 0);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("test", path);
    }
}
