package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static io.github.izakyl.folkways.plugins.build.SiteRig.floor;
import static io.github.izakyl.folkways.plugins.build.SiteRig.target;
import static io.github.izakyl.folkways.plugins.build.SiteRig.worker;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BuildApproachGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuildApproachGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aSiteTurnIsApproachedAtItsTasksWorkingPositions(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos goal = helper.absolutePos(new BlockPos(10, 1, 10));
        SiteRig rig = SiteRig.start(level, target(level, Map.of(goal, Blocks.STONE.defaultBlockState())));
        Node turn = rig.asked.getFirst().nodes().getFirst();
        var feet = turn.spec().stances().cells().stream().map(WorldPos::cell)
            .collect(java.util.stream.Collectors.toSet());
        helper.assertTrue(!feet.isEmpty() && feet.stream().allMatch(cell ->
                Footing.withFeetAt(level, cell) && Reach.withinReach(cell, goal) && !cell.equals(goal)),
            "the destinations are real working surfaces beside the target");
        helper.assertTrue(feet.stream().anyMatch(cell -> cell.distManhattan(goal) == 1),
            "the colony is asked to reach nearby work positions, without an intermediate boundary destination");
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))));
        body.setOnGround(true);
        level.addFreshEntity(body);
        var path = body.getNavigation().createPath(feet, 0);
        helper.assertTrue(path != null && path.canReach(), "ordinary navigation reaches the work positions");
        body.discard();
        rig.site.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 140)
    public static void aPendingTurnKeepsItsMaterialRetryUntilItsWorkPositionsDisappear(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos goal = helper.absolutePos(new BlockPos(10, 2, 10));
        SiteRig rig = SiteRig.start(level, target(level, Map.of(goal, Blocks.STONE.defaultBlockState())));
        Node original = rig.asked.getFirst().nodes().getFirst();
        var originalFeet = original.spec().stances().cells().stream().map(WorldPos::cell).toList();
        helper.onEachTick(() -> rig.site.tick(level));
        helper.startSequence().thenExecuteAfter(21, () -> {
            helper.assertValueEqual(rig.asked.size(), 1, "waiting for material does not replace the turn");
            level.setBlock(originalFeet.getFirst(), Blocks.STONE.defaultBlockState(), 3);
        }).thenExecuteAfter(21, () -> {
            helper.assertValueEqual(rig.asked.size(), 1,
                "new working surfaces do not cancel a turn whose original positions remain usable");
            // Another piece raises the surrounding floor while this turn is waiting for its materials.
            originalFeet.forEach(feet -> level.setBlock(feet, Blocks.STONE.defaultBlockState(), 3));
        }).thenExecuteAfter(21, () -> {
            helper.assertTrue(rig.withdrawn.contains(original.id()), "the vanished working surface withdraws its turn");
            Node replacement = rig.asked.getLast().nodes().getFirst();
            helper.assertTrue(!replacement.id().equals(original.id()),
                "the pending work is asked for on the new floor");
            helper.assertTrue(replacement.spec().stances().cells().stream().allMatch(feet ->
                    !originalFeet.contains(feet.cell()) && Footing.withFeetAt(level, feet.cell())),
                "the replacement uses current usable work positions");
            helper.assertValueEqual(replacement.spec().needs(), original.spec().needs(),
                "its material demand is preserved");
        }).thenExecuteAfter(21, () -> {
            helper.assertValueEqual(rig.asked.size(), 2, "unchanged geometry preserves the replacement's retry");
            rig.site.close();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aNewWorkSurfaceWakesWorkThatHadNowhereToStand(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos goal = helper.absolutePos(new BlockPos(10, 100, 10));
        SiteRig rig = SiteRig.start(level, target(level, Map.of(goal, Blocks.STONE.defaultBlockState())));
        helper.assertTrue(rig.asked.isEmpty(), "a task with no current working surface waits without sending anyone: "
            + rig.asked.stream().flatMap(work -> work.nodes().stream()).map(node -> node.spec().stances()).toList());
        BlockPos floor = goal.east(2).below();
        level.setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
        rig.site.tick(level);
        helper.assertTrue(!rig.asked.isEmpty(), "a new non-target floor wakes the waiting task");
        helper.assertTrue(rig.asked.getLast().nodes().getFirst().spec().stances().cells().stream()
                .anyMatch(feet -> feet.block(level).equals(floor.above())),
            "the task offers the new real working surface");
        helper.assertTrue(level.getBlockState(goal).isAir(), "the geometry event did not complete the target");
        rig.site.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 140)
    public static void aFailedApproachCanUseNewWorkPositionsEvenWhenTheOldFloorStillExists(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos goal = helper.absolutePos(new BlockPos(10, 2, 10));
        SiteRig rig = SiteRig.start(level, target(level, Map.of(goal, Blocks.STONE.defaultBlockState())));
        Node original = rig.asked.getFirst().nodes().getFirst();
        BlockPos raisedFloor = original.spec().stances().cells().iterator().next().block(level);
        level.setBlock(raisedFloor, Blocks.STONE.defaultBlockState(), 3);
        helper.onEachTick(() -> rig.site.tick(level));
        helper.startSequence().thenExecuteAfter(21, () -> {
            helper.assertValueEqual(rig.asked.size(), 1,
                "an ordinary approach is preserved while old positions remain");
            helper.assertTrue(original.spec().stances().cells().stream().anyMatch(feet ->
                    Footing.withFeetAt(level, feet.block(level))), "some old work positions are still valid floor");
            original.failing(level, LaborRefusal.NO_WAY_THERE);
        }).thenExecuteAfter(21, () -> {
            helper.assertTrue(rig.withdrawn.contains(original.id()),
                "actual travel failure can reconsider the old approach");
            Node replacement = rig.asked.getLast().nodes().getFirst();
            helper.assertTrue(!replacement.spec().stances().equals(original.spec().stances()),
                "the new turn offers the changed actual work positions");
            int asks = rig.asked.size();
            replacement.failing(level, LaborRefusal.NO_WAY_THERE);
            rig.site.tick(level);
            helper.assertValueEqual(rig.asked.size(), asks,
                "a failure with the same destinations keeps ordinary retry");
        }).thenExecuteAfter(21, () -> {
            helper.assertValueEqual(rig.asked.size(), 2, "unchanged failed destinations are not repeatedly replaced");
            rig.site.close();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 280)
    public static void anIdleSiteAsksAgainWhenItsDelayExpiresWithoutMoreWorldChanges(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos goal = helper.absolutePos(new BlockPos(10, 1, 10));
        SiteRig rig = SiteRig.start(level, target(level, Map.of(goal, Blocks.STONE.defaultBlockState())));
        Node original = rig.asked.getFirst().nodes().getFirst();
        rig.end(original, Ending.DROPPED);
        rig.site.tick(level);
        helper.assertValueEqual(rig.asked.size(), 1, "an unsuccessful turn first rests the site");
        helper.onEachTick(() -> rig.site.tick(level));
        helper.startSequence().thenExecuteAfter(201, () -> {
            helper.assertValueEqual(rig.asked.size(), 2, "the expired delay wakes the still-pending task");
            helper.assertTrue(!rig.asked.getLast().nodes().getFirst().id().equals(original.id()),
                "a new turn is available to the colony");
        }).thenExecuteAfter(21, () -> {
            helper.assertValueEqual(rig.asked.size(), 2, "the new turn keeps its normal material and travel retries");
            rig.site.close();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aDeepExcavationBringsOnlyTheAccessMaterialItWillNeed(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        Map<BlockPos, BlockState> excavation = new LinkedHashMap<>();
        for (int y = -3; y <= 0; y++) {
            BlockPos cell = helper.absolutePos(new BlockPos(10, y, 10));
            level.setBlock(cell, Blocks.STONE.defaultBlockState(), 3);
            excavation.put(cell, Blocks.AIR.defaultBlockState());
        }
        BlockPos wall = helper.absolutePos(new BlockPos(11, 0, 10));
        excavation.put(wall, Blocks.STONE.defaultBlockState());
        SiteRig deep = SiteRig.start(level, target(level, excavation));
        var needs = deep.asked.getFirst().nodes().getFirst().spec().needs();
        helper.assertTrue(needs.stream().anyMatch(need -> need.spec().item()
            .filter(ResourceLocation.withDefaultNamespace("scaffolding")::equals).isPresent()
                && need.count() == 5 && need.carried()),
            "a four-block descent brings five real scaffolds before leaving the surface");
        deep.site.close();
        excavation.remove(wall);
        SiteRig clearing = SiteRig.start(level, target(level, excavation));
        helper.assertTrue(clearing.asked.stream().flatMap(grown -> grown.nodes().stream())
                .flatMap(node -> node.spec().needs().stream())
                .noneMatch(need -> need.spec().item()
                    .filter(ResourceLocation.withDefaultNamespace("scaffolding")::equals).isPresent()),
            "ordinary clearing with no enclosing structure does not require scaffolds");
        clearing.site.close();
        SiteRig shallow = SiteRig.start(level, target(level, Map.of(
            helper.absolutePos(new BlockPos(10, 0, 10)), Blocks.AIR.defaultBlockState(),
            wall, Blocks.STONE.defaultBlockState())));
        helper.assertTrue(shallow.asked.stream().flatMap(grown -> grown.nodes().stream())
                .flatMap(node -> node.spec().needs().stream())
                .noneMatch(need -> need.spec().item()
                    .filter(ResourceLocation.withDefaultNamespace("scaffolding")::equals).isPresent()),
            "a shallow cut beside permanent structure does not require scaffolds");
        shallow.site.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void runtimeAccessReachesTheWallTopSoAResidentCanLeaveAnExcavation(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        for (int x = 4; x <= 16; x++) {
            for (int z = 4; z <= 16; z++) {
                for (int y = 1; y <= 4; y++) {
                    boolean outside = x < 7 || x > 13 || z < 7 || z > 13;
                    boolean wall = x == 7 || x == 13 || z == 7 || z == 13;
                    if (wall || outside && y <= 3) {
                        level.setBlock(helper.absolutePos(new BlockPos(x, y, z)), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        }
        Map<BlockPos, BlockState> targets = new LinkedHashMap<>();
        for (int x = 9; x <= 12; x++) {
            BlockPos cell = helper.absolutePos(new BlockPos(x, 4, 7));
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
            targets.put(cell, Blocks.STONE.defaultBlockState());
        }
        SiteRig rig = SiteRig.start(level, target(level, targets));
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(10, 1, 10))));
        body.setOnGround(true);
        level.addFreshEntity(body);
        Worker worker = worker(body);
        BlockPos outside = helper.absolutePos(new BlockPos(10, 4, 5));
        var before = body.getNavigation().createPath(outside, 0);
        helper.assertTrue(before == null || !before.canReach(), "the finished excavation wall initially has no exit");
        SiteRig.Shift shift = shift(rig);
        shift.tick(level, worker);
        helper.assertValueEqual(shift.commits, 0, "the first operation is still winding up");
        helper.assertTrue(shift.step(level, worker) instanceof Outcome.Done,
            "ordinary work finishes while access is planned");
        rig.site.tick(level);
        BuildSite.Piece access = rig.site.pieces().stream().filter(piece -> piece.work().step()
            .filter(step -> step.kind() == BuildJob.Kind.RAISE).isPresent()).findFirst().orElseThrow();
        SiteRig.Shift next = shift(rig);
        helper.assertTrue(next.step(level, worker) instanceof Outcome.Done, "temporary access is raised");
        helper.assertTrue(next.operation instanceof ScaffoldAction
                && next.operation.spec().focus().equals(access.action().spec().focus()),
            "a supplied access column is built before more ordinary work strands its resident");
        var after = body.getNavigation().createPath(outside, 0);
        helper.assertTrue(after != null && after.canReach(),
            "native navigation can climb the column over the wall top");
        body.discard();
        rig.site.close();
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void withinChoosesNearbyReadyWorkInsteadOfTheFirstPiece(GameTestHelper helper) {
        floor(helper);
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        for (int x = 8; x <= 11; x++) {
            cells.put(helper.absolutePos(new BlockPos(x, 1, 8)), Blocks.STONE.defaultBlockState());
        }
        SiteRig rig = SiteRig.start(helper.getLevel(), target(helper.getLevel(), cells));
        List<BuildSite.Piece> ready = rig.site.pieces().stream()
            .filter(piece -> rig.site.before(piece).isEmpty()).toList();
        helper.assertTrue(ready.size() >= 2, "several pieces are available to choose from");
        BlockPos nearest = ready.getLast().work().focus();
        Mob body = EntityType.PIG.create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(nearest.south()));
        SiteRig.Shift shift = shift(rig);
        helper.assertTrue(shift.step(helper.getLevel(), worker(body)) instanceof Outcome.Done
                && shift.operation.spec().focus().orElseThrow().equals(nearest),
            "a worker takes its nearest ready piece even when an earlier piece was inserted first");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void interruptedNavigationRestartsWithoutDelayingWork(GameTestHelper helper) {
        floor(helper);
        BlockPos goal = helper.absolutePos(new BlockPos(10, 8, 10));
        SiteRig rig = SiteRig.start(helper.getLevel(), target(helper.getLevel(), Map.of(goal, Blocks.STONE.defaultBlockState())));
        Mob body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(10, 1, 8))));
        body.setOnGround(true);
        Worker worker = worker(body);
        BlockPos top = raise(helper, rig, worker);
        SiteRig.Shift shift = shift(rig);
        helper.assertTrue(shift.tick(helper.getLevel(), worker) instanceof HoldingStep.Running,
            "construction starts on its work clock while the worker approaches");
        helper.assertTrue(top.equals(body.getNavigation().getTargetPos()),
            "the approach path ends at the usable scaffold top");
        body.getNavigation().stop();
        shift.tick(helper.getLevel(), worker);
        helper.assertTrue(top.equals(body.getNavigation().getTargetPos()),
            "business approach resumes while the private action is winding up");
        helper.assertTrue(shift.step(helper.getLevel(), worker)
                instanceof Outcome.Done,
            "the operation is allowed before arrival rather than waiting on navigation");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 240)
    public static void aResidentActuallyClimbsWhileItsWorkClockContinues(GameTestHelper helper) {
        floor(helper);
        BlockPos goal = helper.absolutePos(new BlockPos(10, 8, 10));
        SiteRig rig = SiteRig.start(helper.getLevel(), target(helper.getLevel(), Map.of(goal,
            Blocks.STONE.defaultBlockState())));
        Mob body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(10, 1, 8))));
        body.setOnGround(true);
        Worker worker = worker(body);
        BlockPos top = raise(helper, rig, worker);
        helper.getLevel().addFreshEntity(body);
        SiteRig.Shift shift = shift(rig);
        helper.assertTrue(shift.tick(helper.getLevel(), worker) instanceof HoldingStep.Running, "the high operation has no approach wait");
        helper.assertValueEqual(shift.commits, 0, "the public holding starts its work clock while moving");
        // Give native locomotion world ticks between public work ticks.
        helper.onEachTick(() -> {
            if (helper.getTick() % 20 == 0) {
                shift.tick(helper.getLevel(), worker);
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(body.getY() >= top.getY() - 1.0D,
                "the resident physically climbs the scaffold while approach ticks continue"))
            .thenExecute(() -> {
                helper.assertTrue(shift.step(helper.getLevel(), worker) instanceof Outcome.Done,
                    "the target is built after its ordinary work interval");
                helper.assertTrue(helper.getLevel().getBlockState(goal).is(Blocks.STONE), "the target is built");
                body.discard();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void denseAndScatteredWorkHaveTheSameOperationClock(GameTestHelper helper) {
        floor(helper);
        int[] clocks = new int[2];
        for (int layout = 0; layout < 2; layout++) {
            Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
            for (int i = 0; i < 12; i++) {
                cells.put(helper.absolutePos(new BlockPos(layout == 0 ? i + 3 : (i % 4) * 6 + 1,
                    1, layout == 0 ? 4 : (i / 4) * 6 + 7)), Blocks.STONE.defaultBlockState());
            }
            SiteRig rig = SiteRig.start(helper.getLevel(), target(helper.getLevel(), cells));
            Mob body = EntityType.PIG.create(helper.getLevel());
            body.setPos(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))));
            body.setOnGround(true);
            Worker worker = worker(body);
            SiteRig.Shift shift = shift(rig);
            for (int tick = 0; tick < 512 && shift.commits < cells.size(); tick++) {
                shift.tick(helper.getLevel(), worker);
                clocks[layout]++;
                rig.site.tick(helper.getLevel());
            }
            helper.assertValueEqual(shift.commits, cells.size(), "each operation completes through tick");
            helper.assertTrue(rig.site.complete(), "the whole layout is built");
        }
        helper.assertValueEqual(clocks[0], clocks[1], "operation time is independent of target spacing");
        helper.assertValueEqual(clocks[0], 12 * (BuildJob.LAY_TICKS + 1), "each cell costs one fixed work interval and dispatch tick");
        helper.succeed();
    }

    private static BlockPos raise(GameTestHelper helper, SiteRig rig, Worker worker) {
        helper.assertTrue(rig.site.pieces().stream().noneMatch(piece ->
                piece.work().step().orElseThrow().kind() == BuildJob.Kind.RAISE),
            "the blueprint plan contains no temporary access");
        rig.site.tick(helper.getLevel());
        BuildSite.Piece raise = rig.site.pieces().stream()
            .filter(piece -> piece.work().step().orElseThrow().kind() == BuildJob.Kind.RAISE).findFirst().orElseThrow();
        BlockPos top = raise.work().step().orElseThrow().after().keySet().stream()
            .max(java.util.Comparator.comparingInt(BlockPos::getY)).orElseThrow().above();
        helper.assertTrue(Commit.run(helper.getLevel(), raise.action(), worker) instanceof Outcome.Done,
            "the runtime access scaffold is raised");
        rig.end(raise.action(), Ending.DONE);
        rig.site.tick(helper.getLevel());
        return top;
    }

    private static SiteRig.Shift shift(SiteRig rig) {
        return rig.shift();
    }

}
