package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.TapBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.PressingTubBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarCabinetBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopetavern.item.BottleBlockItem;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.*;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.cookery.*;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class TavernNativeGameTests {
    @GameTest(template = "empty_large", templateNamespace = "folkways")
    public static void cabinetInventoryIsItsActualRenderedBottlesWithOriginalQualityAndNativeSaveData(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(pos, ModBlocks.BAR_CABINET.get().defaultBlockState());
        var nativeCabinet = (BarCabinetBlockEntity)level.getBlockEntity(pos);
        var display = Containers.at(level, pos).orElseThrow();
        ItemStack left = new ItemStack(ModItems.WINE.get()); BottleBlockItem.setBrewLevel(left, 2);
        ItemStack right = new ItemStack(ModItems.WINE.get()); BottleBlockItem.setBrewLevel(right, 5);
        helper.assertTrue(Containers.insert(display, left.copy()).isEmpty() && Containers.insert(display, right.copy()).isEmpty(), "two real bottles enter the two native display positions");
        helper.assertTrue(ItemStack.matches(nativeCabinet.getLeftItem(), left) && ItemStack.matches(nativeCabinet.getRightItem(), right), "renderer reads the original complete component-bearing stacks");
        helper.assertValueEqual(Containers.insert(display, left.copy()).getCount(), 1, "a full cabinet refuses a third bottle without consuming it");
        helper.assertValueEqual(Containers.insert(display, new ItemStack(Items.APPLE)).getCount(), 1, "native cabinet slots reject unrelated food");
        var saved = nativeCabinet.saveWithoutMetadata(level.registryAccess()); level.removeBlockEntity(pos);
        var loaded = new BarCabinetBlockEntity(pos, level.getBlockState(pos)); loaded.loadWithComponents(saved, level.registryAccess()); level.setBlockEntity(loaded);
        var restored = Containers.at(level, pos).orElseThrow();
        ItemStack taken = restored.removeItem(0, 1);
        helper.assertTrue(ItemStack.matches(taken, left), "extracting after save/reload returns exactly the stored quality-two bottle");
        helper.assertTrue(loaded.getLeftItem().isEmpty() && ItemStack.matches(loaded.getRightItem(), right), "extraction visibly removes only its own actual native bottle");
        helper.assertTrue(Containers.insert(restored, taken).isEmpty(), "a cancelled transfer can return the same original bottle");
        helper.assertTrue(ItemStack.matches(loaded.getLeftItem(), left), "the returned bottle retains its original components");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways")
    public static void irregularCabinetBottlesKeepTheirNativeSinglePositionAndNotificationFailureCannotDuplicateStock(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(pos, ModBlocks.BAR_CABINET.get().defaultBlockState()); level.removeBlockEntity(pos);
        var cabinet = new BarCabinetBlockEntity(pos, level.getBlockState(pos)) {
            @Override public void refresh() { throw new IllegalStateException("fixture: native cabinet notification failed after mutation"); }
        };
        level.setBlockEntity(cabinet); var display = Containers.at(level, pos).orElseThrow();
        ItemStack brandy = new ItemStack(ModItems.BRANDY.get()); BottleBlockItem.setBrewLevel(brandy, 4);
        helper.assertTrue(Containers.insert(display, brandy.copy()).isEmpty(), "a notification failure cannot turn an accepted native bottle into a refund");
        helper.assertTrue(cabinet.isSingle() && ItemStack.matches(cabinet.getLeftItem(), brandy) && cabinet.getRightItem().isEmpty(), "the native irregular-bottle display is the single left position");
        helper.assertValueEqual(Containers.insert(display, new ItemStack(ModItems.WINE.get())).getCount(), 1, "an irregular bottle occupies the entire cabinet");
        ItemStack removed = display.removeItem(0, 1);
        helper.assertTrue(ItemStack.matches(removed, brandy) && display.isEmpty() && !cabinet.isSingle(), "removal settles once despite notification failure and frees both positions");
        helper.assertTrue(Containers.insert(display, new ItemStack(ModItems.WINE.get(), 3)).getCount() == 1, "a freed cabinet accepts exactly two ordinary bottles and leaves the third in stock");
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways", timeoutTicks = 700)
    public static void realResidentJumpsPressEightGrapesIntoOneRealJuiceBucket(GameTestHelper helper) {
        Rig r = new Rig(helper, false);
        TavernWork work = r.plan();
        helper.assertTrue(r.step(work) instanceof Outcome.Done, "the actual native tub accepts eight real grapes");
        helper.assertValueEqual(r.count(ModItems.GRAPE.get()), 0L, "accepted grapes are removed from the real pack once");
        var turn = work.next(r.level, r);
        Node press = ((Workload.Within.Turn.Piece)turn).node();
        var continuous = (Workload.Continuous)press.spec().workload();
        helper.onEachTick(() -> {
            if (r.tub().getFluidAmount() < 1000) continuous.holds(r.level, r);
        });
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertValueEqual(r.tub().getFluidAmount(), 1000, "normal physical jumps invoke the native tub fallOn eight times");
        }).thenExecute(() -> {
            helper.assertTrue(press.commit(r.level, r) instanceof Outcome.Done, "pressing really completed");
            helper.assertTrue(r.step(work) instanceof Outcome.Done, "a real empty bucket takes the native juice");
            helper.assertValueEqual(r.count(ModItems.GRAPE_BUCKET.get()), 1L, "one actual grape juice bucket is produced");
            helper.assertValueEqual(r.count(Items.BUCKET), 0L, "one real empty bucket is consumed");
            helper.assertTrue(TavernBatch.read(r.level, r.at, false).isEmpty(), "completed ownership is removed");
            r.close();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways", timeoutTicks = 3000)
    public static void savedAcceptedWineFermentsAndBottlesOnceAfterCancellation(GameTestHelper helper) {
        Rig r = new Rig(helper, true);
        TavernWork old = r.plan();
        helper.assertTrue(r.step(old) instanceof Outcome.Done, "the native barrel accepts four real juice buckets and closes its lid");
        helper.assertValueEqual(r.count(Items.BUCKET), 4L, "native filling gives back exactly four empty buckets");
        helper.assertValueEqual(r.count(ModItems.GRAPE_BUCKET.get()), 0L, "accepted juice buckets are consumed once");
        long started = r.level.getGameTime();
        var saved = r.barrel().saveWithoutMetadata(r.level.registryAccess());
        r.hold.release(Ending.REVOKED); old.ended(r.level, Ending.REVOKED); r.hold = null;
        r.level.removeBlockEntity(r.cell);
        var restored = new BarrelBlockEntity(r.cell, r.level.getBlockState(r.cell));
        restored.loadWithComponents(saved, r.level.registryAccess()); r.level.setBlockEntity(restored);
        var recoveries = new TavernStation(true).recovery(r.colony, r.level, r.at, r.stances, r.bookings, () -> {});
        helper.assertValueEqual(recoveries.size(), 1, "the owned native barrel has one saved recovery");
        TavernWork work = (TavernWork)recoveries.getFirst().nodes().getFirst();
        helper.assertTrue(work.planned(r.level).isEmpty(), "recovery books the native station");
        helper.assertValueEqual(work.spec().needs().size(), 1, "recovery needs bottles, never a second set of already accepted juice");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(r.barrel().getBrewLevel() >= 2, "the native fermentation clock reaches quality two"))
            .thenExecute(() -> {
                helper.assertTrue(r.level.getGameTime() - started >= 2400, "all 2400 native fermentation ticks elapsed");
                helper.assertTrue(r.step(work) instanceof Outcome.Done, "sixteen physical empty bottles receive the actual native wine through the tap");
                helper.assertValueEqual(r.count(ModItems.WINE.get()), 16L, "sixteen actual wine bottles enter the pack");
                helper.assertValueEqual(r.count(ModItems.EMPTY_BOTTLE.get()), 0L, "sixteen empty bottles are truly consumed");
                for (int i = 0; i < r.pack.getContainerSize(); i++) if (r.pack.getItem(i).is(ModItems.WINE.get()))
                    helper.assertTrue(BottleBlockItem.getBrewLevel(r.pack.getItem(i)) >= 2, "native quality components survive physical bottling and transfer");
                helper.assertTrue(r.barrel().getOutput().getStackInSlot(0).isEmpty(), "no second output remains in the real barrel");
                helper.assertTrue(work.commit(r.level, r) instanceof Outcome.Done, "the recovered production promise settles");
                work.ended(r.level, Ending.DONE); r.close();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways")
    public static void playerChangesAndWithdrawalNeverAdoptOrDuplicateNativeIngredients(GameTestHelper helper) {
        Rig r = new Rig(helper, false);
        TavernWork work = r.plan();
        r.bookings.withdraw(r.at);
        helper.assertTrue(work.refusal(r.level).isPresent(), "withdrawal rejects an already planned action");
        helper.assertValueEqual(r.count(ModItems.GRAPE.get()), 8L, "unaccepted grapes remain real stock");
        r.bookings.admit(r.at); r.hold.release(Ending.REVOKED);
        work = r.plan(); helper.assertTrue(r.step(work) instanceof Outcome.Done, "the new plan accepts the actual grapes");
        r.tub().getItems().setStackInSlot(0, new ItemStack(ModItems.GRAPE.get(), 7));
        helper.assertTrue(TavernBatch.read(r.level, r.at, false).isEmpty(), "changing accepted grapes invalidates mass-balanced ownership");
        r.hold.release(Ending.REVOKED); work.ended(r.level, Ending.REVOKED); r.hold = null;
        helper.assertTrue(new TavernStation(false).recovery(r.colony, r.level, r.at, r.stances, r.bookings, () -> {}).isEmpty(), "a changed player batch is never harvested or refunded");
        helper.assertValueEqual(r.tub().getItems().getStackInSlot(0).getCount(), 7, "player contents are left physically intact");
        r.close(); helper.succeed();
    }

    private static final class Rig implements Worker, AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        final boolean barrel;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(30);
        final ResidentEntity actor;
        final Stances stances;
        Hold hold;
        Rig(GameTestHelper helper, boolean barrel) {
            this.helper = helper; this.barrel = barrel; level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(5, 2, 5)); at = WorldPos.of(level, cell);
            for (BlockPos p : BlockPos.betweenClosed(cell.offset(-4, -1, -4), cell.offset(4, 4, 4))) level.setBlockAndUpdate(p, p.getY() == cell.getY() - 1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(cell, (barrel ? ModBlocks.BARREL : ModBlocks.PRESSING_TUB).get().defaultBlockState());
            if (barrel) {
                ModBlocks.BARREL.get().setPlacedBy(level, cell, level.getBlockState(cell), null, new ItemStack(ModItems.BARREL.get()));
                level.setBlockAndUpdate(cell.north(2).above(), ModBlocks.TAP.get().defaultBlockState().setValue(TapBlock.FACING, Direction.NORTH));
            }
            actor = PersonBody.RESIDENT.get().create(level);
            actor.setPos(Vec3.atBottomCenterOf(barrel ? cell.north(3) : cell).add(0, barrel ? 0 : 0.5, 0));
            // Physics and JumpControl must keep ticking in the pressing test.
            actor.setNoAi(false); actor.setInvulnerable(true); level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition());
            pack.setItem(0, new ItemStack(barrel ? ModItems.GRAPE_BUCKET.get() : ModItems.GRAPE.get(), barrel ? 4 : 8));
            pack.setItem(1, new ItemStack(barrel ? ModItems.EMPTY_BOTTLE.get() : Items.BUCKET, barrel ? 16 : 1));
        }
        PressingTubBlockEntity tub() { return (PressingTubBlockEntity)level.getBlockEntity(cell); }
        BarrelBlockEntity barrel() { return (BarrelBlockEntity)level.getBlockEntity(cell); }
        TavernWork plan() {
            var station = new TavernStation(barrel);
            var bench = station.freeze(UUID.randomUUID(), colony, level, at, stances, bookings, () -> {}).orElseThrow(() -> new IllegalStateException("Tavern freeze: empty=" + TavernStation.empty(level, at, barrel) + ", recipe=" + TavernStation.recipeValid(level, barrel) + ", actualRecipe=" + level.getRecipeManager().byKey(TavernStation.recipe(barrel)) + ", state=" + level.getBlockState(cell) + ", be=" + level.getBlockEntity(cell)));
            var target = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(TavernStation.output(barrel)), barrel ? 16 : 1, Produce.UNBOUNDED);
            var option = bench.options(target, Grown.of(target)).getFirst(); hold = option.holds().getFirst();
            helper.assertTrue(hold.take().isEmpty(), "production obtains a real native station booking");
            return (TavernWork)option.replacement().nodes().getFirst();
        }
        Outcome step(TavernWork work) {
            var turn = work.next(level, this);
            helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "expected a native interaction, got " + turn + "; refusal=" + work.refusal(level));
            Node piece = ((Workload.Within.Turn.Piece)turn).node();
            var outcome = Commit.run(level, piece, this);
            if (outcome instanceof Outcome.Done done) { done.made().forEach(s -> helper.assertTrue(Containers.insert(pack, s).isEmpty(), "real outputs fit")); work.ended(level, piece, Ending.DONE); }
            return outcome;
        }
        long count(Item item) { return Goods.countIn(pack, Goods.specOf(new ItemStack(item))); }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new IllegalStateException("unexpected spill " + stack); }
        public Effects effects() { return actor.effects(); }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() { if (hold != null) hold.release(Ending.DROPPED); actor.discard(); }
    }
}
