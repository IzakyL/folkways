package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.WorkExertion;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.walk.OnFoot;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ExertionGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "effort");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ExertionGameTests.class);
    }

    @GameTest(template = "empty")
    public static void hasteChangesTimeButNotWork(GameTestHelper helper) {
        for (double haste : new double[] {1, 0.5}) {
            try (Harness h = new Harness(helper)) {
                h.start(Workload.Once.of(20, who -> haste), new WorkEffort.Total(3), false);
                h.run((int) (20 * haste));
                helper.assertTrue(h.events.isEmpty(), "work must not report before exiting");
                h.run(5);
                h.assertTotals(helper, 3, (int) (20 * haste), 0);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void interruptedAndFailedWorkStillCostsEffort(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            h.start(Workload.Once.of(20), new WorkEffort.Total(4), false);
            h.run(5);
            helper.assertTrue(h.events.isEmpty(), "partial work remains local until exit");
            h.runner.letGo(h.level, h.body);
            h.run(10);
            h.assertTotals(helper, 1, 5, 0);
        }
        try (Harness h = new Harness(helper)) {
            h.start(Workload.Once.of(20), new WorkEffort.Total(4), true);
            h.run(25);
            h.assertTotals(helper, 4, 20, 0);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void continuousAndNonLaborWorkAreExplicit(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            int[] held = {0};
            h.start((Workload.Continuous) (level, who) -> held[0]++ < 7,
                new WorkEffort.PerTick(0.2), false);
            h.run(7);
            helper.assertTrue(h.events.isEmpty(), "continuous work reports only when released");
            h.run(3);
            h.assertTotals(helper, 1.4, 7, 0);
        }
        try (Harness h = new Harness(helper)) {
            h.start(Workload.Once.of(20), WorkEffort.NONE, false);
            h.run(25);
            h.assertTotals(helper, 0, 0, 0);
        }
        try (Harness h = new Harness(helper)) {
            h.start(Workload.Once.of(0), new WorkEffort.Total(2), false);
            h.run(3);
            h.assertTotals(helper, 2, 0, 0);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void walkingDoesNotCountTeleportOrReleaseTwice(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            OnFoot walking = new OnFoot(1, LaborRefusal.NO_WAY_THERE);
            var mob = h.body.mob();
            mob.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(1, 2, 1))));
            walking.setOut(h.level, mob, Set.of());
            mob.move(MoverType.SELF, new Vec3(0.25, 0, 0));
            walking.step(h.level, mob, Set.of());
            helper.assertTrue(h.events.isEmpty(), "walking must not report on each step");
            mob.setPos(mob.position().add(100, 0, 0));
            walking.release(h.level, mob);
            walking.release(h.level, mob);
            h.assertTotals(helper, 0, 0, 0.25);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void blockedAndRidingBodiesDoNotAccumulateWalking(GameTestHelper helper) {
        try (Harness h = new Harness(helper)) {
            OnFoot walking = new OnFoot(1, LaborRefusal.NO_WAY_THERE);
            var mob = h.body.mob();
            walking.setOut(h.level, mob, Set.of());
            for (int tick = 0; tick < 5; tick++) {
                walking.step(h.level, mob, Set.of());
            }
            walking.release(h.level, mob);
            h.assertTotals(helper, 0, 0, 0);
            var mount = net.minecraft.world.entity.EntityType.PIG.create(h.level);
            try {
                helper.assertTrue(mob.startRiding(mount, true), "test resident must be aboard");
                walking.setOut(h.level, mob, Set.of());
                mob.setPos(mob.position().add(20, 0, 0));
                walking.release(h.level, mob);
                h.assertTotals(helper, 0, 0, 0);
            } finally {
                mob.stopRiding();
                mount.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void allReleasePathsSettlePartialWorkOnce(GameTestHelper helper) {
        for (String exit : List.of("cancel", "failure", "drop", "abandon", "away")) {
            try (Harness h = new Harness(helper)) {
                h.start(Workload.Once.of(20), new WorkEffort.Total(4), false);
                h.run(5);
                helper.assertTrue(h.events.isEmpty(), "no report before " + exit);
                switch (exit) {
                    case "cancel" -> {
                        h.labor.execution().personal().cancelled(h.active.id());
                        h.run(1);
                    }
                    case "failure" -> {
                        h.labor.execution().personal().failed(h.active.id(), LaborRefusal.GOODS_GONE);
                        h.run(1);
                    }
                    case "drop" -> h.runner.drop();
                    case "abandon" -> h.runner.abandon();
                    case "away" -> {
                        h.runner.away(0);
                        h.runner.away(100);
                    }
                }
                h.runner.drop();
                h.runner.letGo(h.level, h.body);
                h.assertTotals(helper, 1, 5, 0);
            }
        }
        try (Harness h = new Harness(helper)) {
            h.start(Workload.Once.of(0), new WorkEffort.Total(4), false);
            h.runner.drop();
            h.assertTotals(helper, 0, 0, 0);
        }
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

    private static final class Harness implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ColonyLabor labor;
        final Body body;
        final BodyRunner runner;
        final List<WorkExertion> events = new ArrayList<>();
        final Consumer<WorkExertion> listener;
        Node active;

        Harness(GameTestHelper helper) {
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            labor = new ColonyLabor(ColonyData.find(level.getServer(), colony.id()).orElseThrow(), level.dimension());
            body = PersonBody.RESIDENT.get().create(level);
            runner = new BodyRunner(body.id(), labor.execution());
            listener = event -> { if (event.body() == body.mob()) events.add(event); };
            NeoForge.EVENT_BUS.addListener(listener);
        }

        void start(Workload workload, WorkEffort effort, boolean fail) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, WorkSite.at(level, BlockPos.ZERO),
                Stances.WHEREVER, workload).effort(effort).done();
            Node node = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) {
                    return fail ? Outcome.failed(LaborRefusal.GOODS_GONE) : Outcome.done();
                }
            };
            active = node;
            labor.execution().personal().ready(node, level);
            runner.offer(level, body, new Urge(OWNER, 1, true, () -> node), node);
        }

        void run(int ticks) {
            for (int tick = 0; tick < ticks; tick++) {
                labor.tick(runner, level, body);
            }
        }

        void assertTotals(GameTestHelper helper, double work, int ticks, double distance) {
            helper.assertValueEqual(events.size(), work > 0 || ticks > 0 || distance > 0 ? 1 : 0,
                "each execution reports its nonzero totals exactly once");
            helper.assertTrue(Math.abs(events.stream().mapToDouble(WorkExertion::workDone).sum() - work) < 0.00001,
                "standard work must reflect execution progress");
            helper.assertValueEqual(events.stream().mapToInt(WorkExertion::activeTicks).sum(), ticks,
                "only active labor contributes elapsed ticks");
            helper.assertTrue(Math.abs(events.stream().mapToDouble(WorkExertion::distance).sum() - distance) < 0.00001,
                "only physical walking contributes distance");
        }

        public void close() {
            runner.letGo(level, body);
            NeoForge.EVENT_BUS.unregister(listener);
            labor.close();
            body.mob().discard();
            colony.raze();
        }
    }
}
