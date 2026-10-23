package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.TapBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.deco.StepladderBlock;
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
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@PrefixGameTestTemplate(false)
public final class TavernNativeGameTests {
    @GameTest(template = "empty_large", templateNamespace = "folkways")
    public static void counterStockPreservesComponentsAcrossSaveSyncStateChangesAndDropsOnce(GameTestHelper helper) {
        var level = helper.getLevel(); var pos = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(pos, ModBlocks.BAR_COUNTER.get().defaultBlockState());
        var counter = (TavernCounterBlockEntity)level.getBlockEntity(pos);
        helper.assertTrue(counter != null, "the native counter creates its own persistent stock entity");
        ItemStack first = new ItemStack(ModItems.WINE.get()); BottleBlockItem.setBrewLevel(first, 2);
        ItemStack second = new ItemStack(ModItems.WINE.get()); BottleBlockItem.setBrewLevel(second, 5);
        helper.assertTrue(Containers.insert(counter, first.copy()).isEmpty() && Containers.insert(counter, second.copy()).isEmpty(), "counter stock accepts two real bottles");
        helper.assertTrue(Containers.insert(counter, first.copy()).getCount() == 1 && Containers.insert(counter, new ItemStack(Items.APPLE)).getCount() == 1, "full counter and unrelated items leave stock unchanged");
        var saved = counter.saveWithoutMetadata(level.registryAccess());
        var sync = counter.getUpdateTag(level.registryAccess());
        helper.assertTrue(saved.getList("Items", 10).equals(sync.getList("Items", 10)) && counter.getUpdatePacket() != null, "client sync carries the same real bottle components as disk storage");
        level.removeBlockEntity(pos);
        var restored = new TavernCounterBlockEntity(pos, level.getBlockState(pos));
        restored.loadWithComponents(saved, level.registryAccess()); level.setBlockEntity(restored);
        helper.assertTrue(ItemStack.matches(restored.getItem(0), first) && ItemStack.matches(restored.getItem(1), second), "save/reload preserves each original quality-bearing bottle");
        level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, Direction.EAST));
        helper.assertTrue(level.getBlockEntity(pos) == restored && ItemStack.matches(restored.getItem(1), second), "counter state changes do not discard or drop inventory");
        level.removeBlock(pos, false);
        var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(pos).inflate(1));
        helper.assertTrue(drops.size() == 2 && drops.stream().anyMatch(one -> ItemStack.matches(one.getItem(), first)) && drops.stream().anyMatch(one -> ItemStack.matches(one.getItem(), second)), "breaking the counter drops both original bottles exactly once");
        helper.succeed();
    }

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

    @GameTest(template = "empty_large", templateNamespace = "folkways", timeoutTicks = 400)
    public static void aResidentClimbsTheTavernStepladderOntoTheBarrelLid(GameTestHelper helper) {
        climbsTheStepladder(helper, ladder -> ladder.south(2));
    }

    // Only the low side of a stepladder is stepped; its sides stand a block high, and one coming from there jumps on.
    @GameTest(template = "empty_large", templateNamespace = "folkways", timeoutTicks = 400)
    public static void aResidentComingFromTheSideStillClimbsTheTavernStepladder(GameTestHelper helper) {
        climbsTheStepladder(helper, ladder -> ladder.west(2));
    }

    private static void climbsTheStepladder(GameTestHelper helper, java.util.function.UnaryOperator<BlockPos> from) {
        Rig r = new Rig(helper, true);
        // The stepladder's high side leans on the barrel's back; the loader walks up it and steps on to the lid.
        BlockPos ladder = r.cell.south(2);
        var bottom = ModBlocks.STEPLADDER.get().defaultBlockState().setValue(StepladderBlock.FACING, Direction.SOUTH);
        r.level.setBlockAndUpdate(ladder, bottom.setValue(StepladderBlock.HALF, Half.BOTTOM));
        r.level.setBlockAndUpdate(ladder.above(), bottom.setValue(StepladderBlock.HALF, Half.TOP));
        helper.assertTrue(ClimbableBlocks.isClimbable(r.level.getBlockState(ladder)) && ClimbableBlocks.isClimbable(r.level.getBlockState(ladder.above())),
            "residents take both halves of the Tavern stepladder as a climb");
        var lid = TavernStation.lid(r.level, r.at).orElseThrow().cells();
        r.standAt(from.apply(ladder));
        BlockPos goal = r.cell.above(3);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(r.actor.onGround(), "the resident lands behind the stepladder"))
            .thenExecute(() -> {
                var path = r.actor.getNavigation().createPath(goal, 0);
                var trace = new StringBuilder();
                if (path != null) for (int i = 0; i < path.getNodeCount(); i++) trace.append(path.getNode(i).asBlockPos().subtract(r.cell).toShortString()).append("; ");
                helper.assertTrue(path != null && path.canReach() && r.actor.getNavigation().moveTo(path, 1.0),
                    "a path climbs the stepladder to the lid: " + trace);
            })
            .thenWaitUntil(() -> helper.assertTrue(r.actor.onGround()
                && lid.contains(WorldPos.of(r.level, r.actor.blockPosition())), "the resident physically stands on the barrel's top"))
            .thenExecute(r::close).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways", timeoutTicks = 3000)
    public static void savedAcceptedWineFermentsAndBottlesOnceAfterCancellation(GameTestHelper helper) {
        Rig r = new Rig(helper, true);
        TavernWork old = r.plan();
        helper.assertTrue(TavernStation.lid(r.level, r.at).filter(top -> !top.cells().contains(WorldPos.of(r.level, r.cell.north(3)))).isPresent(),
            "the barrel's lid is loaded from its top, not from the ground before the tap");
        helper.assertTrue(!(r.step(old) instanceof Outcome.Done), "juice is never poured in from the ground beside the barrel");
        r.standAt(r.cell.above(3));
        helper.assertTrue(r.step(old) instanceof Outcome.Done, "the native barrel accepts four real juice buckets from its top and closes its lid");
        r.standAt(r.cell.north(3));
        helper.assertTrue(TavernStation.spout(r.level, r.at).filter(tap -> tap.cells().equals(java.util.Set.of(WorldPos.of(r.level, r.cell.north(3)),
            WorldPos.of(r.level, r.cell.north(2).east()), WorldPos.of(r.level, r.cell.north(2).west())))).isPresent(),
            "bottling stands only in front of or beside the bottle under the tap");
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
                TavernWork active = work;
                for (int i = 0; i < 16; i++) {
                    helper.assertTrue(r.step(active) instanceof Outcome.Done, "place one physical empty bottle");
                    helper.assertTrue(r.level.getBlockState(r.cell.north(2)).getBlock().asItem() == ModItems.EMPTY_BOTTLE.get(), "empty bottle remains visible between actions");
                    helper.assertValueEqual(r.count(ModItems.EMPTY_BOTTLE.get()), 15L - i, "placement consumes exactly one carrier");
                    helper.assertValueEqual(r.barrel().getOutput().getStackInSlot(0).getCount(), 16 - i, "placement does not extract wine early");
                    if (i == 0) active = r.recover(active);
                    helper.assertTrue(r.step(active) instanceof Outcome.Done, "native tap fills the displayed bottle");
                    helper.assertTrue(r.level.getBlockState(r.cell.north(2)).getBlock().asItem() == ModItems.WINE.get(), "filled bottle remains visible until the separate pickup");
                    helper.assertValueEqual(r.count(ModItems.WINE.get()), (long)i, "wine is not added to the pack before pickup");
                    if (i == 0 || i == 15) active = r.recover(active);
                    var next = ((Workload.Within.Turn.Piece)active.next(r.level, r)).node();
                    helper.assertTrue(next.spec().workload() instanceof Workload.Once once && once.baseTicks() >= 20, "pickup gives the filled bottle at least a second of screen time");
                    helper.assertTrue(r.step(active) instanceof Outcome.Done, "resident takes the actual bottle");
                    helper.assertTrue(r.level.getBlockState(r.cell.north(2)).isAir(), "only pickup removes the displayed wine");
                }
                helper.assertTrue(active.commit(r.level, r) instanceof Outcome.Done, "resumed bottling completes");
                active.ended(r.level, Ending.DONE);
                helper.assertValueEqual(r.count(ModItems.WINE.get()), 16L, "sixteen actual wine bottles enter the pack");
                helper.assertValueEqual(r.count(ModItems.EMPTY_BOTTLE.get()), 0L, "sixteen empty bottles are truly consumed");
                for (int i = 0; i < r.pack.getContainerSize(); i++) if (r.pack.getItem(i).is(ModItems.WINE.get()))
                    helper.assertTrue(BottleBlockItem.getBrewLevel(r.pack.getItem(i)) >= 2, "native quality components survive physical bottling and transfer");
                helper.assertTrue(r.barrel().getOutput().getStackInSlot(0).isEmpty(), "no second output remains in the real barrel");
                r.close();
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

    @GameTest(template = "empty_large", templateNamespace = "folkways")
    public static void vineyardBuildsFromRealStockGrowsNativeFruitAndHarvestsWithoutDestroyingVines(GameTestHelper helper) {
        try (Rig r = new Rig(helper, false)) {
            var grape = new TavernGrapes();
            var plot = new java.util.LinkedHashSet<BlockPos>();
            for (int x = 1; x <= 4; x++) for (int z = 1; z <= 3; z++) {
                BlockPos ground = helper.absolutePos(new BlockPos(x, 1, z));
                r.level.setBlockAndUpdate(ground, Blocks.DIRT.defaultBlockState());
                plot.add(ground);
            }
            var corners = plot.stream().filter(ground -> grape.sowsAt(ground, 0, 0, plot)).toList();
            helper.assertValueEqual(corners.size(), 4, "a post stands at each corner of the plot");
            helper.assertFalse(grape.canPlant(r.level, corners.get(0).above()), "no vine is planted before its post stands");
            var first = grape.frameCells(r.level, plot);
            helper.assertTrue(first.size() == 4 && first.stream().allMatch(at -> corners.contains(at.below())), "the frame starts with one trellis at the foot of each post");
            ItemStack none = new ItemStack(ModBlocks.TRELLIS.get(), 0);
            helper.assertFalse(grape.lay(r.level, r, first.get(0), List.of(none)), "no trellis is laid without one supplied");
            int laid = 0;
            for (var ready = first; !ready.isEmpty(); ready = grape.frameCells(r.level, plot)) {
                for (BlockPos at : ready) {
                    ItemStack one = new ItemStack(ModBlocks.TRELLIS.get());
                    helper.assertTrue(grape.lay(r.level, r, at, List.of(one)) && one.isEmpty(), "each frame cell takes exactly one trellis");
                    laid++;
                }
                helper.assertTrue(laid <= 4 * 4 + plot.size(), "the frame goes up a cell at a time and ends");
            }
            helper.assertValueEqual(laid, 4 * 4 + plot.size(), "four posts and a roof over every cell of the plot");
            BlockPos middle = helper.absolutePos(new BlockPos(2, 2, 2));
            helper.assertTrue(r.level.getBlockState(middle.above(4)).is(ModBlocks.TRELLIS.get()) && r.level.getBlockState(middle).isAir(), "the roof is five blocks up and the ground under it is open");
            for (BlockPos corner : corners) {
                ItemStack vine = new ItemStack(ModItems.GRAPEVINE.get());
                helper.assertTrue(grape.plant(r.level, r, corner.above(), vine, List.of(vine)) && vine.isEmpty(), "a vine is planted at the foot of every post");
            }
            for (int tick = 0; tick < 40; tick++) {
                for (BlockPos cell : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(1, 2, 1)), helper.absolutePos(new BlockPos(4, 6, 3)))) {
                    var state = r.level.getBlockState(cell);
                    if (state.getBlock() instanceof com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapevineTrellisBlock vine) vine.doGrow(r.level, cell.immutable(), state);
                }
            }
            BlockPos fruit = middle.above(3);
            helper.assertTrue(r.level.getBlockState(fruit).is(ModBlocks.GRAPE_CROP.get()), "native growth climbs the posts, spreads over the roof and hangs bunches in the middle");
            r.level.setBlockAndUpdate(fruit, r.level.getBlockState(fruit).setValue(com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapeCropBlock.AGE, 5));
            var picked = grape.gather(r.level, r, grape.harvest(r.level, middle)).orElseThrow();
            helper.assertTrue(picked.worked() == 1 && picked.items().stream().anyMatch(stack -> stack.is(ModItems.GRAPE.get())), "resident harvest returns native grape loot");
            helper.assertTrue(r.level.getBlockState(fruit).isAir() && r.level.getBlockState(fruit.above()).is(ModBlocks.GRAPEVINE_TRELLIS.get()), "fruit is removed while living trellis remains");
            var trellis = (com.github.ysbbbbbb.kaleidoscopetavern.block.plant.GrapevineTrellisBlock)r.level.getBlockState(fruit.above()).getBlock();
            trellis.doGrow(r.level, fruit.above(), r.level.getBlockState(fruit.above()));
            helper.assertTrue(r.level.getBlockState(fruit).is(ModBlocks.GRAPE_CROP.get()), "same native trellis fruits again after harvest");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways")
    public static void fellingClaimsHangingWildGrapesBeforeTheirSupportingLeaves(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos trunk = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(trunk, Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(trunk.above(), Blocks.OAK_LOG.defaultBlockState());
        BlockPos leaf = trunk.above().east();
        level.setBlockAndUpdate(leaf, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 1));
        level.setBlockAndUpdate(leaf.below(), ModBlocks.WILD_GRAPEVINE.get().defaultBlockState());
        level.setBlockAndUpdate(leaf.below(2), ModBlocks.WILD_GRAPEVINE.get().defaultBlockState());
        var canopy = io.github.izakyl.folkways.plugins.farming.Felling.canopy(level, List.of(trunk, trunk.above()));
        helper.assertTrue(canopy.contains(leaf.below()) && canopy.contains(leaf.below(2)), "felling includes every attached wild vine");
        helper.assertTrue(canopy.indexOf(leaf.below(2)) < canopy.indexOf(leaf.below()) && canopy.indexOf(leaf.below()) < canopy.indexOf(leaf), "bottom-first recovery precedes support removal");
        int recovered = 0;
        for (BlockPos cell : canopy) {
            var state = level.getBlockState(cell);
            if (!io.github.izakyl.folkways.plugins.farming.Felling.attachment(state)) continue;
            recovered += net.minecraft.world.level.block.Block.getDrops(state, level, cell, null).stream().filter(stack -> stack.is(ModItems.GRAPEVINE.get())).mapToInt(ItemStack::getCount).sum();
            level.removeBlock(cell, false);
        }
        helper.assertTrue(recovered == 2, "both attached native vine drops are recovered exactly once");
        helper.succeed();
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
        void standAt(BlockPos feet) { actor.setPos(Vec3.atBottomCenterOf(feet)); }
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
        TavernWork recover(TavernWork work) {
            work.ended(level, Ending.REVOKED);
            var saved = barrel().saveWithoutMetadata(level.registryAccess());
            level.removeBlockEntity(cell);
            var restored = new BarrelBlockEntity(cell, level.getBlockState(cell));
            restored.loadWithComponents(saved, level.registryAccess()); level.setBlockEntity(restored);
            var recoveries = new TavernStation(true).recovery(colony, level, at, stances, bookings, () -> {});
            helper.assertValueEqual(recoveries.size(), 1, "partial bottling survives cancellation and barrel reload");
            var result = (TavernWork)recoveries.getFirst().nodes().getFirst();
            helper.assertTrue(result.planned(level).isEmpty(), "partial batch can be reclaimed");
            return result;
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
