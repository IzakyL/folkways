package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.walk.OnFoot;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Live-world execution guards. The complete certificate publishes one head for a real licensed builder. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FillPlanGameTests {
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final ItemSpec MATERIAL = ItemSpec.of(ResourceLocation.withDefaultNamespace("dirt"));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(FillPlanGameTests.class); }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void aTunnelIsFilledFromItsFarEndAndTheLastGapCloses(GameTestHelper helper) {
        floor(helper);
        tunnel(helper, 7, 15, false);
        Set<BlockPos> cells = box(at(helper, 7, 1, 10), at(helper, 15, 2, 10));
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        try (FillPlan plan = plan(helper, colony(builder), cells)) {
            List<Job> first = refresh(plan, helper, cells);
            helper.assertTrue(first.size() == 1 && first.getFirst().cell().getX() >= at(helper, 12, 1, 10).getX(),
                "the complete tunnel certificate starts with one deep head: " + describe(plan));
            completeProject(helper, plan, cells, pack);
            helper.assertTrue(pack.countItem(Items.DIRT) == 64 - cells.size(), "one real block is spent per target");
        } finally { builder.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void theSecondOfTwoExitClosuresChecksTheFirstPlacement(GameTestHelper helper) {
        floor(helper);
        tunnel(helper, 7, 15, true);
        Set<BlockPos> cells = Set.of(at(helper, 6, 1, 10), at(helper, 16, 1, 10));
        Mob inside = resident(helper, at(helper, 11, 1, 10));
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, colony(builder), cells)) {
            Job first = onlyJob(helper, refresh(plan, helper, cells));
            helper.assertTrue(plan.requests().size() == 1, "a whole certificate publishes one serial head");
            stand(builder, first, helper);
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, first), worker(builder, pack)) instanceof Outcome.Done,
                "the first exit closes while the bystander retains the other exit");
            Job last = onlyJob(helper, refresh(plan, helper, cells));
            helper.assertTrue(!last.cell().equals(first.cell()), "the next head represents the remaining exit");
            stand(builder, last, helper);
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, last), worker(builder, pack)) instanceof Outcome.Failed,
                "fresh people and the first real receipt prevent the second closure from trapping the bystander");
            helper.assertTrue(helper.getLevel().getBlockState(last.cell()).isAir() && pack.countItem(Items.DIRT) == 7,
                "the refusal preserves the remaining opening and its material");
        } finally { inside.discard(); builder.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void aBystanderEnteringAfterPlanningKeepsTheLastExitOpen(GameTestHelper helper) {
        floor(helper);
        tunnel(helper, 7, 11, false);
        BlockPos mouth = at(helper, 6, 2, 10);
        Set<BlockPos> cells = Set.of(mouth);
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        Mob bystander = null;
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, colony(builder), cells)) {
            Job job = onlyJob(helper, refresh(plan, helper, cells));
            Node seal = node(plan, job);
            stand(builder, job, helper);
            bystander = resident(helper, at(helper, 10, 1, 10));
            helper.assertTrue(Commit.run(helper.getLevel(), seal, worker(builder, pack)) instanceof Outcome.Failed,
                "a new actual bystander is protected even after the entire certificate was planned");
            helper.assertTrue(pack.countItem(Items.DIRT) == 8 && helper.getLevel().getBlockState(mouth).isAir(),
                "the blocked seal neither places nor spends");
            bystander.setPos(Vec3.atBottomCenterOf(at(helper, 4, 1, 10)));
            helper.assertTrue(Commit.run(helper.getLevel(), seal, worker(builder, pack)) instanceof Outcome.Done,
                "the same retained request succeeds after the real bystander leaves");
            finishRetreat(helper, plan);
        } finally { if (bystander != null) bystander.discard(); builder.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_native_idle", timeoutTicks = 150)
    public static void anIdleResidentGetsARealWalkOutOfTheLastFillCell(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Mob body = resident(helper, cell);
        FillPlan plan = plan(helper, colony(body), Set.of(cell));
        NativeWalk[] walk = {null};
        try {
            Job job = onlyJob(helper, refresh(plan, helper, Set.of(cell)));
            Node fill = node(plan, job);
            BlockPos stance = footing(job, helper);
            helper.assertTrue(!stance.equals(cell) && !plan.clearOfFill(body), "the certificate asks the builder to walk out first");
            helper.assertTrue(body.getNavigation().moveTo(stance.getX() + 0.5D, stance.getY(), stance.getZ() + 0.5D, 1.0D),
                "native navigation can reach the certified work stance");
            walk[0] = new NativeWalk(helper, body, job, plan);
            helper.runAtTickTime(40, () -> {
                try {
                    helper.assertTrue(plan.clearOfFill(body), detail("real navigation clears the final target; stance=" + stance.toShortString(), plan, body));
                    SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 2));
                    helper.assertTrue(Commit.run(helper.getLevel(), fill, worker(body, pack)) instanceof Outcome.Done,
                        detail("the original builder places only after actually leaving the target", plan, body));
                    helper.assertTrue(pack.countItem(Items.DIRT) == 1, "walking spends no extra material");
                    finishRetreat(helper, plan);
                    helper.succeed();
                } finally { walk[0].close(); plan.close(); body.discard(); }
            });
        } catch (RuntimeException failure) { if (walk[0] != null) walk[0].close(); plan.close(); body.discard(); throw failure; }
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void unfinishedWorkKeepsItsEntranceEvenWithoutAnybodyInside(GameTestHelper helper) {
        floor(helper);
        tunnel(helper, 7, 15, false);
        BlockPos mouth = at(helper, 6, 1, 10), deep = at(helper, 14, 1, 10);
        Set<BlockPos> cells = Set.of(mouth, deep);
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        try (FillPlan plan = plan(helper, colony(builder), cells)) {
            Job head = onlyJob(helper, refresh(plan, helper, cells));
            helper.assertTrue(head.cell().equals(deep), "the complete suffix retains access to unfinished inner work");
        } finally { builder.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_raised_face", timeoutTicks = 100)
    public static void aRaisedWorkFaceProvidesTheNextLayersStances(GameTestHelper helper) {
        floor(helper);
        Set<BlockPos> cells = box(at(helper, 9, 1, 9), at(helper, 12, 3, 12));
        Mob builder = resident(helper, at(helper, 8, 1, 8));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 64));
        try (FillPlan plan = plan(helper, colony(builder), cells)) {
            refresh(plan, helper, cells);
            completeProject(helper, plan, cells, pack);
            helper.assertTrue(pack.countItem(Items.DIRT) == 64 - cells.size(), "raised work has an exact physical ledger");
        } finally { builder.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_winding_part", timeoutTicks = 100)
    public static void aBlockedLargePartDoesNotHideAnIndependentFill(GameTestHelper helper) {
        floor(helper);
        for (BlockPos cell : box(at(helper, 2, 1, 4), at(helper, 18, 3, 14))) helper.getLevel().setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
        List<BlockPos> path = new ArrayList<>();
        for (int row = 0; row < 5; row++) {
            int z = 5 + row * 2;
            for (int step = 0; step < 15; step++) path.add(at(helper, row % 2 == 0 ? 3 + step : 17 - step, 1, z));
            if (row < 4) path.add(at(helper, row % 2 == 0 ? 17 : 3, 1, z + 1));
        }
        Set<BlockPos> cells = new LinkedHashSet<>(path);
        for (BlockPos cell : path) {
            helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.getLevel().setBlock(at(helper, 2, 1, 5), Blocks.AIR.defaultBlockState(), 3);
        helper.getLevel().setBlock(at(helper, 2, 2, 5), Blocks.AIR.defaultBlockState(), 3);
        BlockPos free = at(helper, 20, 1, 19);
        cells.add(free);
        Mob bystander = resident(helper, path.getLast());
        Mob builder = resident(helper, at(helper, 1, 1, 1));
        try (FillPlan plan = plan(helper, colony(builder), cells)) {
            plan.refresh(new Survey(helper.getLevel(), cells).fill(), keys(cells), DIRT);
            for (int i = 0; i < 200000 && plan.jobs().isEmpty(); i++) {
                Ground.Budget slice = new Ground.Budget(31, 0);
                plan.advance(slice);
                helper.assertTrue(slice.used() <= 31, "each slice retains its actual allowance");
            }
            helper.assertTrue(!plan.jobs().isEmpty(), "the independent component needs a complete publishable head: " + diagnostic(plan));
            Job head = onlyJob(helper, plan.jobs());
            helper.assertTrue(path.size() > 64 && head.cell().equals(free), "the independent safe component is not hidden behind the long passage");
            SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
            stand(builder, head, helper);
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, head), worker(builder, pack)) instanceof Outcome.Done,
                "the independent placement really commits");
            helper.assertTrue(pack.countItem(Items.DIRT) == 7 && path.stream().allMatch(at -> helper.getLevel().getBlockState(at).isAir()),
                "the protected passage remains open and only the independent cell consumes material");
        } finally { bystander.discard(); builder.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void aRemovedPlanRefusesItsAlreadyClaimedWorkAndReturnsTheBlock(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Mob builder = resident(helper, at(helper, 10, 1, 10));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        FillPlan plan = plan(helper, colony(builder), Set.of(cell));
        try {
            Job job = onlyJob(helper, refresh(plan, helper, Set.of(cell)));
            Node held = node(plan, job);
            held.planned(helper.getLevel());
            stand(builder, job, helper);
            plan.close();
            helper.assertTrue(Commit.run(helper.getLevel(), held, worker(builder, pack)) instanceof Outcome.Failed,
                "removing the plan revokes the actual worker's claimed task");
            helper.assertTrue(pack.countItem(Items.DIRT) == 8 && helper.getLevel().getBlockState(cell).isAir(), "cancellation refunds every block");
        } finally { plan.close(); builder.discard(); }
        helper.succeed();
    }

    // The full census can include the48-person neighbour fixture. Capture, fingerprinting and
    // complete replay need separate passes; this changes the number of slices, never their256-work cap.
    @GameTest(template = "empty_large", batch = "safe_fill_budget", timeoutTicks = 8000)
    public static void tinySlicesKeepTheirFrontierAndEventuallyOfferWork(GameTestHelper helper) {
        floor(helper);
        Set<BlockPos> cells = Set.of(at(helper, 11, 1, 11));
        Mob builder = resident(helper, at(helper, 1, 1, 1));
        FillPlan plan = plan(helper, colony(builder), cells);
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), keys(cells), DIRT);
        for (int i = 0; i < 64; i++) {
            Ground.Budget one = new Ground.Budget(1, 0);
            plan.advance(one);
            helper.assertTrue(one.used() <= 1, "one-work slices do not overspend");
        }
        helper.assertTrue(plan.jobs().isEmpty(), "an incomplete whole certificate publishes no new request");
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (!plan.jobs().isEmpty()) return;
            if (ticks[0] % 10 == 0) {
                try (FillPlan remote = plan(helper, UUID.randomUUID(), Set.of(at(helper, 1000, 1, 1000)))) { remote.phase(); }
            }
            Ground.Budget slice = new Ground.Budget(256, 0);
            plan.advance(slice);
            ticks[0]++;
            if (ticks[0] == 1999) System.out.println("tiny-slice checkpoint at1999: " + planningDiagnostic(plan));
            helper.assertTrue(slice.used() <= 256, "all later work remains bounded across real ticks");
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(plan.jobs().size() == 1 && ticks[0] > 1, "the complete certificate resumes across ticks: " + describe(plan));
        }).thenExecute(() -> {
            System.out.println("tiny-slice completed ticks=" + ticks[0] + "; " + planningDiagnostic(plan));
            plan.close(); builder.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_budget", timeoutTicks = 2000)
    public static void interruptedProofsDiscardChangedTerrainAndConfiguration(GameTestHelper helper) {
        floor(helper);
        BlockPos old = at(helper, 9, 1, 9), next = at(helper, 15, 1, 15);
        Set<BlockPos> cells = Set.of(old, next);
        Mob builder = resident(helper, at(helper, 1, 1, 1));
        FillPlan plan = plan(helper, colony(builder), cells);
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), keys(cells), DIRT);
        plan.advance(new Ground.Budget(64, 0));
        helper.getLevel().setBlock(old, Blocks.STONE.defaultBlockState(), 3);
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(Job.keyOf(next)), DIRT);
        helper.onEachTick(() -> {
            Ground.Budget slice = new Ground.Budget(256, 0);
            plan.advance(slice);
            helper.assertTrue(slice.used() <= 256 && !plan.offered(old), "a removed target never returns from suspended work");
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(plan.jobs().size() == 1 && plan.offered(next), "the current whole suffix progresses: " + describe(plan));
        }).thenExecute(() -> { plan.close(); builder.discard(); }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_budget", timeoutTicks = 8000)
    public static void manyIdenticalResidentsAndLargeFillRemainBudgeted(GameTestHelper helper) {
        floor(helper);
        Set<BlockPos> cells = box(at(helper, 12, 1, 12), at(helper, 19, 1, 19));
        List<Mob> people = new ArrayList<>();
        for (int i = 0; i < 48; i++) people.add(resident(helper, at(helper, 2 + i % 8, 1, 2 + i / 8)));
        Mob builder = people.getFirst();
        FillPlan plan = plan(helper, colony(builder), cells);
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), keys(cells), DIRT);
        long[] observations = {0, 0, 0, 0};
        helper.onEachTick(() -> {
            if (!plan.jobs().isEmpty()) return;
            Ground.Budget slice = new Ground.Budget(4096, 0);
            long began = System.nanoTime();
            plan.advance(slice);
            long elapsed = System.nanoTime() - began;
            observations[0]++; observations[1] += slice.used(); observations[2] += elapsed; observations[3] = Math.max(observations[3], elapsed);
            helper.assertTrue(slice.used() <= 4096, "48 bodies and 64 targets still share the bounded planner allowance");
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(plan.jobs().size() == 1 && plan.worker().filter(builder.getUUID()::equals).isPresent(),
                "the whole 64-target certificate publishes one head for its actual licensed builder: " + describe(plan));
        }).thenExecute(() -> {
            org.slf4j.LoggerFactory.getLogger(FillPlanGameTests.class).info(
                "FILL_BUDGET slices={} work={} totalNanos={} maxSliceNanos={} residents=48 cells=64",
                observations[0], observations[1], observations[2], observations[3]);
            plan.close(); people.forEach(Mob::discard);
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_budget", timeoutTicks = 100)
    public static void cancellingAndRedrawingReleasesProofsAndSharedAllowances(GameTestHelper helper) throws ReflectiveOperationException {
        UUID colony = UUID.randomUUID();
        Set<BlockPos> cells = Set.of(at(helper, 11, 1, 11));
        var allowances = FillPlan.class.getDeclaredField("ALLOWANCES");
        allowances.setAccessible(true);
        for (int i = 0; i < 32; i++) {
            FillPlan plan = plan(helper, colony, cells);
            plan.refresh(List.of(), keys(cells), DIRT);
            plan.advance(new Ground.Budget(8, 0));
            plan.close(); plan.close();
            helper.assertTrue(!plan.advance() && plan.jobs().isEmpty(), "closed work cannot publish or continue");
            Map<?, ?> colonies = (Map<?, ?>) ((Map<?, ?>) allowances.get(null)).get(helper.getLevel());
            helper.assertTrue(colonies == null || !colonies.containsKey(colony), "the final closed project releases the colony's tick account");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_retention", timeoutTicks = 500)
    public static void ordinarySweepsKeepAnOfferWhileItsWorkerIsStillOnTheWay(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 18, 1, 18);
        Set<BlockPos> cells = Set.of(cell);
        Mob builder = resident(helper, at(helper, 1, 1, 1));
        FillPlan plan = plan(helper, colony(builder), cells);
        Job job = onlyJob(helper, refresh(plan, helper, cells));
        Node held = node(plan, job);
        held.planned(helper.getLevel());
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (++ticks[0] > 250) return;
            if (ticks[0] % 100 == 0) plan.refresh(new Survey(helper.getLevel(), cells).fill(), keys(cells), DIRT);
            plan.advance(new Ground.Budget(1, 0));
            helper.assertTrue(held.refusal(helper.getLevel()).isEmpty(), "ordinary sweeps preserve the original claimed request identity");
        });
        helper.runAtTickTime(251, () -> {
            try {
                stand(builder, job, helper);
                SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
                helper.assertTrue(Commit.run(helper.getLevel(), held, worker(builder, pack)) instanceof Outcome.Done,
                    "fresh execution still permits the original request after multiple sweeps");
                helper.assertTrue(pack.countItem(Items.DIRT) == 7, "delayed work spends one block exactly");
                finishRetreat(helper, plan);
                helper.succeed();
            } finally { plan.close(); builder.discard(); }
        });
    }

    @GameTest(template = "empty_large", batch = "safe_fill_invalidation", timeoutTicks = 100)
    public static void changedInputsAndTerrainStillRevokeRetainedOffers(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Set<BlockPos> cells = Set.of(cell);
        Mob builder = resident(helper, at(helper, 1, 1, 1));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, colony(builder), cells)) {
            Node old = node(plan, onlyJob(helper, refresh(plan, helper, cells)));
            plan.refresh(new Survey(helper.getLevel(), cells).fill(), Set.of(), DIRT);
            helper.assertTrue(old.refusal(helper.getLevel()).isPresent(), "removing the target revokes its claimed request");
            refresh(plan, helper, cells);
            plan.refresh(new Survey(helper.getLevel(), cells).fill(), keys(cells), Blocks.STONE.defaultBlockState());
            helper.assertTrue(old.refusal(helper.getLevel()).isPresent(), "changing material revokes the old request version");
            Job job = onlyJob(helper, refresh(plan, helper, cells));
            Node current = node(plan, job);
            stand(builder, job, helper);
            helper.getLevel().setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
            plan.advance(new Ground.Budget(1, 0));
            helper.assertTrue(Commit.run(helper.getLevel(), current, worker(builder, pack)) instanceof Outcome.Failed,
                "an old witness cannot claim an external placement as its own successful work");
            helper.assertTrue(pack.countItem(Items.DIRT) == 8 && !plan.receipted(Job.keyOf(cell)),
                "external occupancy creates no own material consumption or receipt");
        } finally { builder.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_fallback", timeoutTicks = 100)
    public static void aDifferentMaterialRebuildsItsFallbackAfterTheExitWatchChanges(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11), oldAnchor = at(helper, 1, 1, 1);
        Set<BlockPos> cells = Set.of(cell);
        AtomicReference<List<BlockPos>> anchors = new AtomicReference<>(List.of(oldAnchor));
        Mob builder = resident(helper, oldAnchor);
        UUID colony = colony(builder);
        Mob bystander = null;
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = new FillPlan(helper.getLevel(), UUID.randomUUID(), colony, cells,
                anchors::get, () -> {}, false, new FillPlan.Limits(Integer.MAX_VALUE, 0))) {
            plan.refresh(new Survey(helper.getLevel(), cells).fill(), keys(cells), Blocks.STONE.defaultBlockState());
            drain(plan);
            Job first = onlyJob(helper, plan.jobs());
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, first), worker(builder, pack)) instanceof Outcome.Failed,
                "an actual-material reproof grants no remote hand reach");
            bystander = resident(helper, cell);
            stand(builder, first, helper);
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, first), worker(builder, pack)) instanceof Outcome.Failed,
                "the actual new occupant blocks the rebuilt material certificate");
            anchors.set(List.of(at(helper, 22, 1, 22)));
            bystander.discard();
            helper.getLevel().setBlock(oldAnchor, Blocks.STONE.defaultBlockState(), 3);
            drain(plan);
            Job next = onlyJob(helper, plan.jobs());
            stand(builder, next, helper);
            helper.assertTrue(Commit.run(helper.getLevel(), node(plan, next), worker(builder, pack)) instanceof Outcome.Done,
                "changing the actual source approach rebuilds the whole certificate for the carried dirt");
            helper.assertTrue(pack.countItem(Items.DIRT) == 7 && helper.getLevel().getBlockState(cell).is(Blocks.DIRT),
                "failed probes refund all material and the real placement spends exactly one");
            finishRetreat(helper, plan);
        } finally { builder.discard(); if (bystander != null) bystander.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_native_near_exit", timeoutTicks = 150)
    public static void aNearTrustedExitDoesNotEndTheAsideInsideItsSafetyBuffer(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Mob body = resident(helper, cell);
        UUID colony = colony(body);
        FillPlan plan = new FillPlan(helper.getLevel(), UUID.randomUUID(), colony, Set.of(cell),
            () -> List.of(at(helper, 12, 1, 11), at(helper, 13, 1, 11)), () -> {}, false, new FillPlan.Limits(Integer.MAX_VALUE, 0));
        NativeWalk[] walk = {null};
        try {
            Job job = onlyJob(helper, refresh(plan, helper, Set.of(cell)));
            BlockPos stance = footing(job, helper);
            helper.assertTrue(body.getNavigation().moveTo(stance.getX() + 0.5D, stance.getY(), stance.getZ() + 0.5D, 1.0D),
                "native navigation walks clear of the pending cube before filling");
            walk[0] = new NativeWalk(helper, body, job, plan);
            helper.runAtTickTime(40, () -> {
                try {
                    helper.assertTrue(plan.clearOfFill(body), detail("the actual body clears the target; stance=" + stance.toShortString(), plan, body));
                    SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 2));
                    helper.assertTrue(Commit.run(helper.getLevel(), node(plan, job), worker(body, pack)) instanceof Outcome.Done,
                        detail("the same licensed builder fills after its real walk", plan, body));
                    helper.assertTrue(pack.countItem(Items.DIRT) == 1, "the real walk neither duplicates nor consumes a block");
                    finishRetreat(helper, plan);
                    helper.succeed();
                } finally { walk[0].close(); plan.close(); body.discard(); }
            });
        } catch (RuntimeException failure) { if (walk[0] != null) walk[0].close(); plan.close(); body.discard(); throw failure; }
    }

    @GameTest(template = "empty_large", batch = "safe_fill_near_exit_budget", timeoutTicks = 2000)
    public static void crowdedRetreatsResumeTheirFarExitSearchWithOneWorkUnit(GameTestHelper helper) {
        floor(helper);
        Set<BlockPos> cells = box(at(helper, 10, 1, 10), at(helper, 12, 1, 12));
        List<Mob> people = cells.stream().map(cell -> resident(helper, cell)).toList();
        UUID colony = colony(people.getFirst());
        people.forEach(body -> ((Body) body).joinColony(colony));
        FillPlan plan = new FillPlan(helper.getLevel(), UUID.randomUUID(), colony, cells,
            () -> List.of(at(helper, 13, 1, 11), at(helper, 14, 1, 11)), () -> {}, false, new FillPlan.Limits(Integer.MAX_VALUE, 0));
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), keys(cells), DIRT);
        long[] slices = {0};
        helper.onEachTick(() -> {
            for (int i = 0; i < 256 && plan.jobs().isEmpty(); i++) {
                Ground.Budget one = new Ground.Budget(1, 0);
                plan.advance(one); slices[0]++;
                helper.assertTrue(one.used() <= 1 && cells.stream().allMatch(at -> helper.getLevel().getBlockState(at).isAir()),
                    "one-work slices preserve physical blocks while completing the whole proof");
            }
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(plan.jobs().size() == 1 && slices[0] > 256, "one-work continuation eventually publishes a full certificate: " + describe(plan));
        }).thenExecute(() -> {
            try {
                SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 16));
                completeProject(helper, plan, cells, pack);
                helper.assertTrue(pack.countItem(Items.DIRT) == 16 - cells.size(), "every crowded cell spends exactly one real block after actual step-aside commits");
            } finally { plan.close(); people.forEach(Mob::discard); }
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_near_exit_closed", timeoutTicks = 150)
    public static void anAsideWaitsIfEveryFartherTrustedExitIsBehindAWall(GameTestHelper helper) {
        floor(helper);
        for (int x = 10; x <= 13; x++) for (int z = 10; z <= 12; z++) for (int y = 1; y <= 3; y++) {
            if (y == 3 || x == 10 || x == 13 || z != 11) helper.getLevel().setBlock(at(helper, x, y, z), Blocks.STONE.defaultBlockState(), 3);
        }
        BlockPos cell = at(helper, 11, 1, 11);
        Mob body = resident(helper, cell);
        FillPlan plan = new FillPlan(helper.getLevel(), UUID.randomUUID(), colony(body), Set.of(cell),
            () -> List.of(at(helper, 12, 1, 11), at(helper, 14, 1, 11)), () -> {}, false, new FillPlan.Limits(Integer.MAX_VALUE, 0));
        try {
            helper.assertTrue(refresh(plan, helper, Set.of(cell)).isEmpty(), "a wall-separated source cannot prove the whole remaining work");
            helper.assertTrue(helper.getLevel().getBlockState(cell).isAir(), "the trapped resident is never filled over");
            helper.getLevel().setBlock(at(helper, 13, 1, 11), Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(at(helper, 13, 2, 11), Blocks.AIR.defaultBlockState(), 3);
            helper.runAtTickTime(21, () -> {
                try {
                    Job job = onlyJob(helper, refresh(plan, helper, Set.of(cell)));
                    stand(body, job, helper);
                    SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 2));
                    helper.assertTrue(Commit.run(helper.getLevel(), node(plan, job), worker(body, pack)) instanceof Outcome.Done,
                        "opening a real passage permits actual work after a fresh complete proof");
                    finishRetreat(helper, plan);
                    helper.succeed();
                } finally { plan.close(); body.discard(); }
            });
        } catch (RuntimeException failure) { plan.close(); body.discard(); throw failure; }
    }

    @GameTest(template = "empty_large", batch = "safe_fill_near_exit_resizing", timeoutTicks = 100)
    public static void aBodyGrowingDuringARetreatProofRechecksTheWholeBuffer(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Mob body = resident(helper, at(helper, 9, 1, 11));
        try (FillPlan plan = plan(helper, colony(body), Set.of(cell))) {
            Job job = onlyJob(helper, refresh(plan, helper, Set.of(cell)));
            Node held = node(plan, job);
            stand(body, job, helper);
            body.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.SCALE).setBaseValue(4.0D);
            body.refreshDimensions();
            helper.assertTrue(body.getBbWidth() > 2.0F, "the actual builder has grown outside the declared full-cube model");
            SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
            helper.assertTrue(Commit.run(helper.getLevel(), held, worker(body, pack)) instanceof Outcome.Failed,
                "an old small-body certificate never grants a permit to an unsupported large body");
            helper.assertTrue(pack.countItem(Items.DIRT) == 8 && helper.getLevel().getBlockState(cell).isAir(),
                "unsupported growth remains a conservative wait with no physical or material effect");
        } finally { body.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_native_overhang", timeoutTicks = 100)
    public static void aBodyOverhangingTheLastCellAlsoStepsAside(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Mob builder = resident(helper, at(helper, 10, 1, 11));
        builder.setPos(cell.getX() - 0.15D, cell.getY(), cell.getZ() + 0.5D);
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, colony(builder), Set.of(cell))) {
            Job job = onlyJob(helper, refresh(plan, helper, Set.of(cell)));
            Node held = node(plan, job);
            helper.assertTrue(!builder.blockPosition().equals(cell) && !plan.clearOfFill(builder), "the body crosses the target although its feet are outside");
            helper.assertTrue(Commit.run(helper.getLevel(), held, worker(builder, pack)) instanceof Outcome.Failed,
                "the old overlapping position cannot place the block");
            helper.assertTrue(pack.countItem(Items.DIRT) == 8 && helper.getLevel().getBlockState(cell).isAir(), "overlap cannot consume any material");
            stand(builder, job, helper);
            helper.assertTrue(plan.clearOfFill(builder), "actual arrival at the certified stance clears the whole body");
            helper.assertTrue(Commit.run(helper.getLevel(), held, worker(builder, pack)) instanceof Outcome.Done,
                detail("the retained task commits only after the whole body leaves", plan, builder));
            finishRetreat(helper, plan);
        } finally { builder.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "safe_fill_native_edge", timeoutTicks = 100)
    public static void arrivingAtTheEdgeOfAStanceStillLeavesTheBlockInReach(GameTestHelper helper) {
        arrivalAtEdge(helper, false);
    }

    @GameTest(template = "empty_large", batch = "safe_fill_native_pit_edge", timeoutTicks = 100)
    public static void arrivingAtAPitsLipStillUsesTheValidWorkStance(GameTestHelper helper) {
        arrivalAtEdge(helper, true);
    }

    @GameTest(template = "empty_large", batch = "safe_fill", timeoutTicks = 100)
    public static void anotherActiveFillCannotSupplyATrustedExit(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 11, 1, 11);
        Mob builder = resident(helper, at(helper, 5, 1, 5));
        FillPlan neighbouring = plan(helper, UUID.randomUUID(), Set.of(at(helper, 1, 1, 1)));
        FillPlan own = plan(helper, colony(builder), Set.of(cell));
        try {
            helper.assertTrue(refresh(own, helper, Set.of(cell)).isEmpty(), "another project's future fill is not a stable external anchor");
            neighbouring.close();
            helper.runAtTickTime(21, () -> {
                try {
                    helper.assertTrue(refresh(own, helper, Set.of(cell)).size() == 1,
                        "withdrawing the neighboring project permits a fresh complete proof after its bounded retry");
                    helper.succeed();
                } finally { own.close(); neighbouring.close(); builder.discard(); }
            });
        } catch (RuntimeException failure) { own.close(); neighbouring.close(); builder.discard(); throw failure; }
    }

    private static void arrivalAtEdge(GameTestHelper helper, boolean pit) {
        floor(helper);
        BlockPos cell = at(helper, 11, pit ? 0 : 1, 11);
        if (pit) helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        Mob builder = resident(helper, at(helper, 9, 1, 9));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (FillPlan plan = plan(helper, colony(builder), Set.of(cell))) {
            Job job = onlyJob(helper, refresh(plan, helper, Set.of(cell)));
            Vec3 center = Vec3.atBottomCenterOf(footing(job, helper));
            Vec3 away = center.subtract(Vec3.atBottomCenterOf(cell)).multiply(1, 0, 1).normalize().scale(0.85D);
            builder.setPos(center.add(away));
            Node held = node(plan, job);
            helper.assertTrue(Commit.run(helper.getLevel(), held, worker(builder, pack)) instanceof Outcome.Done,
                detail("navigation arrival must complete without manual recentering", plan, builder));
            helper.assertTrue(pack.countItem(Items.DIRT) == 7 && helper.getLevel().getBlockState(cell).is(Blocks.DIRT),
                "one actual placement is completed from the certified work stance");
            finishRetreat(helper, plan);
        } finally { builder.discard(); }
        helper.succeed();
    }

    private static UUID colony(Mob builder) {
        UUID colony = UUID.randomUUID();
        ((Body) builder).joinColony(colony);
        return colony;
    }

    private static FillPlan plan(GameTestHelper helper, UUID colony, Set<BlockPos> cells) {
        return new FillPlan(helper.getLevel(), UUID.randomUUID(), colony, cells,
            () -> List.of(at(helper, 1, 1, 1)), () -> {}, false, new FillPlan.Limits(Integer.MAX_VALUE, 0));
    }

    private static void drain(FillPlan plan) {
        for (int i = 0; i < 16; i++) {
            plan.advance(Ground.Budget.unlimited());
            if (Set.of("READY", "RETREATING", "COMPLETE", "WAITING", "UNSUPPORTED", "CONFLICT").contains(plan.completionStatus())) return;
        }
    }

    private static List<Job> refresh(FillPlan plan, GameTestHelper helper, Set<BlockPos> cells) {
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), keys(cells), DIRT);
        drain(plan);
        return plan.jobs();
    }

    private static Set<String> keys(Set<BlockPos> cells) { return cells.stream().map(Job::keyOf).collect(Collectors.toSet()); }
    private static String describe(FillPlan plan) { return plan.completionStatus() + ": " + plan.completionReason(); }
    private static String planningDiagnostic(FillPlan plan) {
        try {
            var field = FillPlan.class.getDeclaredField("completion");
            field.setAccessible(true);
            return ((CompletionRuntime) field.get(plan)).diagnostics().toString();
        } catch (ReflectiveOperationException absent) { return describe(plan) + "; " + absent; }
    }
    private static String diagnostic(FillPlan plan) {
        try {
            var field = FillPlan.class.getDeclaredField("completion");
            field.setAccessible(true);
            CompletionRuntime runtime = (CompletionRuntime) field.get(plan);
            if (runtime == null) return "no runtime";
            Map<String, Object> data = runtime.diagnostics();
            Map<?, ?> attempt = data.get("lastAttempt") instanceof Map<?, ?> map ? map : Map.of();
            String result = "phase=" + data.get("phase") + "; why=" + clipped(String.valueOf(data.get("lastPermitReason")), 95)
                + "; eye=" + attempt.get("eyeDistance") + "; stance=" + attempt.get("headStance")
                + "; feet=" + attempt.get("actualFeet") + "; native=" + attempt.get("nativeResult")
                + "/" + attempt.get("nativeAfterStandable") + "; people=" + attempt.get("peopleResult")
                + "/" + attempt.get("peopleCursor") + "/" + attempt.get("peopleCount")
                + "; stage=" + data.get("searchStage") + "; states=" + data.get("expandedStates")
                + "; back=" + data.get("backtracks");
            return clipped(result, 360);
        } catch (ReflectiveOperationException absent) { return clipped(describe(plan) + "; " + absent, 360); }
    }
    private static String detail(String message, FillPlan plan, Mob body) {
        return clipped(message + "; body=" + body.position() + "; " + diagnostic(plan), 450);
    }
    private static String clipped(String text, int size) { return text.length() <= size ? text : text.substring(0, size - 3) + "..."; }
    private static Job onlyJob(GameTestHelper helper, List<Job> jobs) {
        helper.assertTrue(jobs.size() == 1, "one complete certified head is required; got " + jobs.stream().map(Job::cell).toList());
        return jobs.getFirst();
    }
    private static BlockPos footing(Job job, GameTestHelper helper) { return job.footings().cells().iterator().next().block(helper.getLevel()); }
    private static void stand(Mob body, Job job, GameTestHelper helper) { body.setPos(Vec3.atBottomCenterOf(footing(job, helper))); body.setOnGround(true); }

    // Keep the same real OnFoot intent BodyRunner polls. One bare moveTo has no continuing owner
    // and can be replaced by other entity controllers; this fixture must physically walk out.
    private static final class NativeWalk implements AutoCloseable {
        private final OnFoot movement = new OnFoot(1.0D, () -> "folkways.refusal.labor.no_way_there");
        private final Mob body;
        private final ServerLevel level;
        private boolean active = true;

        NativeWalk(GameTestHelper helper, Mob body, Job job, FillPlan plan) {
            this.body = body;
            this.level = helper.getLevel();
            movement.setOut(level, body, job.footings().cells());
            helper.onEachTick(() -> {
                if (!active || !body.isAlive()) return;
                Going progress = movement.step(level, body, job.footings().cells());
                if (progress instanceof Going.Failed) {
                    String reason = detail("the sustained native walk cannot fail before clearing the target", plan, body);
                    close(); plan.close(); body.discard();
                    helper.assertTrue(false, reason);
                }
            });
        }

        @Override public void close() {
            if (!active) return;
            active = false;
            movement.release(level, body);
        }
    }

    private static void completeProject(GameTestHelper helper, FillPlan plan, Set<BlockPos> cells, SimpleContainer pack) {
        int initial = pack.countItem(Items.DIRT), done = 0;
        for (int attempt = 0; attempt < cells.size() * 32 + 32 && !plan.complete(); attempt++) {
            refresh(plan, helper, cells);
            if (!plan.steppingAside().isEmpty()) { consumeMoves(helper, plan); continue; }
            if (plan.complete()) break;
            Job job = onlyJob(helper, plan.jobs());
            Mob actor = (Mob) helper.getLevel().getEntity(plan.worker().orElseThrow());
            helper.assertTrue(actor != null, "the certificate's real designated builder remains loaded");
            stand(actor, job, helper);
            int before = pack.countItem(Items.DIRT);
            Outcome outcome = Commit.run(helper.getLevel(), node(plan, job), worker(actor, pack));
            if (outcome instanceof Outcome.Done) {
                done++;
                helper.assertTrue(helper.getLevel().getBlockState(job.cell()).is(Blocks.DIRT)
                    && pack.countItem(Items.DIRT) == before - 1, "a successful real placement has exactly one matching material receipt");
            } else {
                helper.assertTrue(outcome instanceof Outcome.Failed && pack.countItem(Items.DIRT) == before
                    && helper.getLevel().getBlockState(job.cell()).isAir(), "every deferred placement preserves all material and terrain");
                helper.assertTrue(!plan.steppingAside().isEmpty(), "a blocked colony bystander receives an actual walk request: " + describe(plan));
            }
            helper.assertTrue(!actor.isInWall() && helper.getLevel().noCollision(actor, actor.getBoundingBox()), "the builder is never walled into its own placement");
        }
        helper.assertTrue(plan.complete() && cells.stream().allMatch(at -> helper.getLevel().getBlockState(at).is(Blocks.DIRT)),
            "every target and the actual terminal retreat finish: " + describe(plan));
        helper.assertTrue(done == cells.size() && pack.countItem(Items.DIRT) == initial - cells.size(), "the full project ledger has no missing or duplicate block");
    }

    private static void finishRetreat(GameTestHelper helper, FillPlan plan) {
        drain(plan);
        helper.assertTrue(!plan.complete(), "placing the last block alone does not erase the actual retreat obligation");
        helper.assertTrue(!plan.steppingAside().isEmpty(), "the real original builder receives its terminal walk");
        consumeMoves(helper, plan);
        drain(plan);
        helper.assertTrue(plan.complete(), "completion follows actual arrival and a fresh retreat commit");
    }

    private static void consumeMoves(GameTestHelper helper, FillPlan plan) {
        for (Grown grown : List.copyOf(plan.steppingAside())) {
            Node move = grown.nodes().getFirst();
            Mob body = (Mob) helper.getLevel().getEntity(move.worker().orElseThrow());
            helper.assertTrue(body != null, "a step-aside request names a real loaded resident");
            BlockPos target = move.spec().stances().cells().iterator().next().block(helper.getLevel());
            body.setPos(Vec3.atBottomCenterOf(target)); body.setOnGround(true);
            helper.assertTrue(Commit.run(helper.getLevel(), move, worker(body, new SimpleContainer())) instanceof Outcome.Done,
                "actual arrival and a fresh same-tick escape check consume the step-aside request: " + describe(plan));
        }
    }

    private static Node node(FillPlan plan, Job job) {
        Chores chores = new Chores(UUID.randomUUID(), "fill", (level, chore) -> new FillNode(chore, MATERIAL, plan), () -> {}, new PastDay());
        return new FillNode(new Chores.Chore(UUID.randomUUID(), job, chores), MATERIAL, plan);
    }
    private static Worker worker(Mob body, SimpleContainer pack) {
        return (Worker) Proxy.newProxyInstance(FillPlanGameTests.class.getClassLoader(), new Class<?>[] { Worker.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "body" -> body;
                case "pack" -> pack;
                case "resident" -> new Resident(body.getUUID(), PersonBody.ID);
                case "effects" -> Effects.NONE;
                case "placement" -> Placement.NOWHERE;
                case "held" -> ItemStack.EMPTY;
                case "within", "route", "supplied" -> List.of();
                default -> null;
            });
    }
    private static Mob resident(GameTestHelper helper, BlockPos feet) {
        Mob body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(feet)); body.setOnGround(true);
        io.github.izakyl.folkways.plugins.person.walk.Walking.INSTANCE.fit(body);
        body.setNoAi(false);
        helper.assertTrue(((Body) body).licences().of(BuildContent.trade()).isPresent(), "the actual resident is licensed to build");
        helper.getLevel().addFreshEntity(body);
        return body;
    }
    private static Set<BlockPos> box(BlockPos min, BlockPos max) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        BlockPos.betweenClosed(min, max).forEach(cell -> cells.add(cell.immutable()));
        return cells;
    }
    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 23; x++) for (int z = 0; z <= 23; z++) for (int y = 0; y <= 5; y++) {
            helper.getLevel().setBlock(at(helper, x, y, z), y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
    }
    private static void tunnel(GameTestHelper helper, int from, int to, boolean bothEnds) {
        ServerLevel level = helper.getLevel();
        for (int x = from - 1; x <= to + 1; x++) for (int z = 9; z <= 11; z++) for (int y = 1; y <= 3; y++) {
            if (y == 3 || z != 10 || x == to + 1 && !bothEnds) level.setBlock(at(helper, x, y, z), Blocks.STONE.defaultBlockState(), 3);
        }
    }
    private static BlockPos at(GameTestHelper helper, int x, int y, int z) { return helper.absolutePos(new BlockPos(x, y, z)); }
}
