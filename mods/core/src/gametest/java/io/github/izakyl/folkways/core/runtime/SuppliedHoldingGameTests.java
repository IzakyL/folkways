package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.work.api.ActionSpec;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SuppliedHoldingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SuppliedHoldingGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void customHoldingAloneControlsActions(GameTestHelper helper) {
        for (boolean fail : List.of(false, true)) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                AtomicInteger gestures = new AtomicInteger();
                AtomicInteger commits = new AtomicInteger();
                AtomicInteger acquired = new AtomicInteger();
                AtomicInteger ticks = new AtomicInteger();
                WorkGesture gesture = new WorkGesture() {
                    @Override
                    public void perform(LivingEntity body, int tick, BlockPos at) {
                        gestures.incrementAndGet();
                    }

                    @Override
                    public void release(LivingEntity body) {
                        gestures.incrementAndGet();
                    }
                };
                NodeSpec spec = NodeSpec.of(UUID.randomUUID(), HoldingGameTests.OWNER,
                    WorkSite.at(h.level, h.at), Stances.WHEREVER, Workload.Once.of(0)).gesture(gesture).done();
                Node node = new Node() {
                    @Override
                    public NodeSpec spec() {
                        return spec;
                    }

                    @Override
                    public Outcome commit(ServerLevel level, Worker who) {
                        commits.incrementAndGet();
                        return Outcome.done();
                    }

                    @Override
                    public WorkHolding acquire(HoldingContext context) {
                        acquired.incrementAndGet();
                        return new WorkHolding() {
                            @Override
                            public HoldingStep tick(HoldingContext context) {
                                if (ticks.incrementAndGet() < 3) {
                                    return new HoldingStep.Running();
                                }
                                return fail ? new HoldingStep.Failed(LaborRefusal.WORK_BROKE)
                                    : new HoldingStep.Done();
                            }

                            @Override
                            public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
                            }

                            @Override
                            public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
                            }

                            @Override
                            public void suspended(HoldingContext context, PreemptReason reason) {
                            }

                            @Override
                            public void resumed(HoldingContext context) {
                            }
                        };
                    }
                };
                h.start(node);
                for (int tick = 0; tick < 2; tick++) {
                    h.labor.tickChecked(h.runner, h.level, h.body);
                    h.assertHeld(node);
                }
                h.labor.tickChecked(h.runner, h.level, h.body);
                h.assertEmpty(node);
                helper.assertValueEqual(acquired.get(), 1, "the node supplies its holding once");
                helper.assertValueEqual(commits.get(), 0, "the engine does not commit the node's action");
                helper.assertValueEqual(gestures.get(), 0, "the engine does not run the node's gesture");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void privateActionsCommitWithoutNodesOrPhases(GameTestHelper helper) {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            SequenceNode node = start(h, 3);
            WorkHolding holding = current(h);
            h.body.pack().setItem(0, new ItemStack(Items.COBBLESTONE, 2));
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
            h.body.pack().setItem(1, pick);
            run(h, node, 20);
            helper.assertValueEqual(node.commits, 2, "finite and continuous actions each commit once");
            helper.assertValueEqual(node.supplied, 2, "each action receives its own input once");
            helper.assertValueEqual(h.body.pack().count(Items.DIRT), 2, "both actual outputs reach the pack");
            helper.assertValueEqual(h.body.pack().count(Items.COBBLESTONE), 0, "both inputs are consumed");
            helper.assertValueEqual(pick.getDamageValue(), 2, "each successful action wears its tool");
            helper.assertValueEqual(node.gestureReleases, 2, "each action releases its gesture once");
            helper.assertTrue(node.performances > 0, "the shared executor performs action gestures");
            helper.assertValueEqual(node.acquired, 1, "the outer node acquires one holding");
            helper.assertValueEqual(node.releases, 1, "the outer node leaves execution once");
            helper.assertValueEqual(node.outerCommits, 0, "Done is supplied by the holding");
            HoldingContext context = h.labor.execution().context(h.level, h.body, Placement.NOWHERE);
            helper.assertTrue(holding.tick(context) instanceof HoldingStep.Done, "terminal execution stays done");
            helper.assertValueEqual(node.commits, 2, "a repeated terminal tick never commits again");
            h.assertEmpty(node);
            helper.assertTrue(h.labor.state(node.id()).orElseThrow() == NodeState.SETTLING,
                "the outer node follows normal settlement");
            h.labor.execution().phases().settled(node, h.level);
            helper.assertValueEqual(node.endings, 1, "the outer node ends once");
            assertPrivate(h, node);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void failedPrivateActionRestoresInputsAndFailsOuterNode(GameTestHelper helper) {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            SequenceNode node = start(h, 0);
            node.fail = true;
            h.body.pack().setItem(0, new ItemStack(Items.COBBLESTONE, 2));
            run(h, node, 5);
            helper.assertTrue(h.labor.state(node.id()).orElseThrow() == NodeState.FAILED,
                "a failed holding follows the ordinary node failure path");
            helper.assertValueEqual(h.body.pack().getItem(0).getCount(), 2, "failed commit restores all input");
            helper.assertValueEqual(node.commits, 1, "failure never starts the second action");
            helper.assertValueEqual(node.execution.hard, 1, "failure releases the holding once");
            helper.assertValueEqual(node.releases, 1, "the outer node is released once");
            helper.assertValueEqual(node.endings, 1, "the outer node is ended once");
            assertPrivate(h, node);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void suppliedHoldingAcquiresAfterApproach(GameTestHelper helper) {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            SequenceNode node = new SequenceNode(h, 3, Stances.at(h.level, h.at));
            h.body.mob().setPos(h.at.getX() + 4.5D, h.at.getY(), h.at.getZ() + 0.5D);
            h.body.pack().setItem(0, new ItemStack(Items.COBBLESTONE, 2));
            h.start(node);
            helper.assertValueEqual(node.acquired, 0, "travelling does not acquire the node's execution");
            helper.assertTrue(h.colony.isApproaching(node.id()), "plugins can distinguish assigned travel");
            h.labor.tickChecked(h.runner, h.level, h.body);
            helper.assertValueEqual(node.acquired, 0, "the node waits for its planned stance");
            h.body.mob().setPos(h.at.getX() + 0.5D, h.at.getY(), h.at.getZ() + 0.5D);
            h.labor.tickChecked(h.runner, h.level, h.body);
            helper.assertValueEqual(node.acquired, 1, "arrival acquires the holding once");
            helper.assertTrue(!h.colony.isApproaching(node.id()), "arrival ends the approach fact");
            h.assertHeld(node);
            assertPrivate(h, node);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void privateActionApproachesBeforeStartingItsClock(GameTestHelper helper) {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            SequenceNode node = new SequenceNode(h, 3, Stances.WHEREVER, Stances.at(h.level, h.at));
            h.body.pack().setItem(0, new ItemStack(Items.COBBLESTONE, 2));
            h.body.mob().setPos(h.at.getX() + 4.5D, h.at.getY(), h.at.getZ() + 0.5D);
            h.start(node);
            h.labor.tickChecked(h.runner, h.level, h.body);
            helper.assertValueEqual(node.acquired, 1, "the outer holding has acquired the body");
            helper.assertValueEqual(node.performances, 0, "private travel does not start the work gesture");
            h.body.mob().setPos(h.at.getX() + 0.5D, h.at.getY(), h.at.getZ() + 0.5D);
            h.labor.tickChecked(h.runner, h.level, h.body);
            for (int tick = 0; tick < 3; tick++) {
                h.labor.tickChecked(h.runner, h.level, h.body);
                helper.assertValueEqual(node.commits, 0, "the full wind-up starts after private arrival");
            }
            h.labor.tickChecked(h.runner, h.level, h.body);
            helper.assertValueEqual(node.commits, 1, "the first action commits after its own wind-up");
            run(h, node, 10);
            helper.assertValueEqual(node.commits, 2, "the next private action also finishes");
            assertPrivate(h, node);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void softSuspendResumesSuppliedHolding(GameTestHelper helper) {
        for (HoldingFactory factory : HoldingFactory.values()) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                SequenceNode node = acquire(h, 4, factory);
                long generation = h.assertHeld(node);
                TestHolding execution = node.execution;
                h.runner.requestSoft(h.level, h.body,
                    new SoftPreempt(Disposition.SUSPEND, PreemptReason.URGE, h.level.getGameTime() + 10));
                h.labor.tickChecked(h.runner, h.level, h.body);
                helper.assertTrue(h.colony.isSuspended(node.id()) && !h.colony.isHeld(node.id()),
                    "yield keeps a suspension instead of active body control");
                helper.assertValueEqual(execution.hard, 0, "soft suspension needs no hard release");
                helper.assertValueEqual(execution.suspensions, 1, "suspension is delivered once");
                helper.assertValueEqual(h.body.pack().getItem(0).getCount(), 2, "suspension retains inputs");
                h.runner.away(0);
                h.runner.away(3000);
                helper.assertTrue(h.colony.isSuspended(node.id()), "absence does not expire suspension");
                h.labor.desertedLongEnough(true, 0);
                helper.assertTrue(!h.labor.desertedLongEnough(true, 3000),
                    "an unobserved colony cannot close while work is suspended");
                h.runner.here();
                h.runner.resume(h.level, h.body);
                helper.assertTrue(h.assertHeld(node) > generation, "resume acquires a fresh generation");
                helper.assertTrue(node.execution == execution, "resume retains the same holding and actions");
                helper.assertValueEqual(execution.resumptions, 1, "resume is delivered once");
                helper.assertTrue(h.labor.state(node.id()).orElseThrow() == NodeState.WORKING,
                    "resume preserves the working phase");
                int before = execution.ticks;
                h.labor.tickChecked(h.runner, h.level, h.body);
                helper.assertValueEqual(execution.ticks, before + 1, "the resumed holding advances");
                if (factory == HoldingFactory.ACTIONS) {
                    run(h, node, 20);
                    helper.assertValueEqual(node.commits, 2, "both actions finish after resume");
                    helper.assertValueEqual(node.acquired, 1, "resume does not reacquire a different execution");
                }
                assertPrivate(h, node);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void hardSuspendReleasesSuppliedHoldingOncePerGeneration(GameTestHelper helper) {
        for (HoldingFactory factory : HoldingFactory.values()) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                SequenceNode node = acquire(h, 4, factory);
                TestHolding execution = node.execution;
                execution.onHard = () -> h.runner.preemptHard(h.level, h.body,
                    Disposition.SUSPEND, PreemptReason.URGE);
                h.runner.preemptHard(h.level, h.body, Disposition.SUSPEND, PreemptReason.URGE);
                execution.onHard = null;
                h.runner.preemptHard(h.level, h.body, Disposition.SUSPEND, PreemptReason.URGE);
                helper.assertValueEqual(execution.hard, 1, "reentrant and repeated release affect one generation once");
                helper.assertValueEqual(execution.suspensions, 1, "the generation is suspended once");
                h.runner.resume(h.level, h.body);
                h.runner.preemptHard(h.level, h.body, Disposition.SUSPEND, PreemptReason.URGE);
                helper.assertValueEqual(execution.hard, 2, "the next generation has its own hard release");
                h.runner.resume(h.level, h.body);
                helper.assertValueEqual(execution.suspensions, 2, "each generation suspends once");
                helper.assertValueEqual(execution.resumptions, 2, "each suspension resumes once");
                if (factory == HoldingFactory.ACTIONS) {
                    run(h, node, 20);
                    helper.assertValueEqual(node.commits, 2, "interrupted wind-ups never duplicate input consumption");
                    helper.assertValueEqual(node.supplied, 2, "each successful action consumes one input");
                }
                assertPrivate(h, node);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void softEndEndsSuppliedHoldingAfterCleanup(GameTestHelper helper) {
        for (HoldingFactory factory : HoldingFactory.values()) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                SequenceNode node = acquire(h, 20, factory);
                h.runner.requestSoft(h.level, h.body,
                    new SoftPreempt(Disposition.END, PreemptReason.OTHER, h.level.getGameTime() + 10));
                h.labor.tickChecked(h.runner, h.level, h.body);
                assertEnded(h, node, 0);
                helper.assertValueEqual(node.execution.suspensions, 0, "ended work is never suspended");
                helper.assertValueEqual(node.execution.requests, 1, "soft end reaches the supplied holding");
                helper.assertValueEqual(node.commits, 0, "cleanup starts no production");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void hardEndEndsSuppliedHoldingSynchronously(GameTestHelper helper) {
        for (HoldingFactory factory : HoldingFactory.values()) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                SequenceNode node = acquire(h, 20, factory);
                h.runner.preemptHard(h.level, h.body, Disposition.END, PreemptReason.REVOKED);
                h.runner.preemptHard(h.level, h.body, Disposition.END, PreemptReason.REVOKED);
                assertEnded(h, node, 1);
                helper.assertTrue(node.execution.reason == PreemptReason.REVOKED,
                    "the supplied holding receives the hard end reason");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void controllerTakeoverSoftEndsAndRequeuesSuppliedHolding(GameTestHelper helper) {
        for (HoldingFactory factory : HoldingFactory.values()) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                SequenceNode node = acquire(h, 20, factory);
                TestHolding previous = node.execution;
                h.runner.controllerTakeover(h.level, h.body, false);
                helper.assertTrue(previous.request.result() == Disposition.END
                    && previous.request.reason() == PreemptReason.CONTROLLER, "control requests soft end");
                h.labor.tickChecked(h.runner, h.level, h.body);
                h.assertEmpty(node);
                helper.assertTrue(h.labor.state(node.id()).orElseThrow() == NodeState.PENDING,
                    "controller end returns the outer node to planning");
                helper.assertValueEqual(previous.hard, 0, "cooperative control cleanup stays soft");
                helper.assertValueEqual(node.releases, 1, "the old execution leaves once");
                h.start(node);
                h.labor.tickChecked(h.runner, h.level, h.body);
                helper.assertValueEqual(node.acquired, 2, "requeued work acquires a fresh holding");
                helper.assertTrue(node.execution != previous, "the abandoned holding is not resumed");
                assertPrivate(h, node);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void controllerOrderEscalatesSuppliedCleanupOnce(GameTestHelper helper) {
        for (HoldingFactory factory : HoldingFactory.values()) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                SequenceNode node = acquire(h, 20, factory);
                node.execution.cooperates = false;
                h.runner.controllerTakeover(h.level, h.body, false);
                long now = h.level.getGameTime();
                helper.assertTrue(node.execution.request.result() == Disposition.END
                    && node.execution.request.reason() == PreemptReason.CONTROLLER
                    && node.execution.request.deadlineTick() == now + 20, "controller cleanup is bounded");
                h.labor.tickChecked(h.runner, h.level, h.body);
                helper.assertValueEqual(node.execution.hard, 0, "cleanup runs before the order");
                h.colony.controllerOrder(h.body);
                h.colony.controllerOrder(h.body);
                helper.assertValueEqual(h.level.getGameTime(), now, "the order escalates in the same tick");
                helper.assertValueEqual(node.execution.hard, 1, "a concrete controller order hard releases once");
                helper.assertTrue(node.execution.reason == PreemptReason.CONTROLLER,
                    "hard escalation retains the controller reason");
                helper.assertTrue(h.labor.state(node.id()).orElseThrow() == NodeState.PENDING,
                    "hard controller end requeues the outer node");
                helper.assertValueEqual(node.releases, 1, "the outer node hears one release");
                h.assertEmpty(node);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void controllerEndsSuspendedSuppliedHoldingWithoutSecondRelease(GameTestHelper helper) {
        for (HoldingFactory factory : HoldingFactory.values()) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                SequenceNode node = acquire(h, 20, factory);
                h.runner.preemptHard(h.level, h.body, Disposition.SUSPEND, PreemptReason.URGE);
                h.runner.controllerTakeover(h.level, h.body, false);
                helper.assertValueEqual(node.execution.hard, 1, "ending a suspension never releases its old body twice");
                helper.assertTrue(!h.colony.isSuspended(node.id()), "control removes the retained suspension");
                helper.assertTrue(h.labor.state(node.id()).orElseThrow() == NodeState.PENDING,
                    "the suspended node is requeued");
                helper.assertValueEqual(node.releases, 1, "the abandoned suspension leaves once");
                helper.assertValueEqual(node.endings, 0, "the planner still owns unfinished work");
                h.runner.resume(h.level, h.body);
                helper.assertValueEqual(node.execution.resumptions, 0, "abandoned work never resumes");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void softDeadlineHardReleasesSuppliedHoldingOnce(GameTestHelper helper) {
        for (HoldingFactory factory : HoldingFactory.values()) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                SequenceNode node = acquire(h, 20, factory);
                node.execution.cooperates = false;
                long deadline = h.level.getGameTime() + 10;
                h.runner.requestSoft(h.level, h.body,
                    new SoftPreempt(Disposition.SUSPEND, PreemptReason.URGE, deadline));
                h.labor.tickChecked(h.runner, h.level, h.body);
                h.runner.checkDeadline(h.level, h.body, deadline - 1, true);
                helper.assertValueEqual(node.execution.hard, 0, "the absolute deadline is inclusive");
                h.runner.checkDeadline(h.level, h.body, deadline, true);
                h.runner.checkDeadline(h.level, h.body, deadline, true);
                helper.assertValueEqual(node.execution.hard, 1, "the deadline escalates one generation once");
                helper.assertTrue(h.colony.isSuspended(node.id()), "deadline escalation preserves suspension");
                helper.assertValueEqual(node.commits, 0, "soft cleanup starts no new action");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void preemptBeforeAcquireDoesNotStartPrivateActions(GameTestHelper helper) {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            SequenceNode node = start(h, 20);
            h.runner.requestSoft(h.level, h.body,
                new SoftPreempt(Disposition.SUSPEND, PreemptReason.URGE, h.level.getGameTime() + 10));
            h.labor.tickChecked(h.runner, h.level, h.body);
            helper.assertValueEqual(node.acquired, 0, "preempted arrival starts no custom execution");
            helper.assertTrue(h.colony.isSuspended(node.id()), "the outer node retains its suspension");
            h.runner.resume(h.level, h.body);
            h.labor.tickChecked(h.runner, h.level, h.body);
            helper.assertValueEqual(node.acquired, 1, "resume acquires the custom holding at the stance");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void acquireFailureFailsOuterNode(GameTestHelper helper) {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            Node original = h.node(new Workload.Custom(), new AtomicInteger(), false);
            Node node = new Node() {
                @Override
                public NodeSpec spec() {
                    return original.spec();
                }

                @Override
                public WorkHolding acquire(HoldingContext context) {
                    throw new IllegalStateException("test acquisition failure");
                }

                @Override
                public Outcome commit(ServerLevel level, Worker who) {
                    return Outcome.done();
                }
            };
            h.start(node);
            h.labor.tickChecked(h.runner, h.level, h.body);
            helper.assertTrue(h.labor.state(node.id()).orElseThrow() == NodeState.FAILED,
                "a broken acquire fails through the normal node lifecycle");
            h.assertEmpty(node);
        }
        helper.succeed();
    }

    private static WorkHolding current(HoldingGameTests.Harness h) {
        try {
            var field = BodyRunner.class.getDeclaredField("execution");
            field.setAccessible(true);
            return (WorkHolding) field.get(h.runner);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static SequenceNode start(HoldingGameTests.Harness h, int ticks) {
        SequenceNode node = new SequenceNode(h, ticks, Stances.WHEREVER);
        h.body.pack().setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        h.start(node);
        return node;
    }

    private enum HoldingFactory {
        PROBE,
        ACTIONS;

        TestHolding create(HoldingContext context, List<WorkAction> steps) {
            return this == PROBE ? new TestHolding()
                : new TestHolding(new SequentialHolding(context, steps, () -> {}));
        }
    }

    private static SequenceNode acquire(HoldingGameTests.Harness h, int ticks, HoldingFactory factory) {
        SequenceNode node = new SequenceNode(h, ticks, Stances.WHEREVER);
        node.factory = factory;
        h.body.pack().setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        h.start(node);
        h.labor.tickChecked(h.runner, h.level, h.body);
        return node;
    }

    private static SequenceNode acquire(HoldingGameTests.Harness h, int ticks) {
        SequenceNode node = start(h, ticks);
        h.body.pack().setItem(0, new ItemStack(Items.COBBLESTONE, 2));
        h.labor.tickChecked(h.runner, h.level, h.body);
        return node;
    }

    private static void run(HoldingGameTests.Harness h, SequenceNode node, int ticks) {
        for (int tick = 0; tick < ticks && h.colony.isHeld(node.id()); tick++) {
            assertPrivate(h, node);
            h.labor.tickChecked(h.runner, h.level, h.body);
        }
    }

    private static void assertPrivate(HoldingGameTests.Harness h, SequenceNode node) {
        for (WorkAction step : node.steps) {
            h.helper.assertTrue(!(step instanceof Node) && !(step.spec() instanceof NodeSpec),
                "private actions have no planning node or ID");
        }
        int planned = 0;
        for (NodeState state : NodeState.values()) {
            h.helper.assertTrue(h.labor.execution().personal().in(state).isEmpty(),
                "private actions never enter personal phases");
            for (UUID id : h.labor.execution().phases().in(state)) {
                h.helper.assertTrue(id.equals(node.id()), "only the outer node enters the plan's phases");
                planned++;
            }
        }
        h.helper.assertValueEqual(planned, 1, "private execution registers no additional node");
    }

    private static void assertEnded(HoldingGameTests.Harness h, SequenceNode node, int hard) {
        h.assertEmpty(node);
        h.helper.assertTrue(h.labor.state(node.id()).orElseThrow() == NodeState.CANCELLED,
            "end uses the normal cancelled phase");
        h.helper.assertValueEqual(node.execution.hard, hard, "hard release count matches the preemption");
        h.helper.assertValueEqual(node.releases, 1, "the outer node leaves once");
        h.helper.assertValueEqual(node.endings, 1, "the outer node ends once");
        h.helper.assertValueEqual(node.commits, 0, "preemption does not commit unfinished work");
        assertPrivate(h, node);
    }

    private static final class SequenceNode implements Node {
        private final NodeSpec spec;
        private final List<WorkAction> steps;
        private HoldingFactory factory = HoldingFactory.ACTIONS;
        private TestHolding execution;
        private int acquired;
        private int commits;
        private int supplied;
        private int outerCommits;
        private int releases;
        private int endings;
        private int gestureReleases;
        private int performances;
        private boolean fail;
        private int continuousTicks;

        SequenceNode(HoldingGameTests.Harness h, int ticks, Stances stance) {
            this(h, ticks, stance, Stances.WHEREVER);
        }

        SequenceNode(HoldingGameTests.Harness h, int ticks, Stances stance, Stances actionStance) {
            spec = NodeSpec.of(UUID.randomUUID(), HoldingGameTests.OWNER,
                WorkSite.at(h.level, h.at), stance, new Workload.Custom())
                .needs(new Need(ItemSpec.of(Items.COBBLESTONE), 2, true)).done();
            steps = List.of(new Step(h, Workload.Once.of(ticks), actionStance),
                new Step(h, (Workload.Continuous) (level, who) -> continuousTicks++ < 2, actionStance));
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public WorkHolding acquire(HoldingContext context) {
            acquired++;
            execution = factory.create(context, steps);
            return execution;
        }

        @Override
        public boolean directTravel() {
            return true;
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            outerCommits++;
            return Outcome.done();
        }

        @Override
        public void released(ServerLevel level, Worker who, Ending how) {
            releases++;
        }

        @Override
        public void ended(ServerLevel level, Ending how) {
            endings++;
        }

        private final class Step implements WorkAction, ActionSpec {
            private final WorkSite site;
            private final Workload workload;
            private final Stances stances;
            private final WorkGesture gesture;

            Step(HoldingGameTests.Harness h, Workload load, Stances stance) {
                site = WorkSite.at(h.level, h.at);
                workload = load;
                stances = stance;
                gesture = new WorkGesture() {
                    @Override
                    public void perform(LivingEntity body, int tick, BlockPos at) {
                        performances++;
                    }

                    @Override
                    public void release(LivingEntity body) {
                        gestureReleases++;
                    }
                };
            }

            @Override
            public ActionSpec spec() {
                return this;
            }

            @Override
            public Doing.Wares wares() {
                return Doing.Wares.NONE;
            }

            @Override
            public WorkSite site() {
                return site;
            }

            @Override
            public Workload workload() {
                return workload;
            }

            @Override
            public Stances stances() {
                return stances;
            }

            @Override
            public List<Need> needs() {
                return List.of(new Need(ItemSpec.of(Items.COBBLESTONE), 1, true));
            }

            @Override
            public List<ToolUse> tools() {
                return List.of(ToolUse.of(new ToolNeed.SuitableFor(Blocks.COBBLESTONE.defaultBlockState())));
            }

            @Override
            public WorkGesture gesture() {
                return gesture;
            }

            @Override
            public Outcome commit(ServerLevel level, Worker who) {
                commits++;
                supplied += who.supplied().stream().mapToInt(ItemStack::getCount).sum();
                return fail ? Outcome.failed(LaborRefusal.WORK_BROKE)
                    : new Outcome.Done(List.of(), List.of(new ItemStack(Items.DIRT)), Optional.empty());
            }
        }
    }

}
