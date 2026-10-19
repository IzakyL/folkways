package io.github.izakyl.folkways.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.cookery.testing.RecoveryAsking;
import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MillstoneAnimalGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(MillstoneAnimalGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void anAlreadyBoundAnimalDrivesThePlannedNativeBatchWithoutBeingReplaced(GameTestHelper helper) {
        grind(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void anAnimalBindingAfterPlanningStillAllowsTheAcceptedOrderToAdvance(GameTestHelper helper) {
        grind(helper, false);
    }

    private static void grind(GameTestHelper helper, boolean animalFirst) {
        var rig = new MillstoneGameTests.Rig(helper);
        Mob cow = cow(rig);
        if (animalFirst) { rig.mill().bindEntity(cow); }
        MillstoneWork work = rig.plan(3);
        if (!animalFirst) { rig.mill().bindEntity(cow); }
        rig.load(work);
        helper.assertTrue(rig.mill().isBoundTo(cow) && !rig.mill().isBoundTo(rig.actor), "loading never steals the existing native animal motor");
        helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "the real native input is spent once");
        Node drive = ((Workload.Within.Turn.Piece) work.next(rig.level, rig)).node();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.mill().getInput().isEmpty(), "the real bound animal finishes grinding through the native ticker"))
            .thenIdle(65).thenExecute(() -> {
                try {
                    helper.assertTrue(rig.mill().isBoundTo(cow), "neither completion nor output retention unbinds the animal");
                    helper.assertTrue(MillstoneBatch.read(rig.mill(), rig.level).filter(MillstoneBatch.Batch::ready).isPresent(), "only the accepted batch adopts the native tick's real output fingerprint");
                    helper.assertTrue(!rig.mill().isOutputEmpty() && drops(rig) == 0, "owned native products survive several automatic-ejection periods without duplicating drops");
                    helper.assertTrue(!((Workload.Continuous) drive.spec().workload()).holds(rig.level, rig), "the resident does not owe an invented motor exit after another animal drove");
                    helper.assertTrue(Commit.run(rig.level, drive, rig) instanceof Outcome.Done, "the native animal's actual completion settles the drive phase");
                    work.ended(rig.level, drive, Ending.DONE);
                    helper.assertTrue(rig.step(work) instanceof Outcome.Done && work.commit(rig.level, rig) instanceof Outcome.Done, "the original order collects the actual native slots while the animal stays bound");
                    helper.assertValueEqual(rig.count(ModItems.FLOUR.get()), 3L, "three original wheat produce exactly three real flour");
                    helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "neither motor choice nor collection refunds raw ingredients");
                    helper.assertTrue(rig.mill().isBoundTo(cow) && rig.mill().isOutputEmpty() && MillstoneBatch.read(rig.mill(), rig.level).isEmpty(), "collection clears ownership and products without touching the animal");
                } finally { cow.discard(); rig.close(); }
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void cancellationAndReloadKeepTheAnimalDrivenAcceptedBatchRecoverableWithoutNewInput(GameTestHelper helper) {
        var rig = new MillstoneGameTests.Rig(helper);
        Mob cow = cow(rig); rig.mill().bindEntity(cow);
        MillstoneWork original = rig.plan(3); rig.load(original);
        Node oldDrive = ((Workload.Within.Turn.Piece) original.next(rig.level, rig)).node();
        oldDrive.released(rig.level, rig, Ending.REVOKED); original.ended(rig.level, Ending.REVOKED);
        rig.hold.release(Ending.REVOKED); rig.hold = null;
        helper.assertTrue(rig.mill().isBoundTo(cow), "cancelling the resident's task never unbinds the native animal");
        var saved = rig.mill().saveWithFullMetadata(rig.level.registryAccess());
        var replacement = new MillstoneBlockEntity(rig.cell, rig.mill().getBlockState());
        replacement.loadWithComponents(saved, rig.level.registryAccess());
        rig.level.removeBlockEntity(rig.cell); rig.level.setBlockEntity(replacement);
        var station = new MillstoneStation();
        var goal = station.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).getFirst();
        var resumed = (MillstoneWork) goal.nodes().getFirst();
        var asking = new RecoveryAsking(helper); asking.offer(List.of(goal)); boolean[] watching = { true };
        helper.assertTrue(resumed.spec().needs().isEmpty() && resumed.planned(rig.level).isEmpty(), "reloading adopts the exact persisted accepted batch without budgeting another wheat");
        Node drive = ((Workload.Within.Turn.Piece) resumed.next(rig.level, rig)).node();
        helper.onEachTick(() -> { if (watching[0] && rig.level.getGameTime() % 40 == 0) asking.assertKept(goal, station.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {})); });
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.mill().getInput().isEmpty(), "the saved native animal motor resumes and finishes its actual accepted input"))
            .thenIdle(45).thenExecute(() -> {
                try {
                    helper.assertTrue(rig.mill().isBoundTo(cow) && drops(rig) == 0, "recovery retains both the original animal and its stored owned output");
                    helper.assertTrue(!((Workload.Continuous) drive.spec().workload()).holds(rig.level, rig) && Commit.run(rig.level, drive, rig) instanceof Outcome.Done, "the recovered animal-driven phase completes through the real workload");
                    resumed.ended(rig.level, drive, Ending.DONE);
                    helper.assertTrue(rig.step(resumed) instanceof Outcome.Done && resumed.commit(rig.level, rig) instanceof Outcome.Done, "the recovered native outputs are collected once");
                    helper.assertValueEqual(rig.count(ModItems.FLOUR.get()), 3L, "cancellation and block reload conserve the three accepted ingredients");
                    helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "recovery never creates a second raw input budget");
                    watching[0] = false; resumed.ended(rig.level, Ending.DONE); asking.finished(goal, Ending.DONE);
                } finally { cow.discard(); rig.close(); }
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void anAnimalLeavingAnAcceptedBatchLetsTheResidentResumeItsActualRemainingProgress(GameTestHelper helper) {
        var rig = new MillstoneGameTests.Rig(helper);
        Mob cow = cow(rig); rig.mill().bindEntity(cow);
        MillstoneWork work = rig.plan(3); rig.load(work);
        Node drive = ((Workload.Within.Turn.Piece) work.next(rig.level, rig)).node();
        helper.startSequence().thenIdle(20).thenExecute(() -> {
            int progress = rig.mill().saveWithoutMetadata(rig.level.registryAccess()).getInt("Progress");
            helper.assertTrue(progress > 0 && rig.mill().isBoundTo(cow), "the animal really advanced the unfinished native countdown");
            rig.mill().unbindEntity(); cow.setPos(Vec3.atBottomCenterOf(rig.cell.east(8)));
            helper.assertTrue(((Workload.Continuous) drive.spec().workload()).holds(rig.level, rig) && rig.mill().isBoundTo(rig.actor), "only after the animal leaves can the licensed resident become the native motor");
            helper.assertValueEqual(rig.mill().saveWithoutMetadata(rig.level.registryAccess()).getInt("Progress"), progress, "switching motors never resets the already accepted native countdown");
        }).thenWaitUntil(() -> helper.assertTrue(rig.mill().getInput().isEmpty(), "the replacement native motor finishes the original real input"))
            .thenWaitUntil(() -> helper.assertTrue(!((Workload.Continuous) drive.spec().workload()).holds(rig.level, rig), "the resident really walks out after its own motor work"))
            .thenExecute(() -> {
                try {
                    helper.assertTrue(Commit.run(rig.level, drive, rig) instanceof Outcome.Done, "actual exit completes the resident-driven remainder"); work.ended(rig.level, drive, Ending.DONE);
                    helper.assertTrue(rig.step(work) instanceof Outcome.Done, "the original batch's real flour is collected");
                    helper.assertValueEqual(rig.count(ModItems.FLOUR.get()), 3L, "changing motors keeps the exact output count");
                    helper.assertValueEqual(rig.count(Items.WHEAT), 0L, "changing motors neither reinserts nor refunds accepted input");
                } finally { cow.discard(); rig.close(); }
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aForeignAnimalBatchIsNeverAdoptedOrProtectedFromItsNativeOutputBehaviour(GameTestHelper helper) {
        var rig = new MillstoneGameTests.Rig(helper);
        MillstoneWork planned = rig.plan(3);
        Node prepared = ((Workload.Within.Turn.Piece) planned.next(rig.level, rig)).node();
        Mob cow = cow(rig); rig.mill().bindEntity(cow);
        helper.assertTrue(rig.mill().onPutItem(rig.level, new ItemStack(Items.WHEAT, 2)), "a player supplies an unrelated native animal batch");
        helper.assertTrue(Commit.run(rig.level, prepared, rig) instanceof Outcome.Failed, "a previously prepared colony load cannot overwrite foreign native ingredients");
        helper.assertTrue(new MillstoneStation().freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).isEmpty(), "the player batch is not advertised as an empty workshop");
        helper.assertTrue(new MillstoneStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).isEmpty(), "foreign ingredients never become a colony recovery");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.mill().getInput().isEmpty(), "the animal finishes the foreign batch normally"))
            .thenIdle(45).thenExecute(() -> {
                try {
                    helper.assertValueEqual(rig.count(Items.WHEAT), 3L, "the rejected colony load retains all its original wheat");
                    helper.assertValueEqual(rig.count(ModItems.FLOUR.get()), 0L, "the colony never takes the player's real flour");
                    helper.assertValueEqual(drops(rig), 2, "unowned native output still follows its normal automatic-ejection behaviour");
                    helper.assertTrue(rig.mill().isBoundTo(cow) && MillstoneBatch.read(rig.mill(), rig.level).isEmpty(), "the player's native animal and batch stay outside colony ownership");
                } finally { cow.discard(); rig.close(); }
            }).thenSucceed();
    }

    private static Mob cow(MillstoneGameTests.Rig rig) {
        Mob cow = EntityType.COW.create(rig.level);
        cow.setNoAi(true); cow.setPos(Vec3.atBottomCenterOf(rig.cell.south(2))); rig.level.addFreshEntity(cow);
        return cow;
    }
    private static int drops(MillstoneGameTests.Rig rig) {
        return rig.level.getEntitiesOfClass(ItemEntity.class, new AABB(rig.cell).inflate(6)).stream()
            .map(ItemEntity::getItem).filter(stack -> stack.is(ModItems.FLOUR.get())).mapToInt(ItemStack::getCount).sum();
    }
}