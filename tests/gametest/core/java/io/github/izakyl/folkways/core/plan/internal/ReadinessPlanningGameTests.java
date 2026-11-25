package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.runtime.ColonyLabor;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Readiness;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.work.internal.Phases;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ReadinessPlanningGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.withDefaultNamespace("readiness_probe");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ReadinessPlanningGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void fitsOtherWorkBeforeAnEstimatedWait(GameTestHelper helper) {
        var fixture = new PlanningFixture(helper);
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        var labor = new ColonyLabor(new ColonyData(), level.dimension());
        var runner = new BodyRunner(body.getUUID(), labor.execution());
        var planner = new Solving();
        var commits = new ArrayList<UUID>();
        var waiting = new AtomicReference<Readiness>(Readiness.notYet(200));
        Node delayed = node(fixture, waiting::get, commits);
        Node available = node(fixture, () -> Readiness.READY, commits);
        var known = new Known(fixture.situation(new Stock(List.of(), Map.of()), List.of()), fixture.crew.hands());
        UUID resident = fixture.crew.hands().getFirst().id();
        long tick = level.getGameTime();
        try {
            Planned first = planner.handle(known, List.of(new Message.Submitted(OWNER, Grown.of(delayed))), tick);
            PlanningFixture.apply(labor, level, first);
            runner.follow(first.laid().all().get(resident).nodes());
            labor.tick(runner, level, body);
            helper.assertTrue(runner.holding().isEmpty(), "the first route waits at the delayed node");

            Planned second = planner.handle(known, List.of(new Message.NotReady(delayed.id(), tick + 200),
                new Message.Submitted(OWNER, Grown.of(available))), tick);
            List<UUID> route = second.laid().all().get(resident).nodes();
            helper.assertValueEqual(route, List.of(available.id(), delayed.id()), "work fills the wait");
            PlanningFixture.apply(labor, level, second);
            runner.follow(route);
            for (int n = 0; n < 20 && commits.isEmpty(); n++) {
                labor.tick(runner, level, body);
            }
            helper.assertValueEqual(commits, List.of(available.id()), "the resident does the available work first");
            waiting.set(Readiness.READY);
            for (int n = 0; n < 20 && commits.size() < 2; n++) {
                labor.tick(runner, level, body);
            }
            helper.assertValueEqual(commits, route, "the resident returns when the delayed work is ready");
        } finally {
            planner.closed();
            labor.close();
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void preservesPackCapacityWhenFillingAWait(GameTestHelper helper) {
        var fixture = new PlanningFixture(helper, 1);
        var base = fixture.crew.hands().getFirst();
        var saddle = ItemSpec.of(ResourceLocation.withDefaultNamespace("saddle"));
        var hand = new Crew.Hand(base.who(), base.at(), 0, 1, 1, base.licences(), List.of(),
            Optional.empty(), List.of(new ItemStack(Items.SADDLE)));
        Node delayed = node(fixture, () -> Readiness.notYet(200), new ArrayList<>());
        Node pickup = node(fixture, () -> Readiness.READY, new ArrayList<>());
        var weave = new WeaveView();
        var tours = new Tours();
        var crew = new Crew(List.of(hand));
        var readyTimes = Map.of(delayed.id(), 200L);
        var first = Shown.delta(Map.of(delayed.id(), carrying(delayed, saddle, -1)), List.of(), List.of());
        weave.apply(first);
        tours.schedule(weave, first.fresh(), crew, fixture.ways, 0, true, readyTimes);
        var next = Shown.delta(Map.of(pickup.id(), carrying(pickup, saddle, 1)), List.of(), List.of());
        weave.apply(next);
        Schedule schedule = tours.schedule(weave, next.fresh(), crew, fixture.ways, 1, true, readyTimes);
        helper.assertValueEqual(schedule.tours().get(hand.id()).nodes(), List.of(delayed.id(), pickup.id()),
            "the resident must free the occupied slot before picking up another saddle");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 4000)
    public static void unknownReadinessBacksOffAndResets(GameTestHelper helper) {
        var fixture = new PlanningFixture(helper);
        var reading = new AtomicReference<Readiness>(Readiness.notYet());
        Node node = node(fixture, reading::get, new ArrayList<>());
        var phases = new Phases();
        var level = helper.getLevel();
        long start = level.getGameTime();
        int elapsed = 0;
        for (int step : List.of(20, 40, 80, 160, 320, 640, 1200, 1200)) {
            int at = elapsed;
            helper.runAfterDelay(at, () -> {
                helper.assertFalse(phases.ready(node, level), "the node is still waiting");
                long now = start + at;
                helper.assertValueEqual(phases.readinessUpdate(node.id(), now), OptionalLong.of(now + step),
                    "unknown estimates grow and stop growing at the cap");
                helper.assertValueEqual(phases.readinessUpdate(node.id(), now), OptionalLong.empty(),
                    "a repeated check preserves the reported deadline");
            });
            elapsed += step;
        }
        helper.runAfterDelay(elapsed, () -> {
            reading.set(Readiness.READY);
            helper.assertTrue(phases.ready(node, level), "the node is ready");
            helper.assertTrue(phases.clearReadinessReport(node.id()), "the old estimate clears");
        });
        helper.runAfterDelay(elapsed + 1, () -> {
            reading.set(Readiness.notYet());
            helper.assertFalse(phases.ready(node, level), "a new wait begins");
            long now = level.getGameTime();
            helper.assertValueEqual(phases.readinessUpdate(node.id(), now), OptionalLong.of(now + 20),
                "the new wait starts with the first interval");
            helper.succeed();
        });
    }

    private static Vertex carrying(Node node, ItemSpec goods, long count) {
        return new Vertex(node.id(), node, new Placement(Set.of(), List.of(new Amount(goods, count)),
            List.of(), List.of()));
    }

    private static Node node(PlanningFixture fixture, Supplier<Readiness> readiness, List<UUID> commits) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(fixture.source),
            Stances.WHEREVER, Workload.Once.of(1)).done();
        return new Node() {
            public NodeSpec spec() {
                return spec;
            }

            public Readiness readiness(ServerLevel level) {
                return readiness.get();
            }

            public Outcome commit(ServerLevel level, Worker who) {
                commits.add(id());
                return Outcome.done();
            }
        };
    }
}
