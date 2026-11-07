package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HoldingPreemptionGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(HoldingPreemptionGameTests.class);
    }

    @GameTest(template = "empty")
    public static void standardSoftSuspensionAbortsWindupWithoutCommit(GameTestHelper helper) {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            AtomicInteger commits = new AtomicInteger();
            Node node = h.node(Workload.Once.of(3), commits, false);
            h.start(node);
            h.labor.tick(h.runner, h.level, h.body);
            h.runner.requestSoft(h.level, h.body,
                new SoftPreempt(Disposition.SUSPEND, PreemptReason.URGE, h.level.getGameTime() + 10));
            h.labor.tickChecked(h.runner, h.level, h.body);
            helper.assertValueEqual(commits.get(), 0, "soft release aborts the unfinished wind-up");
            helper.assertTrue(h.labor.execution().holdings().suspended(node.id()).isPresent(), "work is suspended");
            h.runner.resume(h.level, h.body);
            for (int tick = 0; tick < 4; tick++) {
                h.labor.tick(h.runner, h.level, h.body);
            }
            helper.assertValueEqual(commits.get(), 1, "the resumed wind-up commits exactly once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void urgentUrgeInterruptsSoftCleanup(GameTestHelper helper) throws Exception {
        for (boolean stillOffered : List.of(true, false)) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                Node node = h.node(Workload.Once.of(100), new AtomicInteger(), false);
                h.start(node);
                TestHolding probe = install(h);
                probe.cooperates = false;
                Node slow = h.node(Workload.Once.of(100), new AtomicInteger(), false);
                Urge first = new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "soft_urge"), 1, true, () -> slow);
                HoldingGameTests.OFFERS.put(h.colony.id(), List.of(first));
                h.interrupt();
                helper.assertValueEqual(probe.requests, 1, "ordinary urge requests soft suspension");
                Node fast = h.node(Workload.Once.of(100), new AtomicInteger(), false);
                Urge urgent = new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "urgent_urge"), 2, true,
                    () -> fast).asUrgent();
                HoldingGameTests.OFFERS.put(h.colony.id(), stillOffered ? List.of(first, urgent) : List.of(urgent));
                h.interrupt();
                helper.assertValueEqual(probe.hard, 1, "an urgent higher priority urge interrupts cleanup");
                helper.assertTrue(h.labor.execution().holdings().suspended(node.id()).isPresent(), "original work is suspended");
                h.assertHeld(fast);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withdrawnSuspendedUrgeDoesNotResume(GameTestHelper helper) throws Exception {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            Node firstNode = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            Urge first = new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "first_need"), 1, true,
                () -> firstNode);
            HoldingGameTests.OFFERS.put(h.colony.id(), List.of(first));
            h.interrupt();
            TestHolding probe = install(h);
            Node nextNode = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            Urge next = new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "next_need"), 2, true,
                () -> nextNode);
            HoldingGameTests.OFFERS.put(h.colony.id(), List.of(first, next));
            h.interrupt();
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertTrue(h.labor.execution().holdings().suspended(firstNode.id()).isPresent(), "the first urge is suspended");
            HoldingGameTests.OFFERS.put(h.colony.id(), List.of(next));
            h.interrupt();
            helper.assertTrue(h.labor.execution().holdings().suspended(firstNode.id()).isEmpty(), "withdrawal ends suspension");
            helper.assertValueEqual(probe.resumptions, 0, "a withdrawn suspended urge never resumes");
            h.assertHeld(nextNode);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void revokedSuspendedWorkEndsOnlyOnce(GameTestHelper helper) throws Exception {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            AtomicInteger releases = new AtomicInteger();
            AtomicInteger endings = new AtomicInteger();
            Node node = trackedNode(h, new AtomicBoolean(), releases, endings);
            h.start(node);
            TestHolding probe = install(h);
            h.runner.preemptHard(h.level, h.body, Disposition.SUSPEND, PreemptReason.URGE);
            h.labor.execution().phases().cancelled(node.id());
            h.runner.check(h.level, h.body);
            h.runner.check(h.level, h.body);
            helper.assertTrue(h.labor.execution().holdings().suspended(node.id()).isEmpty(), "revoked suspension is removed");
            helper.assertValueEqual(releases.get(), 1, "release callback runs once");
            helper.assertValueEqual(endings.get(), 1, "node ends once");
            helper.assertValueEqual(probe.resumptions, 0, "revocation never resumes the node");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2300)
    public static void suspensionKeepsItemsAndReservationsPastTwoThousandTicks(GameTestHelper helper) {
        HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper);
        AtomicBoolean fail = new AtomicBoolean();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger endings = new AtomicInteger();
        Node node = trackedNode(h, fail, releases, endings);
        h.body.pack().setItem(0, new ItemStack(Items.IRON_INGOT, 7));
        Placement placement = new Placement(Set.of(), List.of(), List.of(), List.of(),
            List.of(new Placement.Draw(Optional.empty(), ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot")), 7)));
        h.start(node, placement);
        h.runner.preemptHard(h.level, h.body, Disposition.SUSPEND, PreemptReason.URGE);
        Node urgeNode = h.node((Workload.Continuous) (level, who) -> true, new AtomicInteger(), false);
        h.labor.execution().personal().entered(urgeNode, h.level);
        h.labor.execution().personal().ready(urgeNode, h.level);
        h.runner.offer(h.level, h.body, new Urge(HoldingGameTests.OWNER, 1, false, () -> urgeNode), urgeNode);
        long start = h.level.getGameTime();
        AtomicBoolean done = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (!done.get()) {
                h.labor.tick(h.runner, h.level, h.body);
            }
        });
        helper.runAfterDelay(2100, () -> {
            try {
                done.set(true);
                helper.assertTrue(h.level.getGameTime() - start >= 2000, "the world advanced over 2000 ticks");
                helper.assertTrue(h.labor.execution().holdings().suspended(node.id()).isPresent(), "suspension has no timeout");
                helper.assertTrue(h.labor.state(node.id()).orElseThrow() == NodeState.WORKING,
                    "suspended work stays working");
                helper.assertTrue(h.labor.reservedInputs().stream().anyMatch(input -> input.node().equals(node.id())
                    && input.count() == 7), "the input reservation remains");
                helper.assertValueEqual(h.body.pack().getItem(0).getCount(), 7, "the physical items remain");
                helper.assertValueEqual(endings.get(), 0, "the node has not ended");
                fail.set(true);
                h.runner.check(h.level, h.body);
                helper.assertTrue(h.labor.execution().holdings().suspended(node.id()).isEmpty(), "node failure ends suspension");
                helper.assertTrue(h.labor.reservedInputs().stream().noneMatch(input -> input.node().equals(node.id())),
                    "the failed node releases its reservation");
                helper.assertValueEqual(releases.get(), 1, "failure releases once");
                helper.assertValueEqual(endings.get(), 1, "failure ends once");
                helper.succeed();
            } finally {
                h.close();
            }
        });
    }

    @GameTest(template = "empty")
    public static void controllerDeadlineHardEndsWithoutRenewing(GameTestHelper helper) throws Exception {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            Node node = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            h.start(node);
            TestHolding probe = install(h);
            probe.cooperates = false;
            h.runner.controllerTakeover(h.level, h.body, false);
            long deadline = probe.request.deadlineTick();
            h.runner.controllerTakeover(h.level, h.body, false);
            helper.assertValueEqual(probe.requests, 1, "continuing control does not renew cleanup");
            h.runner.checkDeadline(h.level, h.body, deadline - 1, true);
            helper.assertValueEqual(probe.hard, 0, "cleanup runs until its absolute deadline");
            h.runner.checkDeadline(h.level, h.body, deadline, true);
            helper.assertTrue(probe.hard == 1 && probe.result == Disposition.END
                && probe.reason == PreemptReason.CONTROLLER, "the deadline hard ends controller cleanup");
            h.assertEmpty(node);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void suspendedPersonalWorkResumesBeforeLowerPriorityIdleWork(GameTestHelper helper) throws Exception {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            Node guardNode = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            Urge guard = new Urge(HoldingGameTests.OWNER, 1, true, () -> guardNode);
            HoldingGameTests.OFFERS.put(h.colony.id(), List.of(guard));
            h.interrupt();
            TestHolding probe = install(h);
            Node supplyNode = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            Urge supply = new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "supply"), 2, true,
                () -> supplyNode).asUrgent();
            HoldingGameTests.OFFERS.put(h.colony.id(), List.of(guard, supply));
            h.interrupt();
            Node idleNode = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            Urge idle = new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "idle"), .1, false,
                () -> idleNode);
            HoldingGameTests.OFFERS.put(h.colony.id(), List.of(guard, idle));
            h.interrupt();
            helper.assertValueEqual(probe.resumptions, 1, "withdrawn supply work returns the body to the same guard");
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(guard.id()),
                "lower priority idle work cannot starve a suspended personal holding");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void urgentUrgePreservesPendingControllerEnd(GameTestHelper helper) throws Exception {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            Node node = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            h.start(node);
            TestHolding probe = install(h);
            probe.cooperates = false;
            h.runner.controllerTakeover(h.level, h.body, false);
            Node fearNode = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            Urge fear = new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "fear"), 2, true,
                () -> fearNode).asUrgent();
            HoldingGameTests.OFFERS.put(h.colony.id(), List.of(fear));
            h.interrupt();
            helper.assertTrue(probe.hard == 1 && probe.result == Disposition.END
                && probe.reason == PreemptReason.CONTROLLER, "urgent fear escalates the pending controller end");
            helper.assertTrue(h.labor.execution().holdings().suspended(node.id()).isEmpty(),
                "an urgent urge cannot suspend work that control already abandoned");
            h.assertHeld(fearNode);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void controllerEndCleansUpPrivateActionAndRequeuesWork(GameTestHelper helper) throws Exception {
        for (boolean ordered : List.of(false, true)) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                AtomicInteger commits = new AtomicInteger();
                AtomicInteger pieces = new AtomicInteger();
                AtomicInteger releases = new AtomicInteger();
                Node piece = h.node(Workload.Once.of(100), commits, false);
                NodeSpec spec =
                        NodeSpec.of(
                            UUID.randomUUID(),
                            HoldingGameTests.OWNER,
                            WorkSite.at(h.level, h.at),
                            Stances.WHEREVER,
                            new Workload.Custom())
                                .done();
                Node outer =
                        new Node() {
                    public NodeSpec spec() {
                                return spec;
                            }

                            public WorkHolding acquire(HoldingContext context) {
                        pieces.incrementAndGet();
                        return new SequentialHolding(context, List.of(piece), releases::incrementAndGet);
                    }

                    public Outcome commit(ServerLevel level, Worker who) {
                                return Outcome.done();
                    }
                };
                h.start(outer);
                h.labor.tick(h.runner, h.level, h.body);
                helper.assertTrue(h.colony.isHeld(outer.id()) && !h.labor.execution().personal().knows(piece.id()),
                    "the private action has no holding or personal phase");
                h.runner.controllerTakeover(h.level, h.body, false);
                if (ordered) {
                    h.colony.controllerOrder(h.body);
                } else {
                    h.labor.tickChecked(h.runner, h.level, h.body);
                    h.labor.tickChecked(h.runner, h.level, h.body);
                }
                helper.assertValueEqual(releases.get(), 1, "the outer work hears its piece end once");
                helper.assertValueEqual(pieces.get(), 1, "cleanup never starts another piece");
                helper.assertValueEqual(commits.get(), 0, "cleanup starts no production");
                h.assertEmpty(outer);
                helper.assertTrue(h.labor.state(outer.id()).orElseThrow() == NodeState.PENDING,
                    "the outer node returns to planning after its holding ends");
            }
        }
        helper.succeed();
    }

    private static Node trackedNode(HoldingGameTests.Harness h, AtomicBoolean fail, AtomicInteger releases,
            AtomicInteger endings) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), HoldingGameTests.OWNER, WorkSite.at(h.level, h.at),
            Stances.WHEREVER, Workload.Once.of(100)).done();
        return new Node() {
            public NodeSpec spec() {
                return spec;
            }

            public Outcome commit(ServerLevel level, Worker who) {
                return Outcome.done();
            }

            public Optional<RefusalKind> refusal(ServerLevel level) {
                return fail.get() ? Optional.of(LaborRefusal.WORK_BROKE) : Optional.empty();
            }

            public void released(ServerLevel level, Worker who, Ending how) {
                releases.incrementAndGet();
            }

            public void ended(ServerLevel level, Ending how) {
                endings.incrementAndGet();
            }
        };
    }

    private static TestHolding install(HoldingGameTests.Harness h) throws ReflectiveOperationException {
        TestHolding probe = new TestHolding();
        var field = BodyRunner.class.getDeclaredField("execution");
        field.setAccessible(true);
        field.set(h.runner, probe);
        return probe;
    }

}
