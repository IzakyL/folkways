package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.colony.internal.ColonySnapshot;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Shown;
import io.github.izakyl.folkways.core.plan.internal.Vertex;
import io.github.izakyl.folkways.core.plan.internal.WeaveDelta;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.work.internal.HoldingRegistry;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HoldingGameTests {
    static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_core", "holding_tests");
    static final Map<UUID, List<Urge>> OFFERS = new HashMap<>();

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.urges(OWNER, (colony, who, view) -> OFFERS.getOrDefault(colony.id(), List.of()));
        Participations.register(PersonBody.ID, Stake.urge(OWNER), Participation.ALWAYS);
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(HoldingGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void holdingTracksCompletionFailureAndLetGo(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            AtomicInteger commits = new AtomicInteger();
            Node done = h.node(Workload.Once.of(0), commits, false);
            h.start(done);
            long first = h.assertHeld(done);
            h.labor.tick(h.runner, h.level, h.body);
            h.assertEmpty(done);
            helper.assertValueEqual(commits.get(), 1, "finite work commits once");

            Node failed = h.node(Workload.Once.of(0), commits, true);
            h.start(failed);
            long second = h.assertHeld(failed);
            h.labor.tick(h.runner, h.level, h.body);
            h.assertEmpty(failed);
            helper.assertValueEqual(commits.get(), 2, "failed work commits once");

            Node dropped = h.node(Workload.Once.of(1000), commits, false);
            h.start(dropped);
            long third = h.assertHeld(dropped);
            h.runner.letGo(h.level, h.body);
            h.assertEmpty(dropped);
            helper.assertTrue(first < second && second < third,
                "every acquisition advances the generation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void urgeTakesAndReleasesActiveHolding(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            Node work = h.node(Workload.Once.of(1000), new AtomicInteger(), false);
            h.start(work);
            long first = h.assertHeld(work);
            Node urgeNode = h.node(Workload.Once.of(1000), new AtomicInteger(), false);
            Urge urge = new Urge(OWNER, 100, true, () -> urgeNode);
            OFFERS.put(h.colony.id(), List.of(urge));
            h.interrupt();
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertTrue(!h.colony.isHeld(work.id()), "interrupted work no longer holds the body");
            long second = h.assertHeld(urgeNode);
            helper.assertTrue(second > first, "the urge receives a new generation");
            OFFERS.remove(h.colony.id());
            h.interrupt();
            helper.assertTrue(!h.colony.isHeld(urgeNode.id()), "the urge is no longer held");
            long resumed = h.assertHeld(work);
            helper.assertTrue(resumed > second && h.labor.state(work.id()).orElseThrow()
                    == io.github.izakyl.folkways.core.work.api.NodeState.WORKING,
                "the same node resumes in its working phase with a new generation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void completedUrgeReleasesItsHolding(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            AtomicInteger commits = new AtomicInteger();
            Node urgeNode = h.node(Workload.Once.of(0), commits, false);
            OFFERS.put(h.colony.id(), List.of(new Urge(OWNER, 100, false, () -> urgeNode)));
            h.interrupt();
            h.assertHeld(urgeNode);
            h.labor.tick(h.runner, h.level, h.body);
            h.assertEmpty(urgeNode);
            helper.assertValueEqual(commits.get(), 1, "a completed urge commits exactly once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void staleReleaseCannotClearNewGeneration(GameTestHelper helper) {
        HoldingRegistry registry = new HoldingRegistry();
        UUID resident = UUID.randomUUID();
        UUID node = UUID.randomUUID();
        HoldingRegistry.Held first = registry.acquire(resident, node, OWNER);
        registry.release(first);
        HoldingRegistry.Held second = registry.acquire(resident, node, OWNER);
        registry.release(first);
        helper.assertTrue(registry.holds(resident, node) && registry.byNode(node).orElseThrow().equals(second),
            "an old release cannot clear a newer holding");
        helper.assertTrue(second.generation() > first.generation(), "reacquisition advances the generation");
        helper.assertTrue(registry.suspended(node).isEmpty() && registry.suspendedWorks().isEmpty(),
            "active holdings are not suspended work");
        registry.release(second);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void privateActionRetainsOuterHoldingAcrossUrge(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            Node piece = h.node(Workload.Once.of(1000), new AtomicInteger(), false);
            NodeSpec outerSpec =
                    NodeSpec.of(
                        UUID.randomUUID(),
                        OWNER,
                        WorkSite.at(h.level, h.at),
                        Stances.WHEREVER,
                        new Workload.Custom())
                            .done();
            Node outer =
                    new Node() {
                        public NodeSpec spec() {
                            return outerSpec;
                        }

                        public WorkHolding acquire(HoldingContext context) {
                            return new SequentialHolding(context, List.of(piece), () -> {});
                        }

                        public Outcome commit(ServerLevel level, Worker who) {
                            return Outcome.done();
                        }
                    };
            h.start(outer);
            long first = h.assertHeld(outer);
            h.labor.tick(h.runner, h.level, h.body);
            long second = h.assertHeld(outer);
            helper.assertTrue(
                second == first && !h.colony.isHeld(piece.id()),
                "private actions retain the outer holding generation");
            Node urgeNode = h.node(Workload.Once.of(1000), new AtomicInteger(), false);
            OFFERS.put(h.colony.id(), List.of(new Urge(OWNER, 100, true, () -> urgeNode)));
            h.interrupt();
            h.labor.tick(h.runner, h.level, h.body);
            long third = h.assertHeld(urgeNode);
            helper.assertTrue(!h.colony.isHeld(piece.id()), "preemption releases the piece");
            OFFERS.remove(h.colony.id());
            h.interrupt();
            long fourth = h.assertHeld(outer);
            helper.assertTrue(first == second && second < third && third < fourth,
                "only urge takeover and outer reacquisition advance the generation");
            helper.assertTrue(!h.colony.isHeld(urgeNode.id()), "the completed urge is no longer held");
            h.labor.tick(h.runner, h.level, h.body);
            h.assertHeld(outer);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void standardHoldingsCommitOnlyOnce(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            for (Workload load : List.of(Workload.Once.of(0), (Workload.Continuous) (level, who) -> false)) {
                AtomicInteger commits = new AtomicInteger();
                Node node = h.node(load, commits, false);
                HoldingContext context = h.labor.execution().context(h.level, h.body, Placement.NOWHERE);
                WorkHolding holding = node.acquire(context);
                helper.assertTrue(holding.tick(context) instanceof HoldingStep.Done,
                    "each standard holding completes");
                helper.assertValueEqual(commits.get(), 1, "each standard holding commits exactly once");
                helper.assertTrue(holding.tick(context) instanceof HoldingStep.Done,
                    "completion remains stable");
                helper.assertValueEqual(commits.get(), 1, "a repeated tick does not commit again");
                h.assertEmpty(node);
            }
        }
        helper.succeed();
    }

    static final class Harness implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final Colony colony;
        final ColonyLabor labor;
        final ColonyView view;
        final Body body;
        final BodyRunner runner;
        final BlockPos at;

        Harness(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            at = helper.absolutePos(BlockPos.ZERO);
            colony = Colonies.mint(level.getServer());
            ColonyData data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            labor = new ColonyLabor(data, level.dimension());
            view = ColonySnapshot.of(data, level);
            body = PersonBody.RESIDENT.get().create(level);
            labor.run(level, new Lanes(task -> { }, task -> { }));
            try {
                var field = ColonyLabor.class.getDeclaredField("runners");
                field.setAccessible(true);
                runner = ((Runners) field.get(labor)).of(body.id());
            } catch (ReflectiveOperationException broken) {
                throw new AssertionError(broken);
            }
        }

        Node node(Workload load, AtomicInteger commits, boolean fail) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER,
                WorkSite.at(level, at), Stances.WHEREVER, load).done();
            return new Node() {
                public NodeSpec spec() {
                    return spec;
                }

                public Outcome commit(ServerLevel level, Worker who) {
                    commits.incrementAndGet();
                    return fail ? Outcome.failed(LaborRefusal.WORK_BROKE) : Outcome.done();
                }

            };
        }

        void start(Node node) {
            start(node, Placement.NOWHERE);
        }

        void start(Node node, Placement placement) {
            WeaveDelta weave = Shown.delta(Map.of(node.id(), new Vertex(node.id(), node, placement)),
                List.of(), List.of());
            PlanningFixture.apply(labor, level, List.of(), weave);
            runner.follow(List.of(node.id()));
            labor.tick(runner, level, body);
        }

        long assertHeld(Node node) {
            UUID resident = body.resident().id();
            HoldingRegistry.Held held = labor.execution().holdings().byNode(node.id()).orElseThrow();
            helper.assertTrue(labor.execution().holdings().holds(resident, node.id()), "registry holds the resident and node");
            helper.assertTrue(labor.execution().holdings().byResident(resident).orElseThrow().equals(held),
                "both registry indexes agree");
            helper.assertTrue(colony.isHeld(node.id()) && colony.holder(node.id()).orElseThrow().equals(resident),
                "plugins can query the holder");
            return held.generation();
        }

        void assertEmpty(Node node) {
            helper.assertTrue(labor.execution().holdings().byNode(node.id()).isEmpty()
                    && labor.execution().holdings().byResident(body.resident().id()).isEmpty()
                    && !colony.isHeld(node.id()) && colony.holder(node.id()).isEmpty(),
                "ended work is absent from both indexes and the plugin view");
        }

        void interrupt() throws ReflectiveOperationException {
            var method = ColonyLabor.class.getDeclaredMethod("interrupt", ServerLevel.class, ColonyView.class,
                Body.class, BodyRunner.class);
            method.setAccessible(true);
            method.invoke(labor, level, view, body, runner);
        }

        public void close() {
            OFFERS.remove(colony.id());
            runner.letGo(level, body);
            labor.close();
            body.mob().discard();
            colony.raze();
        }
    }
}
