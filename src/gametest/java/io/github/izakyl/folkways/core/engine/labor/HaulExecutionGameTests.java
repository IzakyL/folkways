package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonySnapshot;
import io.github.izakyl.folkways.core.engine.plan.Asks;
import io.github.izakyl.folkways.core.engine.plan.Diagnosis;
import io.github.izakyl.folkways.core.engine.plan.Schedule;
import io.github.izakyl.folkways.core.engine.plan.Solved;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.Weave;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.walk.OnFoot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HaulExecutionGameTests {
    private static final ResourceLocation TEST_URGE = ResourceLocation.fromNamespaceAndPath("folkways_test", "lifecycle");
    private static final Map<UUID, io.github.izakyl.folkways.core.api.work.Urge> REQUESTS = new java.util.HashMap<>();

    @SubscribeEvent
    public static void declare(io.github.izakyl.folkways.core.api.Declaring event) {
        event.urges(TEST_URGE, (colony, who, view) -> {
            var urge = REQUESTS.get(colony.id());
            return urge == null ? List.of() : List.of(urge);
        });
        io.github.izakyl.folkways.core.api.participation.Participations.register(PersonBody.ID,
            io.github.izakyl.folkways.core.api.participation.Stake.urge(TEST_URGE),
            io.github.izakyl.folkways.core.api.participation.Participation.ALWAYS);
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(HaulExecutionGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void consumerWaitsForItsPredecessorToFinish(GameTestHelper helper) throws ReflectiveOperationException {
        var level = helper.getLevel();
        Node fetch = node(level, helper.absolutePos(BlockPos.ZERO));
        Node build = node(level, helper.absolutePos(new BlockPos(1, 0, 0)));
        var weave = new Weave(Map.of(fetch.id(), new Vertex(fetch.id(), fetch, Placement.NOWHERE),
            build.id(), new Vertex(build.id(), build, Placement.NOWHERE)),
            List.of(new Before(fetch.id(), build.id())), List.of());
        ColonyLabor labor = labor(level);
        var apply = ColonyLabor.class.getDeclaredMethod("apply", ServerLevel.class, List.class, Solved.class);
        apply.setAccessible(true);
        apply.invoke(labor, level, List.of(), new Solved(weave, Schedule.EMPTY, Diagnosis.NOTHING, Asks.NONE));
        helper.assertTrue(labor.takeable(fetch.id(), level).isPresent(), "fetch must be ready first");
        helper.assertTrue(labor.takeable(build.id(), level).isEmpty(), "build cannot overtake pending fetch");
        labor.phases().started(fetch.id());
        helper.assertTrue(labor.takeable(build.id(), level).isEmpty(), "build must wait while fetch runs");
        labor.phases().letGo(fetch.id());
        helper.assertTrue(labor.takeable(build.id(), level).isEmpty(), "failed fetch must not unblock build");
        helper.assertTrue(labor.takeable(fetch.id(), level).isPresent(), "released work can be retried");
        labor.phases().started(fetch.id());
        labor.phases().handedOver(fetch.id());
        helper.assertTrue(labor.takeable(build.id(), level).isEmpty(), "settling fetch is not yet complete");
        labor.phases().finished(fetch.id());
        helper.assertTrue(labor.takeable(build.id(), level).isPresent(), "finished fetch unblocks build");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void walkingRetriesAfterLandingAndReleasesItsNavigation(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos from = helper.absolutePos(new BlockPos(1, 1, 1));
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 3; z++) {
                BlockPos floor = from.offset(x - 1, -1, z - 1);
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
            }
        }
        var body = PersonBody.RESIDENT.get().create(level);
        body.setPos(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
        body.setOnGround(false);
        var goals = Set.of(WorldPos.of(level, from.offset(2, 0, 0)));
        OnFoot walking = new OnFoot(1.0, LaborRefusal.NO_WAY_THERE);
        walking.setOut(level, body, goals);
        walking.step(level, body, goals);
        helper.assertTrue(body.getNavigation().getPath() == null, "airborne navigation should not have a path yet");
        body.setOnGround(true);
        for (int tick = 0; tick < 5; tick++) {
            walking.step(level, body, goals);
        }
        helper.assertTrue(!body.getNavigation().isDone(), "landing must retry navigation before timing out");
        walking.release(level, body);
        helper.assertTrue(body.getNavigation().isDone(), "cancelled walking must release its old path");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void acceptingAPlanRunsOnceAndARejectedNodeCannotExecute(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        NodeSpec spec = node(level, helper.absolutePos(BlockPos.ZERO)).spec();
        AtomicInteger calls = new AtomicInteger();
        Node rejected = new Node() {
            public NodeSpec spec() { return spec; }
            public Optional<RefusalKind> planned(ServerLevel ignored) {
                calls.incrementAndGet();
                return Optional.of(LaborRefusal.WORK_BROKE);
            }
            public Outcome commit(ServerLevel ignored, Worker worker) {
                throw new AssertionError("a rejected plan cannot execute");
            }
        };
        var weave = new Weave(Map.of(rejected.id(), new Vertex(rejected.id(), rejected, Placement.NOWHERE)),
            List.of(), List.of());
        ColonyLabor labor = labor(level);
        var solved = new Solved(weave, Schedule.EMPTY, Diagnosis.NOTHING, Asks.NONE);
        var apply = ColonyLabor.class.getDeclaredMethod("apply", ServerLevel.class, List.class, Solved.class);
        apply.setAccessible(true);
        apply.invoke(labor, level, List.of(), solved);
        apply.invoke(labor, level, List.of(), solved);
        helper.assertTrue(calls.get() == 1, "acceptance belongs to node entry, not each planning round");
        helper.assertTrue(labor.takeable(rejected.id(), level).isEmpty(),
            "even a node whose default readiness is true must not execute after rejecting its plan");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void residentCanLeaveBeforeTheResultSettles(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        List<NodeState> seen = new ArrayList<>();
        AtomicBoolean settled = new AtomicBoolean();
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger endings = new AtomicInteger();
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("test"),
            WorkSite.at(level, helper.absolutePos(BlockPos.ZERO)), Stances.WHEREVER,
            Workload.Once.of(0)).done();
        Node work = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
            public boolean settled(ServerLevel at) { return settled.get(); }
            public void changed(ServerLevel at, NodeState state) { seen.add(state); }
            public void released(ServerLevel at, Worker who, Ending why) { releases.incrementAndGet(); }
            public void ended(ServerLevel at, Ending why) { endings.incrementAndGet(); }
        };
        Node next = node(level, helper.absolutePos(BlockPos.ZERO));
        ColonyLabor labor = labor(level);
        var weave = new Weave(Map.of(work.id(), new Vertex(work.id(), work, Placement.NOWHERE),
            next.id(), new Vertex(next.id(), next, Placement.NOWHERE)),
            List.of(new Before(work.id(), next.id())), List.of());
        apply(labor, level, weave);
        var body = PersonBody.RESIDENT.get().create(level);
        BodyRunner runner = new BodyRunner(body.getUUID());
        runner.follow(List.of(work.id()));
        runner.tick(labor, level, body);
        runner.tick(labor, level, body);
        helper.assertTrue(runner.holding().isEmpty(), "resident must be free while result settles");
        helper.assertTrue(releases.get() == 1 && endings.get() == 0,
            "releasing labor must not report node success");
        helper.assertTrue(labor.state(work.id()).orElseThrow() == NodeState.SETTLING,
            "completed resident labor is SETTLING");
        helper.assertTrue(labor.takeable(next.id(), level).isEmpty(), "successor waits for result");
        helper.assertTrue(!labor.phases().settled(work, level), "result is not ready yet");
        settled.set(true);
        helper.assertTrue(labor.phases().settled(work, level), "result can finish without resident");
        helper.assertTrue(labor.takeable(next.id(), level).isPresent(), "successful result unblocks successor");
        helper.assertTrue(!labor.phases().settled(work, level) && endings.get() == 1,
            "terminal notification must run once");
        helper.assertTrue(seen.equals(List.of(NodeState.PENDING, NodeState.READY, NodeState.WORKING,
            NodeState.SETTLING, NodeState.DONE)), "all lifecycle transitions must be observable");
        labor.close();
        body.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void continuousWorkAlsoWaitsForItsResult(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("test"),
            WorkSite.at(level, helper.absolutePos(BlockPos.ZERO)), Stances.WHEREVER,
            (Workload.Continuous) (at, who) -> false).done();
        Node work = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
            public boolean settled(ServerLevel at) { return false; }
        };
        ColonyLabor labor = labor(level);
        apply(labor, level, new Weave(Map.of(work.id(), new Vertex(work.id(), work, Placement.NOWHERE)),
            List.of(), List.of()));
        var body = PersonBody.RESIDENT.get().create(level);
        BodyRunner runner = new BodyRunner(body.getUUID());
        runner.follow(List.of(work.id()));
        runner.tick(labor, level, body);
        runner.tick(labor, level, body);
        helper.assertTrue(labor.state(work.id()).orElseThrow() == NodeState.SETTLING,
            "continuous workload must not bypass result settlement");
        helper.assertTrue(runner.holding().isEmpty(), "continuous worker must be released");
        labor.close();
        body.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedAndCancelledNodesDoNotRunAgain(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        AtomicInteger endings = new AtomicInteger();
        NodeSpec spec = node(level, helper.absolutePos(BlockPos.ZERO)).spec();
        Node work = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
            public boolean settled(ServerLevel at) { throw new IllegalStateException("machine broke"); }
            public void ended(ServerLevel at, Ending why) { endings.incrementAndGet(); }
        };
        Phases phases = new Phases();
        phases.entered(work, level);
        phases.ready(work, level);
        phases.started(work.id());
        phases.handedOver(work.id());
        helper.assertTrue(!phases.settled(work, level), "broken result is not successful");
        helper.assertTrue(phases.of(work.id()) == NodeState.FAILED, "settlement failure is terminal");
        phases.letGo(work.id());
        helper.assertTrue(!phases.ready(work, level), "failure cannot reset to pending");
        phases.retain(Set.of());
        helper.assertTrue(endings.get() == 1, "pruning failure must not notify again");

        List<NodeState> cancelled = new ArrayList<>();
        Node waiting = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
            public void changed(ServerLevel at, NodeState state) { cancelled.add(state); }
        };
        phases.entered(waiting, level);
        phases.retain(Set.of());
        helper.assertTrue(cancelled.equals(List.of(NodeState.PENDING, NodeState.CANCELLED)),
            "removing unstarted work must cancel its lifetime");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aFailureItsOwnerWaitsOutKeepsTheNodeTillTheWaitIsUp(GameTestHelper helper) {
        var level = helper.getLevel();
        AtomicReference<RefusalKind> refused = new AtomicReference<>(LaborRefusal.NOWHERE_TO_PUT_IT);
        AtomicInteger asked = new AtomicInteger();
        AtomicInteger endings = new AtomicInteger();
        NodeSpec spec = node(level, helper.absolutePos(BlockPos.ZERO)).spec();
        Node work = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
            public Optional<RefusalKind> refusal(ServerLevel at) { return Optional.ofNullable(refused.get()); }
            public Recourse failing(ServerLevel at, RefusalKind why) {
                asked.incrementAndGet();
                return why == LaborRefusal.NOWHERE_TO_PUT_IT ? Recourse.waitFor(20) : Recourse.FAIL;
            }
            public void ended(ServerLevel at, Ending why) { endings.incrementAndGet(); }
        };
        Phases phases = new Phases((node, how) -> { });
        helper.assertTrue(!phases.ready(work, level), "a node its owner waits on is not ready");
        helper.assertTrue(phases.of(work.id()) == NodeState.PENDING && phases.failure(work.id()).isEmpty(),
            "waiting is not failing");
        helper.assertTrue(!phases.check(work.id()) && asked.get() == 1, "the owner is asked once a wait");
        refused.set(LaborRefusal.GOODS_GONE);
        helper.runAfterDelay(21, () -> {
            helper.assertTrue(!phases.ready(work, level) && phases.of(work.id()) == NodeState.FAILED,
                "once the wait is up, a failure the owner does not wait on ends the node");
            helper.assertTrue(asked.get() == 2 && endings.get() == 1, "it ends once, asked again");
            Phases personal = new Phases();
            refused.set(LaborRefusal.NOWHERE_TO_PUT_IT);
            NodeSpec ownSpec = node(level, helper.absolutePos(BlockPos.ZERO)).spec();
            Node own = new Node() {
                public NodeSpec spec() { return ownSpec; }
                public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
                public Optional<RefusalKind> refusal(ServerLevel at) { return Optional.of(refused.get()); }
                public Recourse failing(ServerLevel at, RefusalKind why) { return Recourse.waitFor(20); }
            };
            helper.assertTrue(!personal.ready(own, level) && personal.of(own.id()) == NodeState.FAILED,
                "a personal urge's work is never waited on");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void readyConditionsAreRecheckedBeforeTakingWork(GameTestHelper helper) {
        var level = helper.getLevel();
        AtomicBoolean ready = new AtomicBoolean(true);
        NodeSpec spec = node(level, helper.absolutePos(BlockPos.ZERO)).spec();
        Node work = new Node() {
            public NodeSpec spec() { return spec; }
            public boolean ready(ServerLevel at) { return ready.get(); }
            public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
        };
        Phases phases = new Phases();
        helper.assertTrue(phases.ready(work, level), "initial readiness");
        ready.set(false);
        helper.assertTrue(!phases.ready(work, level) && phases.of(work.id()) == NodeState.PENDING,
            "cached readiness cannot outlive its world condition");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void directWorkPreservesDependenciesAndRejectsCycles(GameTestHelper helper) {
        var level = helper.getLevel();
        Node harvest = node(level, helper.absolutePos(BlockPos.ZERO));
        Node replant = node(level, helper.absolutePos(BlockPos.ZERO));
        Grown graph = Grown.then(harvest, replant);
        Grown goal = graph.ranked(2);
        helper.assertTrue(goal.nodes().equals(graph.nodes()) && goal.links().equals(graph.links())
            && goal.rank() == 2, "ranking a goal preserves its DAG");
        boolean rejected = false;
        try {
            new Grown(List.of(harvest, replant), List.of(new Before(harvest.id(), replant.id()),
                new Before(replant.id(), harvest.id())));
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "work cycles must be rejected before planning");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void directDagIsExpandedAndScheduledByThePlanner(GameTestHelper helper) {
        var fixture = new io.github.izakyl.folkways.core.engine.plan.PlanningFixture(helper);
        var level = helper.getLevel();
        Node first = node(level, fixture.source.cell());
        Node second = node(level, fixture.target.cell());
        Grown goal = Grown.then(first, second);
        var solver = new io.github.izakyl.folkways.core.engine.plan.PlanningFixture.Rounds();
        var stock = new io.github.izakyl.folkways.core.engine.plan.Stock(List.of(), Map.of());
        var solved = solver.solve(fixture.situation(stock, List.of()), fixture.crew,
            List.of(goal), List.of(), level.getGameTime());
        helper.assertTrue(solved.weave().vertices().containsKey(first.id())
            && solved.weave().vertices().containsKey(second.id()), "both direct nodes must enter the graph");
        var tour = solved.schedule().tours().values().stream()
            .filter(route -> route.nodes().contains(first.id())).findFirst().orElseThrow();
        helper.assertTrue(tour.nodes().indexOf(first.id()) < tour.nodes().indexOf(second.id()),
            "planner must schedule direct DAG dependencies in order");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void urgesSettleWithoutDuplicatingTheResidentRequest(GameTestHelper helper)
            throws ReflectiveOperationException {
        var level = helper.getLevel();
        var colony = io.github.izakyl.folkways.core.api.colony.Colonies.mint(level.getServer());
        var data = io.github.izakyl.folkways.core.engine.colony.ColonyData.find(level.getServer(), colony.id()).orElseThrow();
        ColonyLabor labor = new ColonyLabor(data, level.dimension());
        var body = PersonBody.RESIDENT.get().create(level);
        BodyRunner runner = new BodyRunner(body.getUUID());
        AtomicBoolean settled = new AtomicBoolean();
        AtomicInteger takes = new AtomicInteger();
        AtomicInteger ends = new AtomicInteger();
        REQUESTS.put(colony.id(), new io.github.izakyl.folkways.core.api.work.Urge(TEST_URGE, 2.0, true, () -> {
            takes.incrementAndGet();
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), TEST_URGE,
                WorkSite.at(level, helper.absolutePos(BlockPos.ZERO)), Stances.WHEREVER,
                Workload.Once.of(0)).done();
            return new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
                public boolean settled(ServerLevel at) { return settled.get(); }
                public void ended(ServerLevel at, Ending why) { ends.incrementAndGet(); }
            };
        }));
        try {
            labor.run(level, task -> { });
            var view = ColonySnapshot.of(data, level);
            var interrupt = ColonyLabor.class.getDeclaredMethod("interrupt", ServerLevel.class,
                ColonyView.class, io.github.izakyl.folkways.core.api.resident.body.Body.class, BodyRunner.class);
            interrupt.setAccessible(true);
            var settle = ColonyLabor.class.getDeclaredMethod("settle", ServerLevel.class);
            settle.setAccessible(true);
            interrupt.invoke(labor, level, view, body, runner);
            runner.tick(labor, level, body);
            helper.assertTrue(!runner.ownBusiness(), "urge releases resident while result is pending");
            interrupt.invoke(labor, level, view, body, runner);
            settle.invoke(labor, level);
            helper.assertTrue(takes.get() == 1 && ends.get() == 0,
                "pending result must neither succeed early nor duplicate the same request");
            settled.set(true);
            settle.invoke(labor, level);
            helper.assertTrue(ends.get() == 1, "urge succeeds after settlement without a worker");
            interrupt.invoke(labor, level, view, body, runner);
            helper.assertTrue(takes.get() == 2, "a completed urge permits a new request");
            runner.letGo(labor, level, body);
            helper.assertTrue(ends.get() == 2, "interrupted personal work must receive cancellation");
        } finally {
            REQUESTS.remove(colony.id());
            labor.close();
            body.discard();
            colony.raze();
        }
        helper.succeed();
    }

    private static void apply(ColonyLabor labor, ServerLevel level, Weave weave)
            throws ReflectiveOperationException {
        var apply = ColonyLabor.class.getDeclaredMethod("apply", ServerLevel.class, List.class, Solved.class);
        apply.setAccessible(true);
        apply.invoke(labor, level, List.of(), new Solved(weave, Schedule.EMPTY, Diagnosis.NOTHING, Asks.NONE));
    }

    private static ColonyLabor labor(ServerLevel level) {
        return new ColonyLabor(new ColonyData(), level.dimension());
    }

    private static Node node(ServerLevel level, BlockPos pos) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("test"),
            new WorkSite.AtBlock(WorldPos.of(level, pos)), Stances.at(WorldPos.of(level, pos)),
            Workload.Once.of(1, who -> 1.0)).done();
        return new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel ignored, Worker who) { return Outcome.done(); }
        };
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workThatJoinsTheWorkJustDoneSharesItsWindUp(GameTestHelper helper)
            throws ReflectiveOperationException {
        int alone = ticksForTwo(helper, false);
        int joined = ticksForTwo(helper, true);
        helper.assertTrue(alone >= 40 && alone < 100, "each step winds up on its own, got " + alone);
        helper.assertTrue(joined <= 24, "the joining step goes on in the first one's wind-up, got " + joined);
        helper.succeed();
    }

    // Two twenty-tick steps in a row, the second joining the first or not; how many ticks until both are done.
    private static int ticksForTwo(GameTestHelper helper, boolean joins) throws ReflectiveOperationException {
        var level = helper.getLevel();
        AtomicInteger done = new AtomicInteger();
        List<Node> steps = new ArrayList<>();
        for (int at = 0; at < 2; at++) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("test"),
                WorkSite.at(level, helper.absolutePos(BlockPos.ZERO)), Stances.WHEREVER,
                Workload.Once.of(20)).done();
            Node first = steps.isEmpty() ? null : steps.getFirst();
            steps.add(new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel ignored, Worker who) {
                    done.incrementAndGet();
                    return Outcome.done();
                }
                public boolean joins(Node before) { return joins && before == first; }
            });
        }
        ColonyLabor labor = labor(level);
        apply(labor, level, new Weave(Map.of(
            steps.get(0).id(), new Vertex(steps.get(0).id(), steps.get(0), Placement.NOWHERE),
            steps.get(1).id(), new Vertex(steps.get(1).id(), steps.get(1), Placement.NOWHERE)),
            List.of(new Before(steps.get(0).id(), steps.get(1).id())), List.of()));
        var body = PersonBody.RESIDENT.get().create(level);
        BodyRunner runner = new BodyRunner(body.getUUID());
        runner.follow(steps.stream().map(Node::id).toList());
        int ticks = 0;
        while (done.get() < 2 && ticks < 100) {
            runner.tick(labor, level, body);
            ticks++;
            if (done.get() == 1) {
                labor.phases().settled(steps.get(0), level);
            }
        }
        labor.close();
        body.discard();
        return ticks;
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void durableProductsAreCargoButVocationToolsStayEquipped(GameTestHelper helper) {
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        var body = io.github.izakyl.folkways.core.api.resident.body.Bodies.of(resident).orElseThrow();
        var kit = Kit.of(new io.github.izakyl.folkways.core.engine.colony.ColonyData().works(), body);
        var armor = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_CHESTPLATE);
        armor.setDamageValue(137);
        var axe = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE);
        helper.assertTrue(!kit.holds(armor), "durability alone does not make production into personal equipment");
        helper.assertTrue(kit.holds(axe), "declared vocation tools remain equipped");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aToolOffTheColonyListIsCarriedAsCargo(GameTestHelper helper) {
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        var body = io.github.izakyl.folkways.core.api.resident.body.Bodies.of(resident).orElseThrow();
        var colony = new io.github.izakyl.folkways.core.engine.colony.ColonyData().works();
        var front = io.github.izakyl.folkways.front.engine.colony.ColonyFront.of(colony);
        helper.assertTrue(front.setSetting(io.github.izakyl.folkways.front.api.CoreSettings.CORE,
                io.github.izakyl.folkways.front.api.CoreSettings.TOOL_KEY,
                new io.github.izakyl.folkways.front.engine.colony.ColonySettings.Value.Items(List.of(
                    io.github.izakyl.folkways.core.api.terms.ItemFilter.item(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace("stone_axe"))))),
            "the colony's tool list should take a narrower list");
        var kit = Kit.of(colony, body);
        var iron = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_AXE);
        var stone = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE_AXE);
        helper.assertTrue(!kit.holds(iron), "a tool the colony took off its list is cargo, to be put away");
        helper.assertTrue(kit.holds(stone), "a tool still on the list stays equipped");
        helper.succeed();
    }

}
