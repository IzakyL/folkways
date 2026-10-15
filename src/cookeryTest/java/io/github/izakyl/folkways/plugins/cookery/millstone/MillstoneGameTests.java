package io.github.izakyl.folkways.plugins.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
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
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
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

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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
    private static final class Rig implements Worker, AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        final Stances stances;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(12);
        final ResidentEntity actor;
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
        long count(net.minecraft.world.item.Item item) { return Goods.countIn(pack, Goods.specOf(new ItemStack(item))); }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new IllegalStateException("unexpected spill"); }
        public int rankOf(String perk) { return 0; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() {
            MillstoneDrive.stop(colony);
            if (hold != null) { hold.release(Ending.DROPPED); }
            actor.discard();
        }
    }
}
