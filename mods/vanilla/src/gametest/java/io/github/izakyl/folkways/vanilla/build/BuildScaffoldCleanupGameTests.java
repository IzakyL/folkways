package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static io.github.izakyl.folkways.vanilla.build.SiteRig.floor;
import static io.github.izakyl.folkways.vanilla.build.SiteRig.worker;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class BuildScaffoldCleanupGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuildScaffoldCleanupGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 120)
    public static void finishingTheLastTargetWaitsForDynamicScaffoldCleanup(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos goal = at(helper, 10, 9, 10);
        BlockPos oldFooting = goal.offset(0, -1, -1);
        level.setBlock(oldFooting, Blocks.STONE.defaultBlockState(), 3);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        SiteRig rig = SiteRig.start(level, target(level, goal), temporary, Set.of());
        helper.assertTrue(rig.site.pieces().stream().noneMatch(piece -> kind(piece) == BuildJob.Kind.RAISE),
            "the blueprint plan contains no temporary access");
        level.setBlock(oldFooting, Blocks.AIR.defaultBlockState(), 3);
        rig.site.tick(level);
        BuildSite.Piece raising = rig.site.pieces().stream()
            .filter(piece -> kind(piece) == BuildJob.Kind.RAISE).findFirst().orElseThrow();
        helper.assertTrue(raising.work().id() < 0, "the missing floor generates a dynamic access column");
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(at(helper, 10, 1, 8)));
        Worker worker = worker(body);
        helper.assertTrue(Commit.run(level, raising.action(), worker) instanceof Outcome.Done,
            "dynamic access is raised");
        rig.end(raising.action(), Ending.DONE);
        rig.site.tick(level);
        helper.assertTrue(rig.work(helper, goal, worker) instanceof Outcome.Done, "the last target finishes");
        helper.assertTrue(!temporary.isEmpty(), "the access column still stands before the next access scan");
        helper.assertTrue(!rig.site.complete(), "target completion cannot retire a site with temporary scaffolds");
        helper.startSequence().thenExecuteAfter(41, () -> {
            rig.site.tick(level);
            helper.assertTrue(rig.site.pieces().stream().anyMatch(piece -> kind(piece) == BuildJob.Kind.LOWER),
                "the next access scan still has a live site on which to schedule cleanup");
            rig.workAll(helper, worker);
            helper.assertTrue(temporary.isEmpty() && rig.site.complete(), "cleanup releases final site completion");
            body.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void finishingTheTargetCancelsAccessThatWasNeverRaised(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos goal = at(helper, 10, 9, 10);
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        SiteRig rig = SiteRig.start(level, target(level, goal), temporary, Set.of());
        rig.site.tick(level);
        helper.assertTrue(rig.site.pieces().stream().anyMatch(piece -> kind(piece) == BuildJob.Kind.RAISE),
            "an access request is waiting for its materials");
        level.setBlock(goal, Blocks.STONE.defaultBlockState(), 3);
        rig.site.tick(level);
        helper.startSequence().thenExecuteAfter(41, () -> {
            rig.site.tick(level);
            helper.assertTrue(temporary.isEmpty(), "no nonexistent scaffold is recorded or refunded");
            helper.assertTrue(rig.site.pieces().stream().noneMatch(piece -> kind(piece) == BuildJob.Kind.RAISE),
                "finished work no longer keeps an unbuilt access request alive");
            helper.assertTrue(rig.site.complete(), "unbuilt access cannot prevent a finished site from retiring");
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 320)
    public static void aResidentDescendsBeforeRemovingTheScaffoldItStandsOn(GameTestHelper helper) {
        descent(helper, false, false, false, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 320)
    public static void cleanupAsksAnotherIdleResidentToDescendSafely(GameTestHelper helper) {
        descent(helper, true, false, false, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 360)
    public static void aResidentReturnsFromTheHorizontalEndBeforeScaffoldCleanup(GameTestHelper helper) {
        descent(helper, true, true, false, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 360)
    public static void cleanupCentresAResidentSupportedByTheScaffoldCorner(GameTestHelper helper) {
        descent(helper, true, false, true, false);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 760)
    public static void cleanupRetriesAnExpiredReturnWhenTheResidentCanMoveAgain(GameTestHelper helper) {
        descent(helper, true, false, false, true);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 360)
    public static void cleanupLetsAResidentClimbOutOfThePitBeforeRemovingItsColumn(GameTestHelper helper) {
        pit(helper);
        ServerLevel level = helper.getLevel();
        BlockPos base = at(helper, 9, 1, 9);
        List<BlockPos> column = java.util.stream.IntStream.range(0, 4).mapToObj(base::above).toList();
        column.forEach(cell -> level.setBlock(cell, ScaffoldAction.state(), 3));
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        temporary.added(column.stream().map(cell -> WorldPos.of(level, cell)).toList());
        Mob occupant = PersonBody.RESIDENT.get().create(level);
        occupant.setPos(Vec3.atBottomCenterOf(at(helper, 10, 1, 10)));
        occupant.setOnGround(true);
        level.addFreshEntity(occupant);
        Worker worker = worker(occupant);
        column.forEach(cell -> level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3));
        helper.assertTrue(ScaffoldAction.neededForReturn(level, occupant, column),
            "a still-unbuilt access request is needed for the enclosed resident's return");
        column.forEach(cell -> level.setBlock(cell, ScaffoldAction.state(), 3));
        BuildAction lower = BuildAction.of(Step.lower(column).job(level, target(level, base.east(4)), Stances.WHEREVER),
            temporary);
        helper.assertTrue(Commit.run(level, lower, worker) instanceof Outcome.Failed,
            "the pit floor is not a safe place to remove its only route to permanent upper ground");
        boolean[] removed = {false};
        float health = occupant.getHealth();
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (!removed[0] && ++ticks[0] % 20 == 0) {
                removed[0] = Commit.run(level, lower, worker) instanceof Outcome.Done;
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(removed[0],
                "the resident follows native navigation onto permanent upper ground before cleanup: y="
                    + occupant.getY() + " pos=" + occupant.blockPosition() + " nav="
                    + occupant.getNavigation().getTargetPos() + " wanted="
                    + occupant.getMoveControl().getWantedY()))
            .thenExecute(() -> {
                helper.assertTrue(occupant.getY() >= at(helper, 0, 4, 0).getY(),
                    "the resident has physically left the pit floor");
                helper.assertTrue(!level.getBlockState(occupant.blockPosition().below()).is(Blocks.SCAFFOLDING),
                    "permanent ground supports the resident when its access is removed");
                helper.assertTrue(occupant.getHealth() >= health, "cleanup causes no fall damage");
                helper.assertTrue(temporary.isEmpty(), "the entire temporary column is recovered");
            }).thenExecuteAfter(20, () -> {
                helper.assertTrue(occupant.isAlive(), "a completed native return remains safe during later goal ticks");
                column.forEach(cell -> level.setBlock(cell, ScaffoldAction.state(), 3));
                temporary.added(column.stream().map(cell -> WorldPos.of(level, cell)).toList());
                occupant.setPos(Vec3.atBottomCenterOf(at(helper, 10, 1, 10)));
                helper.assertTrue(Commit.run(level, lower, worker) instanceof Outcome.Failed,
                    "a former upper return does not count as safety after the resident goes back into the pit");
                occupant.discard();
                column.forEach(cell -> level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3));
                temporary.removed(column.stream().map(cell -> WorldPos.of(level, cell)).toList());
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 420)
    public static void aCompletedCleanupStayDoesNotLeadItsResidentBackIntoThePit(GameTestHelper helper) {
        pit(helper);
        ServerLevel level = helper.getLevel();
        BlockPos base = at(helper, 9, 1, 9);
        List<BlockPos> column = java.util.stream.IntStream.range(0, 4).mapToObj(base::above).toList();
        column.forEach(cell -> level.setBlock(cell, ScaffoldAction.state(), 3));
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        temporary.added(column.stream().map(cell -> WorldPos.of(level, cell)).toList());
        SiteRig rig = SiteRig.start(level, target(level, base.below()), temporary, Set.of());
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(at(helper, 10, 1, 10)));
        body.setOnGround(true);
        level.addFreshEntity(body);
        Worker worker = worker(body);
        worker.pack().setItem(1, ItemStack.EMPTY);
        SiteRig.Shift shift = rig.shift();
        helper.assertTrue(shift.step(level, worker) instanceof Outcome.Failed,
            "the stay first requests a physical return to permanent upper ground");
        boolean[] removed = {false};
        helper.onEachTick(() -> {
            shift.tick(level, worker);
            rig.site.tick(level);
            removed[0] = temporary.isEmpty();
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(removed[0],
                "the cleanup stay permits its resident to finish the native return: y=" + body.getY()
                    + " pos=" + body.blockPosition() + " nav=" + body.getNavigation().getTargetPos()
                    + " wanted=" + body.getMoveControl().getWantedX() + "," + body.getMoveControl().getWantedY()
                    + "," + body.getMoveControl().getWantedZ() + " last=" + shift.outcome))
            .thenExecute(() -> helper.assertTrue(body.getY() >= at(helper, 0, 4, 0).getY(),
                "the cleanup operation finishes on permanent upper ground"))
            .thenExecuteAfter(20, () -> {
                helper.assertTrue(body.isAlive() && body.getY() >= at(helper, 0, 4, 0).getY(),
                    "later approach ticks do not guide the resident back down the obsolete cleanup route");
                helper.assertTrue(!level.getBlockState(body.blockPosition().below()).is(Blocks.SCAFFOLDING),
                    "the resident remains supported by permanent ground after its column is recovered");
                helper.assertTrue(temporary.isEmpty() && rig.site.complete(),
                    "the site has fully recovered its access");
                body.discard();
                rig.site.close();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void cleanupDoesNotMakeAGroundResidentClimbAnOrdinaryTower(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos base = at(helper, 10, 1, 10);
        List<BlockPos> column = java.util.stream.IntStream.range(0, 7).mapToObj(base::above).toList();
        column.forEach(cell -> level.setBlock(cell, ScaffoldAction.state(), 3));
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        temporary.added(column.stream().map(cell -> WorldPos.of(level, cell)).toList());
        Mob body = PersonBody.RESIDENT.get().create(level);
        body.setPos(Vec3.atBottomCenterOf(base.south(2)));
        body.setOnGround(true);
        level.addFreshEntity(body);
        BuildAction lower = BuildAction.of(Step.lower(column).job(level, target(level, base.east(4)), Stances.WHEREVER),
            temporary);
        helper.assertTrue(Commit.run(level, lower, worker(body)) instanceof Outcome.Done,
            "a resident already able to walk away on the ground does not delay tower cleanup");
        helper.assertTrue(body.getY() == base.getY(), "cleanup has not moved the ground resident upwards");
        body.discard();
        helper.succeed();
    }

    private static void descent(GameTestHelper helper, boolean otherWorker, boolean horizontal, boolean corner,
            boolean paused) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos base = at(helper, 10, 1, 10);
        List<BlockPos> column = new ArrayList<>(java.util.stream.IntStream.range(0, 7).mapToObj(base::above).toList());
        BlockPos top = column.getLast();
        column.forEach(cell -> level.setBlock(cell, ScaffoldAction.state(), 3));
        if (horizontal) {
            for (int distance = 1; distance <= 3; distance++) {
                BlockPos cell = top.east(distance);
                level.setBlock(cell, ScaffoldAction.state().setValue(ScaffoldingBlock.DISTANCE, distance)
                    .setValue(ScaffoldingBlock.BOTTOM, true), 3);
                column.add(cell);
            }
        }
        TemporaryScaffolds temporary = new TemporaryScaffolds(() -> { });
        temporary.added(column.stream().map(cell -> WorldPos.of(level, cell)).toList());
        Mob occupant = PersonBody.RESIDENT.get().create(level);
        occupant.setPos(corner
            ? new Vec3(base.getX() - 0.1477D, top.getY() + 1.0D, base.getZ() - 0.2338D)
            : Vec3.atBottomCenterOf(column.getLast().above()));
        occupant.setOnGround(true);
        // A temporary interruption can outlast one return request. Once movement resumes, another cleanup
        // attempt must be able to ask for the same descent instead of remembering that request forever.
        occupant.setNoAi(paused);
        level.addFreshEntity(occupant);
        Mob hand = otherWorker ? PersonBody.RESIDENT.get().create(level) : occupant;
        if (otherWorker) {
            hand.setPos(Vec3.atBottomCenterOf(base.south(2)));
            hand.setOnGround(true);
            level.addFreshEntity(hand);
        }
        Worker worker = worker(hand);
        float health = occupant.getHealth();
        BuildAction lower = BuildAction.of(Step.lower(column).job(level, target(level, base.offset(4, 0, 0)),
            Stances.WHEREVER),
            temporary);
        helper.assertTrue(Commit.run(level, lower, worker) instanceof Outcome.Failed,
            "cleanup initially waits rather than removing a column supporting a resident");
        helper.assertTrue(column.stream().allMatch(cell -> level.getBlockState(cell).is(Blocks.SCAFFOLDING)),
            "all supporting scaffold remains while the resident is high up");
        boolean[] removed = {false};
        boolean[] returnedToColumn = {!horizontal && !corner};
        int[] recovered = {0};
        int[] ticks = {0};
        helper.onEachTick(() -> {
            if (paused && ticks[0] == 450) {
                occupant.setNoAi(false);
            }
            double dx = occupant.getX() - (base.getX() + 0.5D);
            double dz = occupant.getZ() - (base.getZ() + 0.5D);
            returnedToColumn[0] |= dx * dx + dz * dz <= 0.75D * 0.75D
                && occupant.getY() >= top.getY() - 1.0D;
            if (!removed[0] && ++ticks[0] % 20 == 0) {
                Outcome outcome = Commit.run(level, lower, worker);
                if (outcome instanceof Outcome.Done done) {
                    removed[0] = true;
                    recovered[0] = done.made().stream().filter(stack -> stack.is(Items.SCAFFOLDING))
                        .mapToInt(ItemStack::getCount).sum();
                }
            }
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(removed[0],
                "the resident physically descends far enough for safe cleanup: y=" + occupant.getY()
                    + " pos=" + occupant.blockPosition() + " nav=" + occupant.getNavigation().getTargetPos()))
            .thenExecute(() -> {
                helper.assertTrue(occupant.getY() <= base.getY() + 1.25D, "cleanup happened only near ground");
                helper.assertTrue(occupant.getHealth() >= health && occupant.fallDistance < 2.0F,
                    "the resident was neither dropped nor hurt by cleanup");
                helper.assertTrue(returnedToColumn[0], "the resident returned along the deck to the actual column top");
                helper.assertTrue(temporary.isEmpty(), "cleanup removes the owned structure");
                helper.assertTrue(column.stream().noneMatch(cell -> level.getBlockState(cell).is(Blocks.SCAFFOLDING)),
                    "cleanup removes every vertical and horizontal scaffold cell");
                helper.assertValueEqual(recovered[0], column.size(),
                    "cleanup refunds every scaffold cell exactly once");
            }).thenExecuteAfter(20, () -> {
                helper.assertTrue(occupant.isAlive(), "a completed descent remains safe during later goal ticks");
                occupant.discard();
                if (otherWorker) { hand.discard(); }
            }).thenSucceed();
    }

    private static BuildJob.Kind kind(BuildSite.Piece piece) {
        return piece.work().step().orElseThrow().kind();
    }

    private static BuildTarget target(ServerLevel level, BlockPos goal) {
        return new BuildTarget(WorldPos.of(level, goal).realm(), Map.of(goal, Blocks.STONE.defaultBlockState()));
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static void pit(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        for (int x = 4; x <= 18; x++) {
            for (int z = 4; z <= 18; z++) {
                for (int y = 1; y <= 4; y++) {
                    boolean outside = x < 8 || x > 14 || z < 8 || z > 14;
                    boolean wall = x == 8 || x == 14 || z == 8 || z == 14;
                    if (wall || outside && y <= 3) {
                        level.setBlock(at(helper, x, y, z), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        }
    }

}
