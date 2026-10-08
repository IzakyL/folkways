package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonySnapshot;
import io.github.izakyl.folkways.core.engine.plan.Asks;
import io.github.izakyl.folkways.core.engine.plan.Diagnosis;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Schedule;
import io.github.izakyl.folkways.core.engine.plan.Solved;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.Weave;
import io.github.izakyl.folkways.plugins.person.PersonBody;
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

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UrgePreemptionGameTests {
    private static final ResourceLocation OWNER = id("preemption");
    private static final Map<UUID, List<Urge>> OFFERS = new HashMap<>();

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.urges(OWNER, (colony, who, view) -> OFFERS.getOrDefault(colony.id(), List.of()));
        Participations.register(PersonBody.ID, Stake.urge(OWNER), Participation.ALWAYS);
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(UrgePreemptionGameTests.class);
    }

    @GameTest(template = "empty")
    public static void idleChoiceUsesWeightWithoutPreferringPreemption(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            Urge light = h.urge("light", 0.01, true);
            Urge heavy = h.urge("heavy", 100, false);
            h.offers(light, heavy);
            h.interrupt();
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(heavy.id()),
                "weight chooses idle work, regardless of the preemption flag");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void onlyExplicitPreemptionCanReplaceFiniteOrContinuousWork(GameTestHelper helper) throws Exception {
        for (Workload load : List.of(Workload.Once.of(1000),
                (Workload.Continuous) (level, who) -> true)) {
            try (Harness h = new Harness(helper)) {
                AtomicInteger released = new AtomicInteger();
                Node work = h.node(load, true, released);
                h.start(work);
                Urge heavy = h.urge("heavy", 100, false);
                h.offers(heavy);
                h.interrupt();
                helper.assertTrue(h.runner.holding().orElseThrow().equals(work.id()) && released.get() == 0,
                    "a high weight cannot interrupt either type of running node without permission");
                Urge light = h.urge("light", 0.01, true);
                h.offers(heavy, light);
                h.interrupt();
                helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(light.id()) && released.get() == 1,
                    "filter ineligible choices before ranking; a low-weight permitted urge can preempt");
                helper.assertTrue(h.labor.state(work.id()).orElseThrow() == NodeState.PENDING,
                    "interrupted colony work remains retryable");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void personalWorkCanBePreemptedWithoutOscillating(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            Urge first = h.urge("first", 0.1, false);
            h.offers(first);
            h.interrupt();
            Urge forbidden = h.urge("higher", 100, false);
            h.offers(first, forbidden);
            h.interrupt();
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(first.id()),
                "weight alone cannot interrupt personal work either");
            Urge next = h.urge("next", 0.2, true);
            h.offers(first, next);
            h.interrupt();
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(next.id()),
                "a permitted higher-weight choice must interrupt personal work, even below the old threshold");
            h.offers(h.urge("lower", 0.1, true), h.urge("equal", 0.2, true), next);
            h.interrupt();
            helper.assertTrue(h.runner.ownUrge().orElseThrow().equals(next.id()),
                "lower or tied weights cannot cause personal tasks to alternate");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withdrawnPersonalWorkYieldsToQueuedWork(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            h.offers(h.urge("wander", 100, false));
            h.interrupt();
            Node job = h.node(Workload.Once.of(1000), true, new AtomicInteger());
            h.queue(job);
            h.offers();
            h.interrupt();
            h.runner.tick(h.labor, h.level, h.body);
            helper.assertTrue(h.runner.holding().orElseThrow().equals(job.id()),
                "withdrawn idle work yields without relying on a magic weight threshold");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unreadyPreemptorDoesNotReleaseCurrentWork(GameTestHelper helper) throws Exception {
        try (Harness h = new Harness(helper)) {
            AtomicInteger released = new AtomicInteger();
            Node job = h.node(Workload.Once.of(1000), true, released);
            h.start(job);
            Node unready = h.node(Workload.Once.of(1000), false, new AtomicInteger());
            h.offers(new Urge(id("unready"), 100, true, () -> unready));
            h.interrupt();
            helper.assertTrue(h.runner.holding().orElseThrow().equals(job.id()) && released.get() == 0,
                "preemption commits only after the replacement is ready");
        }
        helper.succeed();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("folkways_test", path);
    }

    private static final class Harness implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ColonyLabor labor;
        final ColonyView view;
        final Body body;
        final BodyRunner runner;
        final BlockPos at;

        Harness(GameTestHelper helper) {
            level = helper.getLevel();
            at = helper.absolutePos(BlockPos.ZERO);
            colony = Colonies.mint(level.getServer());
            ColonyData data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            labor = new ColonyLabor(data, level.dimension());
            view = ColonySnapshot.of(data, level);
            body = PersonBody.RESIDENT.get().create(level);
            runner = new BodyRunner(body.resident().id());
            labor.run(level, task -> { });
        }

        Urge urge(String name, double weight, boolean preempts) {
            return new Urge(id(name), weight, preempts,
                () -> node(Workload.Once.of(1000), true, new AtomicInteger()));
        }

        Node node(Workload load, boolean ready, AtomicInteger releases) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER,
                WorkSite.at(level, at), Stances.WHEREVER, load).done();
            return new Node() {
                public NodeSpec spec() { return spec; }
                public boolean ready(ServerLevel level) { return ready; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
                public void released(ServerLevel level, Worker who, Ending why) { releases.incrementAndGet(); }
            };
        }

        void offers(Urge... offered) {
            OFFERS.put(colony.id(), List.of(offered));
        }

        void queue(Node node) {
            Weave weave = new Weave(Map.of(node.id(), new Vertex(node.id(), node, Placement.NOWHERE)),
                List.of(), List.of());
            PlanningFixture.apply(labor, level, new Solved(weave, Schedule.EMPTY, Diagnosis.NOTHING, Asks.NONE));
            runner.follow(List.of(node.id()));
        }

        void start(Node node) {
            queue(node);
            runner.tick(labor, level, body);
        }

        void interrupt() throws ReflectiveOperationException {
            var method = ColonyLabor.class.getDeclaredMethod("interrupt", ServerLevel.class, ColonyView.class,
                Body.class, BodyRunner.class);
            method.setAccessible(true);
            method.invoke(labor, level, view, body, runner);
        }

        public void close() {
            OFFERS.remove(colony.id());
            runner.letGo(labor, level, body);
            labor.close();
            body.mob().discard();
            colony.raze();
        }
    }
}
