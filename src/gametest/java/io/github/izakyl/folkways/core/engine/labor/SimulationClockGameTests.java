package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.plan.Claims;
import io.github.izakyl.folkways.core.engine.plan.Cooldowns;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Planner;
import io.github.izakyl.folkways.core.engine.plan.Situation;
import io.github.izakyl.folkways.core.engine.plan.Stands;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.travel.graph.WayGraph;
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
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SimulationClockGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SimulationClockGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void planningBeginsOnlyWhenToldAndOneAtATime(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = new ColonyData();
        ColonyLabor labor = new ColonyLabor(colony, level.dimension());
        List<Runnable> queued = new ArrayList<>();
        Executor later = queued::add;
        try {
            for (int tick = 0; tick < 5; tick++) {
                labor.run(level, later);
            }
            helper.assertTrue(queued.isEmpty(), "with nothing to tell the planner, no plan is begun");
            colony.works().submit(OWNER, level.dimension(), Grown.of(node(helper)), (any, how) -> { });
            labor.run(level, later);
            helper.assertTrue(queued.size() == 1, "a submission begins a plan at once");
            colony.works().submit(OWNER, level.dimension(), Grown.of(node(helper)), (any, how) -> { });
            labor.run(level, later);
            helper.assertTrue(queued.size() == 1, "a plan in flight holds back the next one");
            queued.removeFirst().run();
            labor.run(level, later);
            helper.assertTrue(queued.size() == 1, "what arrived meanwhile begins the next plan");
            queued.removeFirst().run();
            labor.run(level, later);
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
        return new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
    }

    @GameTest(template = "empty", timeoutTicks = 100)
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

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void retiredCyclesRejectReadyAndLateResults(GameTestHelper helper) throws InterruptedException {
        var level = helper.getLevel();
        Situation empty = new Situation(WayGraph.UNBUILT, new Stock(List.of(), Map.of()),
            List.of(), Stands.NONE);
        for (boolean readyFirst : List.of(false, true)) {
            Cycle cycle = new Cycle(new Inbox());
            Planner planner = new Planner(new Claims(), new Cooldowns());
            AtomicReference<Runnable> queued = new AtomicReference<>();
            AtomicInteger applied = new AtomicInteger();
            cycle.begin(level, queued::set, planner, new Known(empty, List.of()),
                level.getGameTime(), (batch, solved) -> applied.incrementAndGet());
            if (!readyFirst) cycle.close();
            Thread worker = new Thread(queued.get(), "folkways-test-retired-solve");
            worker.start();
            worker.join(5_000);
            helper.assertTrue(!worker.isAlive(), "the retired plan must finish");
            cycle.close();
            cycle.applyReady(level);
            helper.assertTrue(applied.get() == 0,
                "neither an already ready result nor a late result may enter a retired world");
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void backgroundResultWaitsForASimulationTick(GameTestHelper helper) throws InterruptedException {
        var level = helper.getLevel();
        var clock = level.getServer().tickRateManager();
        boolean frozen = clock.isFrozen();
        int steps = clock.frozenTicksToRun();
        Cycle cycle = new Cycle(new Inbox());
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger applied = new AtomicInteger();
        Situation empty = new Situation(WayGraph.UNBUILT, new Stock(List.of(), Map.of()),
            List.of(), Stands.NONE);
        try {
            cycle.begin(level, queued::set, new Planner(new Claims(), new Cooldowns()),
                new Known(empty, List.of()), level.getGameTime(), (batch, solved) -> applied.incrementAndGet());
            clock.setFrozen(true);
            clock.setFrozenTicksToRun(0);
            clock.tick();
            Thread worker = new Thread(queued.get(), "folkways-test-solve");
            worker.start();
            worker.join(5_000);
            helper.assertTrue(!worker.isAlive(), "the plan should finish while frozen");
            cycle.applyReady(level);
            helper.assertTrue(applied.get() == 0 && cycle.busy(), "frozen results must remain pending");
            helper.assertTrue(cycle.busyFor(Long.MAX_VALUE) == 0, "a pending result is not a stuck solve");
            clock.stepGameIfPaused(1);
            clock.tick();
            cycle.applyReady(level);
            cycle.applyReady(level);
            helper.assertTrue(applied.get() == 1 && !cycle.busy(), "the next step applies the result exactly once");
        } finally {
            clock.setFrozen(frozen);
            clock.setFrozenTicksToRun(steps);
            clock.tick();
            clock.setFrozenTicksToRun(steps);
        }
        helper.succeed();
    }
}
