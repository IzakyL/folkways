package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.base.task.ColonyLog;
import io.github.izakyl.folkways.core.runtime.RecentErrors;
import io.github.izakyl.folkways.core.plan.internal.Claims;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.Known;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.plan.internal.Planner;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Scheduler;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ColonyLogGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ColonyLogGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void nestedScopesRestorePreviousValuesEvenAfterFailure(GameTestHelper helper) {
        String previous = ThreadContext.get(ColonyLog.KEY);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var outer = ColonyLog.open(first)) {
            try {
                try (var inner = ColonyLog.open(second)) {
                    helper.assertTrue(ThreadContext.get(ColonyLog.KEY).equals(second.toString()), "inner colony wins");
                    throw new IllegalStateException("scope fixture");
                }
            } catch (IllegalStateException expected) {
                helper.assertTrue(ThreadContext.get(ColonyLog.KEY).equals(first.toString()), "failure restores outer");
            }
            try (var empty = ColonyLog.open(null)) {
                helper.assertFalse(ThreadContext.containsKey(ColonyLog.KEY), "unknown origins are untagged");
            }
            helper.assertTrue(ThreadContext.get(ColonyLog.KEY).equals(first.toString()), "empty scope restores outer");
        }
        helper.assertTrue(java.util.Objects.equals(previous, ThreadContext.get(ColonyLog.KEY)),
                "outer scope restores the original value");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void plannerAndSchedulerTasksCarryTheSubmittingColony(GameTestHelper helper)
            throws InterruptedException {
        UUID colony = UUID.randomUUID();
        String marker = UUID.randomUUID().toString();
        PlanningFixture fixture = new PlanningFixture(helper);
        AtomicReference<Runnable> weave = new AtomicReference<>();
        AtomicReference<Runnable> schedule = new AtomicReference<>();
        AtomicBoolean woven = new AtomicBoolean();
        AtomicBoolean laid = new AtomicBoolean();
        ResourceLocation owner = ResourceLocation.parse("folkways_core:log_task_test");
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner, new WorkSite.AtBlock(fixture.target),
                Stances.at(fixture.target), Workload.Once.of(20, who -> 1.0)).done();
        Node node = new Node() {
            @Override
            public NodeSpec spec() {
                String thread = Thread.currentThread().getName();
                if (thread.equals("folkways-log-weave") && woven.compareAndSet(false, true)
                        || thread.equals("folkways-log-schedule") && laid.compareAndSet(false, true)) {
                    LoggerFactory.getLogger("folkways-labor").warn("{} {}", marker, thread);
                }
                return spec;
            }

            @Override
            public Outcome commit(ServerLevel level, Worker who) {
                throw new AssertionError("planning executed work");
            }
        };
        Inbox inbox = new Inbox();
        Pipeline pipeline = new Pipeline(inbox, new Planner(new Claims(), new Cooldowns()), new Scheduler());
        try {
            inbox.raise(new Message.Submitted(owner, Grown.of(node)));
            var known = new Known(fixture.situation(new Stock(List.of(), Map.of()), List.of()), fixture.crew.hands());
            try (var ignored = ColonyLog.open(colony)) {
                pipeline.weave(weave::set, schedule::set, known, helper.getLevel().getGameTime(),
                        (batch, result) -> { }, result -> { });
            }
            run(helper, weave.get(), "folkways-log-weave");
            run(helper, schedule.get(), "folkways-log-schedule");
            helper.assertTrue(woven.get() && laid.get(), "both actual planning stages read the node on worker threads");
            var errors = RecentErrors.latest(200).stream().filter(entry -> entry.message().contains(marker)).toList();
            helper.assertTrue(errors.size() == 1 && errors.getFirst().count() == 2 && errors.stream()
                    .allMatch(entry -> entry.colony().equals(Optional.of(colony))), "both stages preserve the colony");
        } finally {
            pipeline.close(Runnable::run, Runnable::run);
            fixture.worker.body().discard();
        }
        helper.succeed();
    }

    private static void run(GameTestHelper helper, Runnable task, String name) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        UUID previous = UUID.randomUUID();
        Thread thread = new Thread(() -> {
            try (var ignored = ColonyLog.open(previous)) {
                task.run();
                if (!ThreadContext.get(ColonyLog.KEY).equals(previous.toString())) {
                    throw new AssertionError("worker context leaked");
                }
            } catch (Throwable broken) {
                failure.set(broken);
            }
        }, name);
        thread.start();
        thread.join(5_000);
        helper.assertFalse(thread.isAlive(), "planning worker finished");
        if (failure.get() != null) {
            throw new AssertionError("planning worker failed", failure.get());
        }
    }
}
