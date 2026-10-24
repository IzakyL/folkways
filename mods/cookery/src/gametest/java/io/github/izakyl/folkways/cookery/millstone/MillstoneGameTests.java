package io.github.izakyl.folkways.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Situation;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.plan.Stands;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.plan.Unassigned;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.testing.RecoveryAsking;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MillstoneGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(MillstoneGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void nativeGrindingUnbindsAndWalksOutBeforeDeliveringActualFlour(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        MillstoneWork work = rig.plan(3);
        rig.load(work);
        Node drive = ((Workload.Within.Turn.Piece) work.next(rig.level, rig)).node();
        helper.assertTrue(rig.mill().isBoundTo(rig.actor), "the assigned resident is the real motor");
        helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "the accepted input was spent exactly once");
        float walked = rig.actor.walkDist;
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.mill().getInput().isEmpty(),
            "the original block ticker finishes grinding")).thenExecute(() -> {
                helper.assertTrue(!rig.mill().hasEntity(), "the finishing native tick releases its motor");
                helper.assertTrue(MillstoneDrive.exiting(rig.actor), "unbinding alone does not pretend the resident left");
                helper.assertTrue(!rig.mill().isOutputEmpty(), "real output remains held against asynchronous ejection");
            }).thenWaitUntil(() -> helper.assertTrue(!((Workload.Continuous) drive.spec().workload()).holds(rig.level, rig),
                "the resident actually walks to its safe exit")).thenExecute(() -> {
                    try {
                        helper.assertTrue(rig.actor.walkDist > walked + 0.05F, "the exit required physical movement");
                        helper.assertTrue(Commit.run(rig.level, drive, rig) instanceof Outcome.Done, "driving completes only after exit");
                        work.ended(rig.level, drive, Ending.DONE);
                        helper.assertTrue(rig.step(work) instanceof Outcome.Done, "the actual mill output is extracted");
                        helper.assertValueEqual(rig.count(ModItems.FLOUR.get()), 3L, "each real input produces one real flour");
                        helper.assertTrue(work.commit(rig.level, rig) instanceof Outcome.Done, "the batch settles once");
                        helper.assertTrue(rig.mill().isOutputEmpty() && !rig.mill().hasEntity(), "the station is empty and unbound");
                        helper.assertTrue(rig.level.getEntitiesOfClass(ItemEntity.class,
                            new net.minecraft.world.phys.AABB(rig.cell).inflate(5)).isEmpty(), "the products were not also ejected");
                    } finally { rig.close(); }
                }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void aPassingResidentCannotBindButNativeAnimalsStillCan(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            helper.assertTrue(!rig.mill().canBindEntity(rig.actor), "ordinary residents have no motor permission");
            rig.mill().bindEntity(rig.actor);
            helper.assertTrue(!rig.mill().hasEntity(), "a direct unpermitted native binding is also refused");
            var cow = EntityType.COW.create(rig.level);
            try {
                helper.assertTrue(rig.mill().canBindEntity(cow), "the native animal eligibility rules remain active");
                rig.mill().bindEntity(cow);
                helper.assertTrue(rig.mill().isBoundTo(cow), "an animal can still drive the native mill");
                rig.mill().unbindEntity();
            } finally { cow.discard(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void noReachableExitRejectsLoadingAndRefundsTheOriginalGoods(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            MillstoneWork work = rig.plan(3);
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) >= 3) {
                        rig.level.setBlockAndUpdate(rig.cell.offset(x, -1, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            helper.assertTrue(rig.step(work) instanceof Outcome.Failed, "a motor must have a reachable safe exit before starting");
            helper.assertValueEqual(rig.count(Items.WHEAT), 3L, "rejected loading refunds all three ingredients");
            helper.assertTrue(rig.mill().getInput().isEmpty() && !rig.mill().hasEntity(), "no half-started batch or motor is left behind");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withdrawingAnAlreadyPlannedMillNeverSpendsItsIngredients(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            MillstoneWork work = rig.plan(3);
            Node prepared = ((Workload.Within.Turn.Piece) work.next(rig.level, rig)).node();
            rig.bookings.withdraw(rig.at);
            helper.assertTrue(Commit.run(rig.level, prepared, rig) instanceof Outcome.Failed, "the old generation cannot load");
            helper.assertValueEqual(rig.count(Items.WHEAT), 3L, "withdrawal cannot consume planned ingredients");
            helper.assertTrue(!rig.mill().hasEntity(), "withdrawal cannot bind the old worker");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void cancellationUnbindsImmediatelyAndTheResidentReallyLeaves(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        MillstoneWork work = rig.plan(3);
        rig.load(work);
        float walked = rig.actor.walkDist;
        MillstoneDrive.interrupt(rig.actor);
        rig.hold.release(Ending.REVOKED);
        helper.assertTrue(!rig.mill().hasEntity(), "cancellation releases the original motor immediately");
        helper.assertValueEqual(rig.mill().getInput().getCount(), 3, "canceling never refunds consumed ingredients");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(MillstoneDrive.leave(rig.level, rig.actor),
            "the interrupted resident walks out through ordinary locomotion")).thenExecute(() -> {
                try {
                    helper.assertTrue(rig.actor.walkDist > walked + 0.05F, "leaving is not a teleport or an in-place success");
                    helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "the physical input has not been duplicated in the pack");
                    var recovery = new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {});
                    helper.assertTrue(recovery.size() == 1 && recovery.getFirst().nodes().getFirst().spec().needs().isEmpty(),
                        "recovery adopts the accepted native batch without another input budget");
                } finally { rig.close(); }
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void revokingTheActiveMotorLicenceLeavesAndPausesItsActualBatchUntilRestored(GameTestHelper helper) {
        Rig rig = new Rig(helper); MillstoneWork original = rig.plan(3); rig.load(original);
        Node driving = ((Workload.Within.Turn.Piece) original.next(rig.level, rig)).node();
        var licence = rig.actor.licences().trade(WaresContent.trade()).orElseThrow();
        ItemStack accepted = rig.mill().getInput().copy();
        int initialProgress = rig.mill().saveWithoutMetadata(rig.level.registryAccess()).getInt("Progress");
        int[] paused = { -1 }; float[] walked = { 0 }; MillstoneWork[] resumed = { null }; Node[] resumedDrive = { null };
        Grown[] wanted = { null }; var asking = new RecoveryAsking(helper);
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            int actual = rig.mill().saveWithoutMetadata(rig.level.registryAccess()).getInt("Progress");
            helper.assertTrue(actual > 100 && actual < initialProgress && rig.mill().isBoundTo(rig.actor), "the real native ticker is actively grinding the already accepted unfinished input");
            walked[0] = rig.actor.walkDist;
            rig.actor.licences().allow(licence, false);
            helper.assertTrue(rig.actor.licences().of(WaresContent.trade()).isEmpty()
                && !MillstoneDrive.permitted(rig.mill(), rig.actor), "withholding the actual task licence immediately revokes the native motor permission");
        }).thenWaitUntil(() -> helper.assertTrue(!rig.mill().hasEntity(), "the native tick releases the unlicensed motor before the accepted batch finishes"))
            .thenExecute(() -> {
                paused[0] = rig.mill().saveWithoutMetadata(rig.level.registryAccess()).getInt("Progress");
                helper.assertTrue(paused[0] > 0 && MillstoneDrive.exiting(rig.actor), "the unfinished native batch is paused while the released resident still owes a physical departure");
                helper.assertTrue(ItemStack.matches(rig.mill().getInput(), accepted), "revocation preserves the exact native input and components");
            }).thenWaitUntil(() -> helper.assertTrue(!((Workload.Continuous) driving.spec().workload()).holds(rig.level, rig)
                && !MillstoneDrive.exiting(rig.actor), "the active Within drive ends only after its former motor actually walks clear"))
            .thenExecute(() -> {
                helper.assertTrue(rig.actor.walkDist > walked[0] + 0.05F, "revoking the vocation never substitutes teleportation or in-place unbinding for departure");
                Outcome failure = Commit.run(rig.level, driving, rig);
                helper.assertTrue(failure instanceof Outcome.Failed, "the unlicensed old drive does not pretend its unfinished recipe completed");
                var ending = new Ending.Failed(driving.id(), ((Outcome.Failed) failure).why());
                original.ended(rig.level, driving, ending); driving.released(rig.level, rig, ending); original.ended(rig.level, ending);
                rig.hold.release(ending); rig.hold = null;
                helper.assertTrue(new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {})
                    .getFirst().nodes().getFirst().spec().needs().isEmpty(), "the original accepted batch remains available to recovery without another wheat budget");
            }).thenIdle(80).thenExecute(() -> {
                helper.assertTrue(!rig.mill().hasEntity() && !MillstoneDrive.exiting(rig.actor), "a revoked motor cannot be captured again while idle");
                helper.assertValueEqual(rig.mill().saveWithoutMetadata(rig.level.registryAccess()).getInt("Progress"), paused[0], "the original native progress remains exactly paused without an authorized motor");
                helper.assertTrue(ItemStack.matches(rig.mill().getInput(), accepted) && rig.mill().isOutputEmpty(), "neither accepted input nor finished output is invented while the vocation is disabled");
                helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "the cancelled work never refunds native-owned wheat");
                rig.actor.licences().allow(licence, true);
                wanted[0] = new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).getFirst();
                resumed[0] = (MillstoneWork) wanted[0].nodes().getFirst();
                asking.offer(List.of(wanted[0]));
                helper.assertTrue(resumed[0].spec().needs().isEmpty() && resumed[0].planned(rig.level).isEmpty(), "restoring the task licence adopts the same persistent batch without reinserting input");
                resumedDrive[0] = ((Workload.Within.Turn.Piece) resumed[0].next(rig.level, rig)).node();
                helper.assertTrue(((Workload.Continuous) resumedDrive[0].spec().workload()).holds(rig.level, rig)
                    && rig.mill().isBoundTo(rig.actor), "the restored resident really rebinds as the native motor");
                helper.assertValueEqual(rig.mill().saveWithoutMetadata(rig.level.registryAccess()).getInt("Progress"), paused[0], "binding recovery does not reset the native countdown");
                asking.assertKept(wanted[0], new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
            }).thenIdle(40).thenExecute(() -> asking.assertKept(wanted[0], new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {})))
            .thenIdle(40).thenExecute(() -> asking.assertKept(wanted[0], new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {})))
            .thenWaitUntil(() -> helper.assertTrue(rig.mill().getInput().isEmpty(), "the resumed native ticker eventually produces the original real output"))
            .thenWaitUntil(() -> helper.assertTrue(!((Workload.Continuous) resumedDrive[0].spec().workload()).holds(rig.level, rig), "the restored motor also physically leaves after successful grinding"))
            .thenExecute(() -> {
                try {
                    helper.assertTrue(Commit.run(rig.level, resumedDrive[0], rig) instanceof Outcome.Done, "the recovered native drive actually completes");
                    resumed[0].ended(rig.level, resumedDrive[0], Ending.DONE);
                    helper.assertTrue(rig.step(resumed[0]) instanceof Outcome.Done && resumed[0].commit(rig.level, rig) instanceof Outcome.Done, "the actual stored output is collected and settled once");
                    asking.assertKept(wanted[0], new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
                    helper.assertValueEqual(rig.count(ModItems.FLOUR.get()), 3L, "the exact three original wheat become three actual flour after licence restoration");
                    helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "no raw input is duplicated by either revocation or restoration");
                    helper.assertTrue(rig.mill().isOutputEmpty() && !rig.mill().hasEntity()
                        && rig.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(rig.cell).inflate(5)).isEmpty(), "native slots, motor and loose outputs all settle without duplicates");
                    resumed[0].ended(rig.level, Ending.DONE); asking.finished(wanted[0], Ending.DONE);
                } finally { if (resumed[0] != null) { resumed[0].ended(rig.level, Ending.DONE); } rig.close(); }
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void reloadedNativeMotorCannotKeepItsOldRuntimePermission(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        MillstoneWork work = rig.plan(3);
        rig.load(work);
        var saved = rig.mill().saveWithFullMetadata(rig.level.registryAccess());
        var replacement = new MillstoneBlockEntity(rig.cell, rig.mill().getBlockState());
        replacement.loadWithComponents(saved, rig.level.registryAccess());
        rig.level.removeBlockEntity(rig.cell);
        rig.level.setBlockEntity(replacement);
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(!replacement.hasEntity(), "a loaded entity id is not an authorized live motor lease");
                helper.assertValueEqual(replacement.getInput().getCount(), 3, "reload preserves actual accepted input");
                helper.assertTrue(MillstoneBatch.read(replacement, rig.level).isPresent(), "the persistent batch survives release");
                helper.assertTrue(MillstoneDrive.exiting(rig.actor), "the released resident still owes a real exit after reload");
                helper.succeed();
            } finally { rig.close(); }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void removingTheColonyStillLetsItsFormerMotorWalkOut(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        MillstoneWork work = rig.plan(3);
        rig.load(work);
        float walked = rig.actor.walkDist;
        new MillstoneStation().stop(rig.colony, io.github.izakyl.folkways.core.api.colony.Closing.RAZED);
        rig.hold.release(Ending.REVOKED);
        helper.assertTrue(!rig.mill().hasEntity(), "colony closure unbinds the resident immediately");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!MillstoneDrive.exiting(rig.actor),
            "the former resident exits without a remaining colony urge source")).thenExecute(() -> {
                try {
                    helper.assertTrue(rig.actor.walkDist > walked + 0.05F, "the independent exit uses actual movement");
                    helper.assertValueEqual(rig.mill().getInput().getCount(), 3, "physical input remains after closure");
                    helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "closure never duplicates accepted input");
                } finally { rig.close(); }
            }).thenSucceed();
    }
    @GameTest(template = "empty")
    public static void aMillstonePartIsVisibleButAnExternalWallStillBlocksLoading(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            helper.assertTrue(MillstoneStation.inSight(rig.level, rig.actor.blockPosition(), rig.cell),
                "hitting this millstone's outer part still reaches the same real appliance");
            MillstoneWork work = rig.plan(3);
            rig.level.setBlockAndUpdate(rig.cell.offset(2, 0, 0), Blocks.STONE.defaultBlockState());
            rig.level.setBlockAndUpdate(rig.cell.offset(2, 1, 0), Blocks.STONE.defaultBlockState());
            helper.assertTrue(!MillstoneStation.inSight(rig.level, rig.actor.blockPosition(), rig.cell),
                "the multi-block sight rule does not reach through an unrelated wall");
            helper.assertTrue(rig.step(work) instanceof Outcome.Failed, "the blocked interaction fails before native loading");
            helper.assertValueEqual(rig.count(Items.WHEAT), 3L, "the blocked interaction refunds all actual input");
            helper.assertTrue(rig.mill().getInput().isEmpty() && !rig.mill().hasEntity(), "the wall leaves the native mill unchanged");
        }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 180)
    public static void blockingAllExitsDuringGrindingUnbindsAndWaitsForARealWalkOut(GameTestHelper helper) {
        Rig rig = new Rig(helper);
        rig.load(rig.plan(3));
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(Math.abs(rig.actor.getX() - rig.cell.getX() - 0.5) < 2.9
                && Math.abs(rig.actor.getZ() - rig.cell.getZ() - 0.5) < 2.9, "the native motor is safely inside the future wall");
            exitWall(rig, true);
        });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(MillstoneDrive.blocked(rig.level, rig.at),
            "a changed physical exit immediately becomes a blocked departure")).thenExecute(() -> {
                helper.assertTrue(!rig.mill().hasEntity(), "the first native update after obstruction releases the motor");
                helper.assertValueEqual(rig.mill().getInput().getCount(), 3, "interrupted grinding preserves all actual accepted inputs");
                helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "blocked departure never refunds accepted goods");
            }).thenIdle(10).thenExecute(() -> {
                helper.assertTrue(MillstoneDrive.exiting(rig.actor) && !rig.mill().hasEntity(), "a closed wall cannot count as a successful exit or rebind");
                exitWall(rig, false);
            }).thenWaitUntil(() -> helper.assertTrue(!MillstoneDrive.exiting(rig.actor),
                "opening the physical route lets the independent goal really walk clear")).thenExecute(() -> {
                    try {
                        helper.assertTrue(rig.actor.walkDist > 0.05F, "the reopened departure uses actual movement");
                        helper.assertTrue(!rig.mill().hasEntity(), "the former motor does not immediately get caught again");
                        helper.assertValueEqual(rig.mill().getInput().getCount(), 3, "the recoverable batch remains in its native slot");
                    } finally { rig.close(); }
                }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void aDeadMotorLeavesNoDepartureLeaseAndAnotherResidentCollectsTheSameNativeBatch(GameTestHelper helper) {
        Rig rig = new Rig(helper); MillstoneWork work = rig.plan(3); rig.load(work);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.mill().getInput().isEmpty(),
            "the original native ticker finishes before the motor dies")).thenExecute(() -> {
                ResidentEntity dead = rig.actor;
                try {
                    helper.assertTrue(!rig.mill().hasEntity() && MillstoneDrive.exiting(dead), "the completed motor is unbound but still owes its real departure");
                    var batch = MillstoneBatch.read(rig.mill(), rig.level).orElseThrow();
                    helper.assertTrue(batch.ready() && !rig.mill().isOutputEmpty(), "the actual finished batch is still in the native mill");
                    dead.hurt(rig.level.damageSources().genericKill(), Float.MAX_VALUE);
                    helper.assertTrue(!dead.isAlive(), "the former native motor really died before walking away");
                    rig.hold.release(Ending.REVOKED);
                    for (int x = -3; x <= 3; x++) { for (int z = -3; z <= 3; z++) {
                        if (Math.max(Math.abs(x), Math.abs(z)) == 3) { rig.level.setBlockAndUpdate(rig.cell.offset(x, -1, z), Blocks.AIR.defaultBlockState()); }
                    } }
                    MillstoneDrive.tick(rig.level, rig.colony);
                    helper.assertTrue(!MillstoneDrive.exiting(dead), "a dead actor has no physical departure obligation");
                    helper.assertTrue(!MillstoneDrive.blocked(rig.level, rig.at), "removing the old exit support cannot leave a dead actor blocking this station");
                    helper.assertTrue(MillstoneBatch.read(rig.mill(), rig.level).orElseThrow().equals(batch), "death cleanup preserves the same native-owned output batch");
                    helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "death never refunds any accepted raw ingredient");
                    for (int x = -3; x <= 3; x++) { for (int z = -3; z <= 3; z++) {
                        rig.level.setBlockAndUpdate(rig.cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                    } }
                    rig.actor = PersonBody.RESIDENT.get().create(rig.level);
                    rig.actor.setPos(Vec3.atBottomCenterOf(rig.cell.offset(3, 0, 0))); rig.actor.setOnGround(true); rig.level.addFreshEntity(rig.actor);
                    helper.assertTrue(rig.actor.isAlive() && !rig.actor.getUUID().equals(dead.getUUID()), "recovery belongs to another actual living resident");
                    var recovery = new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {});
                    MillstoneWork resumed = (MillstoneWork) recovery.getFirst().nodes().getFirst();
                    helper.assertTrue(resumed.spec().needs().isEmpty() && resumed.planned(rig.level).isEmpty(), "the live resident adopts the same batch without another raw budget");
                    Node drive = ((Workload.Within.Turn.Piece) resumed.next(rig.level, rig)).node();
                    helper.assertTrue(!((Workload.Continuous) drive.spec().workload()).holds(rig.level, rig), "the new resident has no invented duty to walk the dead actor out");
                    helper.assertTrue(Commit.run(rig.level, drive, rig) instanceof Outcome.Done, "native finished state permits recovery");
                    resumed.ended(rig.level, drive, Ending.DONE);
                    helper.assertTrue(rig.step(resumed) instanceof Outcome.Done && resumed.commit(rig.level, rig) instanceof Outcome.Done, "the live resident extracts and settles the actual stored products");
                    resumed.ended(rig.level, Ending.DONE);
                    helper.assertValueEqual(rig.count(ModItems.FLOUR.get()), 3L, "the exact original three real flour are delivered once");
                    helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "no raw input is recreated for either resident");
                    helper.assertTrue(rig.mill().isOutputEmpty() && !rig.mill().hasEntity() && !MillstoneDrive.blocked(rig.level, rig.at), "all actual outputs and runtime ownership settle cleanly");
                    helper.assertTrue(rig.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(rig.cell).inflate(5)).isEmpty(), "death and recovery do not duplicate products as drops");
                } finally { dead.discard(); rig.close(); }
            }).thenSucceed();
    }
    @GameTest(template = "empty")
    public static void arrivingWithinTheCoreStanceToleranceStillLoadsAtTheReachBoundary(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            BlockPos footing = rig.cell.offset(-3, 0, -3);
            rig.stances = Stances.at(rig.level, footing);
            rig.actor.setPos(footing.getX() + 0.397, footing.getY(), footing.getZ() - 0.118);
            BlockPos actual = rig.actor.blockPosition();
            rig.level.setBlockAndUpdate(actual.below(), Blocks.STONE.defaultBlockState());
            rig.level.setBlockAndUpdate(actual, Blocks.AIR.defaultBlockState());
            rig.level.setBlockAndUpdate(actual.above(), Blocks.AIR.defaultBlockState());
            helper.assertTrue(MillstoneStation.inSight(rig.level, footing, rig.cell), "the reserved footing sees a native part of this mill, without another test structure blocking its ray");
            helper.assertTrue(MillstoneDrive.exit(rig.level, rig.at, rig.actor).isPresent(), "the real native motor has a supported departure path before loading");
            helper.assertTrue(io.github.izakyl.folkways.core.api.terms.Reach.standingIn(rig.actor, rig.stances), "ordinary core navigation has legitimately arrived within its 0.9-block tolerance");
            helper.assertTrue(io.github.izakyl.folkways.core.api.terms.Reach.withinReach(footing, rig.cell)
                && !io.github.izakyl.folkways.core.api.terms.Reach.withinReach(rig.actor.blockPosition(), rig.cell), "rounding the actual body down crosses the reach boundary despite a valid reserved footing");
            rig.load(rig.plan(3));
            helper.assertTrue(rig.mill().isBoundTo(rig.actor) && rig.mill().getInput().getCount() == 3, "the real native mill accepts the fetched ingredients from the valid reserved stance");
            helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "the accepted three real inputs are consumed once");
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void aNativeRecoveryRootStillRequiresRoomForAllItsUnpromisedFlour(GameTestHelper helper) {
        var fixture = new PlanningFixture(helper);
        try (Rig rig = new Rig(helper)) {
            MillstoneWork original = rig.plan(3);
            rig.load(original);
            MillstoneDrive.interrupt(rig.actor);
            original.ended(rig.level, Ending.DROPPED);
            rig.hold.release(Ending.DROPPED);
            Grown recovery = new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances,
                rig.bookings, () -> {}).getFirst();
            Node root = recovery.nodes().getFirst();
            for (int slot = 0; slot < rig.pack.getContainerSize(); slot++) {
                rig.pack.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            int capacity = rig.pack.getContainerSize();
            var licence = rig.actor.licences().of(WaresContent.trade()).orElseThrow();
            var world = new Situation(fixture.ways, new Stock(List.of(), Map.of()), List.of(), Stands.building().done());
            var solver = new Solving();
            try {
                var full = new Crew.Hand(rig.actor.resident(), WorldPos.of(rig.level, rig.actor.blockPosition()),
                    0, capacity, 1, List.of(licence), List.of(), Optional.empty());
                var blocked = solver.handle(new Known(world, List.of(full)),
                    List.of(new Message.Submitted(CookeryContent.ID, recovery)), 0);
                helper.assertTrue(blocked.schedule().tours().isEmpty() && blocked.schedule().unassigned().stream()
                        .anyMatch(wait -> wait.node().equals(root.id()) && wait.why() == Unassigned.Reason.PACK_BUSY),
                    "an independent native recovery has no promised carry and must still reserve its real flour output");
                helper.assertValueEqual(rig.mill().getInput().getCount(), 3,
                    "rejecting a full pack neither refunds nor repeats the accepted native ingredients");
                rig.pack.setItem(0, ItemStack.EMPTY);
                var room = new Crew.Hand(full.who(), full.at(), 1, capacity, 1,
                    full.licences(), full.tools(), Optional.empty());
                var known = solver.handle(new Known(world, List.of(room)), List.of(Message.ANSWERED), 1);
                helper.assertTrue(known.schedule().unassigned().isEmpty() && known.schedule().tours().values().stream()
                        .anyMatch(tour -> tour.nodes().contains(root.id())),
                    "freeing exactly one real cell admits the unchanged recovery batch");
                long flour = known.weave().vertices().get(root.id()).plan().carrying().stream()
                    .filter(amount -> amount.spec().equals(Goods.specOf(new ItemStack(ModItems.FLOUR.get()))))
                    .mapToLong(io.github.izakyl.folkways.core.api.work.Amount::count).sum();
                helper.assertValueEqual(flour, 3L,
                    "all three real recovered products remain in the root's capacity budget without a consumer promise");
            } finally { solver.closed(); }
        } finally { fixture.worker.body().discard(); }
        helper.succeed();
    }

    private static void exitWall(Rig rig, boolean closed) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (Math.max(Math.abs(x), Math.abs(z)) == 3) {
                    rig.level.setBlockAndUpdate(rig.cell.offset(x, 0, z), (closed ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                    rig.level.setBlockAndUpdate(rig.cell.offset(x, 1, z), (closed ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                }
            }
        }
    }
    static final class Rig implements Worker, AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        Stances stances;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(12);
        ResidentEntity actor;
        Hold hold;
        Rig(GameTestHelper helper) {
            this.helper = helper; this.level = helper.getLevel();
            cell = helper.absolutePos(new BlockPos(3, 1, 3));
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(cell.offset(x, 0, z), Blocks.AIR.defaultBlockState());
                    level.setBlockAndUpdate(cell.offset(x, 1, z), Blocks.AIR.defaultBlockState());
                }
            }
            var state = ModBlocks.MILLSTONE.get().defaultBlockState();
            level.setBlockAndUpdate(cell, state);
            state.getBlock().setPlacedBy(level, cell, state, null, new ItemStack(ModItems.MILLSTONE.get()));
            at = WorldPos.of(level, cell);
            actor = PersonBody.RESIDENT.get().create(level);
            actor.setPos(Vec3.atBottomCenterOf(cell.offset(3, 0, 0)));
            actor.setOnGround(true);
            level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition());
            pack.setItem(0, new ItemStack(Items.WHEAT, 3));
        }
        MillstoneBlockEntity mill() { return (MillstoneBlockEntity) level.getBlockEntity(cell); }
        MillstoneWork plan(int count) {
            var bench = new MillstoneStation().freeze(UUID.randomUUID(), colony, level, at, stances, bookings, () -> {}).orElseThrow();
            Produce wanted = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(new ItemStack(ModItems.FLOUR.get())), count, Produce.UNBOUNDED);
            var option = bench.options(wanted, Grown.of(wanted)).getFirst();
            hold = option.holds().getFirst();
            helper.assertTrue(hold.take().isEmpty(), "the real kitchen turn is booked");
            return (MillstoneWork) option.replacement().nodes().getFirst();
        }
        void load(MillstoneWork work) {
            long before = count(Items.WHEAT);
            Outcome outcome = step(work);
            helper.assertTrue(outcome instanceof Outcome.Done, "native loading: " + outcome
                + ", supplied raw before=" + before + ", pack now=" + count(Items.WHEAT)
                + ", real input=" + mill().getInput());
        }
        Outcome step(MillstoneWork work) {
            Node node = ((Workload.Within.Turn.Piece) work.next(level, this)).node();
            Outcome outcome = Commit.run(level, node, this);
            if (outcome instanceof Outcome.Done done) {
                done.made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "actual products fit in the test pack"));
                work.ended(level, node, Ending.DONE);
            }
            return outcome;
        }
        long count(net.minecraft.world.item.Item item) { return Goods.countIn(pack, ItemSpec.of(item)); }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new IllegalStateException("unexpected spill"); }
        public Effects effects() { return Effects.NONE; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() {
            MillstoneDrive.stop(colony);
            if (hold != null) { hold.release(Ending.DROPPED); }
            actor.discard();
        }
    }
}
