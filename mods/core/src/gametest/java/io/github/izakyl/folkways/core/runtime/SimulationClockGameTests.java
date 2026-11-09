package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Claims;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.Known;
import io.github.izakyl.folkways.core.plan.internal.Planner;
import io.github.izakyl.folkways.core.plan.internal.Scheduler;
import io.github.izakyl.folkways.core.plan.internal.Situation;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.testing.Nodes;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraph;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SimulationClockGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SimulationClockGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void planningBeginsOnlyWhenToldAndOneAtATime(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = new ColonyData();
        ColonyLabor labor = new ColonyLabor(colony, level.dimension());
        List<Runnable> queued = new ArrayList<>();
        Executor later = queued::add;
        try {
            for (int tick = 0; tick < 5; tick++) {
                labor.run(level, new Lanes(later, Runnable::run));
            }
            helper.assertTrue(queued.isEmpty(), "with nothing to tell the planner, no plan is begun");
            colony.works().submit(OWNER, level.dimension(), Grown.of(node(helper)), (any, how) -> { });
            labor.run(level, new Lanes(later, Runnable::run));
            helper.assertTrue(queued.size() == 1, "a submission begins a plan at once");
            colony.works().submit(OWNER, level.dimension(), Grown.of(node(helper)), (any, how) -> { });
            labor.run(level, new Lanes(later, Runnable::run));
            helper.assertTrue(queued.size() == 1, "a plan in flight holds back the next one");
            queued.removeFirst().run();
            labor.run(level, new Lanes(later, Runnable::run));
            helper.assertTrue(queued.size() == 1, "what arrived meanwhile begins the next plan");
            queued.removeFirst().run();
            labor.run(level, new Lanes(later, Runnable::run));
            helper.assertTrue(queued.isEmpty(), "once everything is told, planning rests");
        } finally {
            labor.close();
            queued.forEach(Runnable::run);
            ColonyWays.forget(colony.colonyId());
        }
        helper.succeed();
    }

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "clock");

    private static Node node(GameTestHelper helper) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER,
            WorkSite.at(helper.getLevel(), helper.absolutePos(BlockPos.ZERO)), Stances.WHEREVER,
            Workload.Once.of(1)).done();
        return Nodes.done(spec);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void frozenServerEventsDoNotAdvanceLaborButTheLastStepDoes(GameTestHelper helper)
            throws ReflectiveOperationException {
        var server = helper.getLevel().getServer();
        var clock = server.tickRateManager();
        boolean frozen = clock.isFrozen();
        int steps = clock.frozenTicksToRun();
        Labor labor = new Labor();
        var ticks = Labor.class.getDeclaredField("simulationTicks");
        ticks.setAccessible(true);
        var event = new ServerTickEvent.Post(() -> true, server);
        try {
            clock.setFrozen(true);
            clock.setFrozenTicksToRun(0);
            clock.tick();
            for (int i = 0; i < 250; i++) {
                labor.onServerTick(event);
            }
            helper.assertTrue(ticks.getInt(labor) == 0, "frozen server events must not advance labor");
            clock.stepGameIfPaused(1);
            clock.tick();
            helper.assertTrue(!clock.isSteppingForward(), "the final step has no remaining step ticks");
            labor.onServerTick(event);
            helper.assertTrue(ticks.getInt(labor) == 1, "the final simulation step must run labor once");
            clock.tick();
            labor.onServerTick(event);
            helper.assertTrue(ticks.getInt(labor) == 1, "labor must stop again after the step");
            clock.setFrozen(false);
            clock.tick();
            labor.onServerTick(event);
            helper.assertTrue(ticks.getInt(labor) == 2, "unfreezing must resume labor");
        } finally {
            clock.setFrozen(frozen);
            clock.setFrozenTicksToRun(steps);
            clock.tick();
            clock.setFrozenTicksToRun(steps);
            labor.shutdown();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void retiredPipelinesRejectReadyAndLateResults(GameTestHelper helper) throws InterruptedException {
        var level = helper.getLevel();
        Situation empty = new Situation(WayGraph.UNBUILT, new Stock(List.of(), Map.of()),
            List.of(), Stands.NONE);
        for (boolean readyFirst : List.of(false, true)) {
            Planner planner = new Planner(new Claims(), new Cooldowns());
            Pipeline pipeline = new Pipeline(new Inbox(), planner, new Scheduler());
            AtomicReference<Runnable> queued = new AtomicReference<>();
            AtomicInteger applied = new AtomicInteger();
            pipeline.weave(queued::set, Runnable::run, new Known(empty, List.of()), level.getGameTime(),
                (batch, woven) -> applied.incrementAndGet(), laid -> applied.incrementAndGet());
            if (!readyFirst) {
                pipeline.close(Runnable::run, Runnable::run);
            }
            Thread worker = new Thread(queued.get(), "folkways-test-retired-solve");
            worker.start();
            worker.join(5_000);
            helper.assertTrue(!worker.isAlive(), "the retired plan must finish");
            pipeline.close(Runnable::run, Runnable::run);
            pipeline.applyReady(level);
            helper.assertTrue(applied.get() == 0,
                "neither an already ready result nor a late result may enter a retired world");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void backgroundResultWaitsForASimulationTick(GameTestHelper helper) throws InterruptedException {
        var level = helper.getLevel();
        var clock = level.getServer().tickRateManager();
        boolean frozen = clock.isFrozen();
        int steps = clock.frozenTicksToRun();
        Pipeline pipeline = new Pipeline(new Inbox(), new Planner(new Claims(), new Cooldowns()), new Scheduler());
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger woven = new AtomicInteger();
        AtomicInteger laid = new AtomicInteger();
        Situation empty = new Situation(WayGraph.UNBUILT, new Stock(List.of(), Map.of()),
            List.of(), Stands.NONE);
        try {
            pipeline.weave(queued::set, Runnable::run, new Known(empty, List.of()), level.getGameTime(),
                (batch, result) -> woven.incrementAndGet(), result -> laid.incrementAndGet());
            clock.setFrozen(true);
            clock.setFrozenTicksToRun(0);
            clock.tick();
            Thread worker = new Thread(queued.get(), "folkways-test-solve");
            worker.start();
            worker.join(5_000);
            helper.assertTrue(!worker.isAlive(), "the plan should finish while frozen");
            pipeline.applyReady(level);
            helper.assertTrue(woven.get() == 0 && laid.get() == 0 && pipeline.weaving(),
                "frozen results must remain pending");
            clock.stepGameIfPaused(1);
            clock.tick();
            pipeline.applyReady(level);
            pipeline.applyReady(level);
            helper.assertTrue(woven.get() == 1 && laid.get() == 1 && !pipeline.weaving(),
                "the next step applies the weave and the schedule laid for it exactly once");
        } finally {
            pipeline.close(Runnable::run, Runnable::run);
            clock.setFrozen(frozen);
            clock.setFrozenTicksToRun(steps);
            clock.tick();
            clock.setFrozenTicksToRun(steps);
        }
        helper.succeed();
    }
}
