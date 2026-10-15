package io.github.izakyl.folkways.plugins.cookery.teapot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.ITeapot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.TeapotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.TeapotRecipeSerializer;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.TeacupRegistry;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
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
import io.github.izakyl.folkways.plugins.cookery.testing.KitchenRecoveryWanted;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.cookery.tray.TrayStation;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TeapotGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(TeapotGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeFlowerTea(GameTestHelper helper) { brew(helper, "flower_tea"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeBarleyTea(GameTestHelper helper) { brew(helper, "barley_tea"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeTieguanyin(GameTestHelper helper) { brew(helper, "tieguanyin"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeBiluochun(GameTestHelper helper) { brew(helper, "biluochun"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeOolong(GameTestHelper helper) { brew(helper, "oolong"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeSakuraFubuki(GameTestHelper helper) { brew(helper, "sakura_fubuki"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeButterTea(GameTestHelper helper) { brew(helper, "butter_tea"); }

    private static void brew(GameTestHelper helper, String recipe) {
        Rig rig = new Rig(helper, recipe);
        TeapotWork work = rig.load(); long started = rig.level.getGameTime();
        var extras = work.planningLeaves(work.spec().gives());
        helper.assertTrue(extras.equals(List.of(new Amount(Goods.specOf(Items.BUCKET.getDefaultInstance()), 1))),
            "twelve promised tea portions are counted once while the actual emptied bucket retains its slot");
        helper.assertTrue(work.planningLeaves(List.of()).equals(Leaves.merged(work.spec().leaves())),
            "a teapot take with no consumer still reserves the full twelve-cup output");
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.level.getGameTime() - started >= 200 + ready.job.time(), "both native ingredient grace and brewing clocks really elapsed");
            ItemStack old = new ItemStack(Items.APPLE, 5); ready.actor.setItemInHand(InteractionHand.MAIN_HAND, old);
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "actual native tea is poured through twelve physical cup blocks");
            ready.assertServed();
            helper.assertTrue(ready.actor.getMainHandItem() == old, "the precise previous main hand is restored after pouring");
            helper.assertTrue(work.commit(ready.level, ready) instanceof Outcome.Done, "the original production promise settles after physical serving");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void removingRealHeatPausesTheNativeBrewingClock(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        rig.level.setBlockAndUpdate(rig.cell.below(), Blocks.STONE.defaultBlockState());
        int before = rig.pot().getCurrentTick();
        helper.runAfterDelay(60, () -> {
            helper.assertValueEqual(rig.pot().getCurrentTick(), before, "a physically cold teapot does not advance its native ingredient clock");
            helper.assertTrue(work.next(rig.level, rig) instanceof Workload.Within.Turn.Wait, "unfinished raw tea cannot be served");
            rig.level.setBlockAndUpdate(rig.cell.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
            helper.assertTrue(work.refusal(rig.level).isEmpty(), "native base/chain appearance updates do not invalidate the actual tea batch");
        });
        afterBrewing(rig, ready -> { helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the same real tea resumes on returning heat"); ready.assertServed(); });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void cancellationAfterFluidRecoversOnlyTheUnspentTeaBagAndCups(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork old = rig.plan();
        helper.assertTrue(rig.step(old) instanceof Outcome.Done, "native filling accepts one real bucket");
        helper.assertValueEqual(rig.count(Items.BUCKET), 1L, "native filling returns its actual empty bucket");
        TeapotWork work = rig.recover();
        helper.assertTrue(work.spec().needs().stream().noneMatch(need -> Goods.matches(rig.job.bucket(), need.spec())), "partial recovery never budgets a second fluid bucket");
        helper.assertTrue(rig.step(work) instanceof Outcome.Done, "the remaining real tea bag is inserted once");
        afterBrewing(rig, ready -> { helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the saved fluid and newly accepted bag brew normally"); ready.assertServed(); });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void blockEntityReloadKeepsTheAcceptedTeaWithoutBudgetingAnotherFluidOrBag(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); rig.load();
        var saved = rig.pot().saveWithoutMetadata(rig.level.registryAccess());
        rig.level.removeBlockEntity(rig.cell);
        TeapotBlockEntity loaded = new TeapotBlockEntity(rig.cell, rig.level.getBlockState(rig.cell));
        loaded.loadWithComponents(saved, rig.level.registryAccess()); rig.level.setBlockEntity(loaded);
        TeapotWork work = rig.recover();
        helper.assertValueEqual(work.spec().needs().size(), 1, "fully accepted recovery needs only twelve real empty cups");
        KitchenRecoveryWanted.assertKept(helper, work.recoveryGoal(), () -> new TeapotStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the saved native ticker reaches actual completion");
            KitchenRecoveryWanted.assertKept(helper, work.recoveryGoal(), () -> new TeapotStation().recovery(ready.colony, ready.level, ready.at, ready.stances, ready.bookings, () -> {}));
            ready.assertServed();
            work.ended(ready.level, Ending.DONE);
            helper.assertTrue(new TeapotStation().activeRecovery(ready.colony, ready.level, ready.at, ready.bookings).isEmpty(), "ended tea recovery is removed from wanted");
        });
    }

    @GameTest(template = "empty")
    public static void withdrawalRejectsAnAlreadyPlannedFluidInteraction(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "barley_tea")) {
            TeapotWork work = rig.plan();
            var turn = work.next(rig.level, rig); helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "the native filling action is planned");
            rig.bookings.withdraw(rig.at);
            helper.assertTrue(Commit.run(rig.level, ((Workload.Within.Turn.Piece) turn).node(), rig) instanceof Outcome.Failed, "the withdrawn generation refuses the old fill");
            helper.assertValueEqual(rig.count(rig.job.bucket().getItem()), 1L, "the unaccepted full bucket remains real stock");
            helper.assertValueEqual(rig.count(Items.BUCKET), 0L, "no empty bucket is manufactured by a rejected interaction");
            helper.assertTrue(TeapotStation.empty(rig.pot()), "the actual teapot stays completely empty");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void elevenCupsCannotServeOrLoseAnyOfTheTwelveNativePortions(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        rig.pack.getItem(1).shrink(1);
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Failed, "the core rejects a missing twelfth physical cup before native serving");
            helper.assertValueEqual(ready.count(ModItems.EMPTY_CUP.get()), 11L, "the eleven real cups are unchanged");
            helper.assertValueEqual(ready.pot().getResult().getCount(), 12, "all twelve actual portions remain in the finished teapot");
            helper.assertValueEqual(ready.count(ready.job.output().getItem()), 0L, "no tea is produced without its real cup");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void playerCupPositionsAreNeverOverwrittenToServeAnOwnedPot(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        for (Direction side : Direction.Plane.HORIZONTAL) { rig.level.setBlockAndUpdate(rig.cell.relative(side), ModBlocks.EMPTY_CUP.get().defaultBlockState()); }
        afterBrewing(rig, ready -> {
            helper.assertTrue(work.next(ready.level, ready) instanceof Workload.Within.Turn.Wait,
                "with all player cup positions occupied, the actual finished batch waits without issuing a destructive action");
            for (Direction side : Direction.Plane.HORIZONTAL) { helper.assertTrue(ready.level.getBlockState(ready.cell.relative(side)).is(ModBlocks.EMPTY_CUP.get()), "each player's real cup is preserved"); }
            helper.assertValueEqual(ready.count(ModItems.EMPTY_CUP.get()), 12L, "the task's own cups return after refusal");
            helper.assertValueEqual(ready.pot().getResult().getCount(), 12, "none of the native tea is taken");
            ready.level.setBlockAndUpdate(ready.cell.west(), Blocks.AIR.defaultBlockState());
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "freeing one actual cup position resumes the same native finished batch");
            for (Direction side : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                helper.assertTrue(ready.level.getBlockState(ready.cell.relative(side)).is(ModBlocks.EMPTY_CUP.get()), "remaining player cups are never overwritten");
            ready.assertServed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aThrowBeforeNativeTakeLeavesTheFinishedPotAndRefundsOnlyCups(GameTestHelper helper) { throwFromPot(helper, false); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aThrowAfterNativeTakeRestoresExactlyOneFinishedPhysicalPot(GameTestHelper helper) { throwFromPot(helper, true); }

    private static void throwFromPot(GameTestHelper helper, boolean after) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        afterBrewing(rig, ready -> {
            var saved = ready.pot().saveWithoutMetadata(ready.level.registryAccess());
            ready.level.removeBlockEntity(ready.cell);
            TeapotBlockEntity throwing = new TeapotBlockEntity(ready.cell, ready.level.getBlockState(ready.cell)) {
                @Override public boolean takeTeapot(Level level, LivingEntity user) {
                    if (after) { super.takeTeapot(level, user); }
                    throw new IllegalStateException("fixture: native pot take hook failed " + (after ? "after" : "before") + " physical removal");
                }
            };
            throwing.loadWithComponents(saved, ready.level.registryAccess()); ready.level.setBlockEntity(throwing);
            helper.assertTrue(ready.step(work) instanceof Outcome.Failed, "the failed native extraction rolls back");
            ready.assertRolledBack();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aThrowOnTheFirstPouredCupRollsBackWithoutReturningSpentTeaIngredients(GameTestHelper helper) { throwWhileServing(helper, 1); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aThrowOnTheLastPouredCupCannotLeaveElevenProductsAndRefundTwelveCups(GameTestHelper helper) { throwWhileServing(helper, 12); }

    private static void throwWhileServing(GameTestHelper helper, int failAt) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        afterBrewing(rig, ready -> {
            AtomicInteger actualPours = new AtomicInteger();
            Consumer<EntityJoinLevelEvent> hook = event -> {
                if (event.getLevel() == ready.level && event.getEntity() instanceof ItemEntity drop
                        && new AABB(ready.cell).inflate(3).contains(drop.position()) && drop.getItem().getItem() == ready.job.output().getItem()
                        && actualPours.incrementAndGet() == failAt) { throw new IllegalStateException("fixture: actual poured-cup delivery failed at " + failAt); }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, hook);
            try {
                helper.assertTrue(ready.step(work) instanceof Outcome.Failed, "partly completed physical serving is rolled back");
                helper.assertValueEqual(actualPours.get(), failAt, "the fixture failed at the requested real cup, not before pouring");
                ready.assertRolledBack();
            } finally { NeoForge.EVENT_BUS.unregister(hook); }
            TeapotWork recovered = ready.recover();
            helper.assertTrue(ready.step(recovered) instanceof Outcome.Done, "the same native finished batch can be served once after recovery");
            ready.assertServed();
            recovered.ended(ready.level, Ending.DONE);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2900)
    public static void freshLeavesBecomeARealCraftedTeaBagThenTwelveServedCups(GameTestHelper helper) {
        Rig rig = new Rig(helper, "tieguanyin");
        rig.pack.setItem(2, new ItemStack(ModItems.FRESH_TEA_LEAVES.get(), 8));
        rig.pack.setItem(3, new ItemStack(Items.IRON_NUGGET));
        BlockPos trayCell = rig.cell.east(2);
        rig.level.setBlockAndUpdate(trayCell.below(), Blocks.STONE.defaultBlockState());
        rig.level.setBlockAndUpdate(trayCell, ModBlocks.BAMBOO_TRAY.get().defaultBlockState());
        WorldPos trayAt = WorldPos.of(rig.level, trayCell);
        var bench = new TrayStation().freeze(UUID.randomUUID(), rig.colony, rig.level, trayAt, rig.stances, rig.bookings, () -> {}).orElseThrow();
        Produce dried = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(trayAt), Goods.specOf(new ItemStack(ModItems.DRIED_TEA_LEAVES.get())), 8, Produce.UNBOUNDED);
        var option = bench.options(dried, Grown.of(dried)).getFirst(); Hold trayHold = option.holds().getFirst();
        helper.assertTrue(trayHold.take().isEmpty(), "the native drying station is booked");
        Node trayWork = option.replacement().nodes().getFirst();
        helper.assertTrue(rig.step(trayWork) instanceof Outcome.Done, "all eight real fresh leaves are placed on the native tray");
        helper.startSequence().thenWaitUntil(() -> {
            var actual = (com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity) rig.level.getBlockEntity(trayCell);
            helper.assertTrue(actual.canTakeItemThroughFace(0, actual.getItem(0), Direction.DOWN), "the native drying completion flag is reached without injecting dried leaves");
        }).thenExecute(() -> {
            helper.assertTrue(rig.step(trayWork) instanceof Outcome.Done, "the actual dried leaves enter the real pack"); trayHold.release(Ending.DONE);
            List<ItemStack> grid = new ArrayList<>();
            for (int slot = 0; slot < 9; slot++) {
                Item item = slot == 4 ? Items.IRON_NUGGET : ModItems.DRIED_TEA_LEAVES.get();
                ItemStack used = Containers.extract(rig.pack, stack -> stack.is(item), 1);
                helper.assertTrue(!used.isEmpty(), "every real tea-bag crafting cell consumes existing basic-chain material"); grid.add(used);
            }
            CraftingInput input = CraftingInput.of(3, 3, grid);
            var recipe = rig.level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, rig.level).orElseThrow();
            helper.assertTrue(recipe.id().equals(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "tieguanyin_tea_bag")), "the real native shaped recipe matches the actual dried leaves and nugget");
            ItemStack bag = recipe.value().assemble(input, rig.level.registryAccess());
            helper.assertTrue(bag.is(ModItems.TIEGUANYIN_TEA_BAG.get()) && bag.getCount() == 1, "the tea bag comes from native assemble");
            helper.assertTrue(Containers.insert(rig.pack, bag).isEmpty(), "the actually crafted bag enters the pack");
            for (ItemStack remainder : recipe.value().getRemainingItems(input)) { if (!remainder.isEmpty()) { Containers.insert(rig.pack, remainder); } }
            rig.load();
        }).thenWaitUntil(() -> helper.assertTrue(rig.pot().getStatus() == ITeapot.FINISHED, "the real crafted bag completes native brewing"))
            .thenExecute(() -> {
                try {
                    TeapotWork work = rig.current;
                    helper.assertTrue(rig.step(work) instanceof Outcome.Done, "real cups serve tea made from the original fresh leaves"); rig.assertServed();
                    helper.assertValueEqual(rig.count(ModItems.FRESH_TEA_LEAVES.get()) + rig.count(ModItems.DRIED_TEA_LEAVES.get())
                        + rig.count(ModItems.TIEGUANYIN_TEA_BAG.get()) + rig.count(Items.IRON_NUGGET), 0L, "no intermediate or consumed base material is duplicated");
                } finally { rig.close(); }
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 3300)
    public static void nativeMysteryTeaWaitsTheDefaultClockAndServesExactlyFourActualCups(GameTestHelper helper) {
        Rig rig = new Rig(helper, "mystery_tea"); TeapotWork work = rig.load();
        long started = rig.level.getGameTime();
        helper.assertValueEqual(rig.job.time(), TeapotRecipeSerializer.DEFAULT_TIME, "mystery tea uses the unmodified native default brewing time");
        helper.assertValueEqual(rig.job.output().getCount(), 4, "unmatched tea promises four native portions rather than a recipe's twelve");
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.level.getGameTime() - started >= 200 + TeapotRecipeSerializer.DEFAULT_TIME,
                "both native ingredient grace and default brewing clocks actually elapsed");
            helper.assertValueEqual(ready.pot().getResult().getCount(), 4, "the actual native fallback produces four mystery portions");
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "four physical empty cups serve the native finished teapot");
            ready.assertServed();
            helper.assertTrue(work.commit(ready.level, ready) instanceof Outcome.Done, "the exact requested mystery tea target succeeds");
        });
    }

    @GameTest(template = "empty")
    public static void threeCupsCannotInvestWaterOrSeedIntoAnExplicitMysteryTeaBatch(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "mystery_tea")) {
            rig.pack.getItem(1).shrink(1); TeapotWork work = rig.plan();
            helper.assertTrue(rig.step(work) instanceof Outcome.Failed, "missing one actual fallback cup refuses before native fluid acceptance");
            helper.assertTrue(TeapotStation.empty(rig.pot()) && TeaBatch.read(rig.pot(), rig.level).isEmpty(), "the real teapot remains empty and unclaimed");
            helper.assertValueEqual(rig.count(Items.WATER_BUCKET), 1L, "the one real full water bucket stays available");
            helper.assertValueEqual(rig.count(Items.BUCKET), 0L, "no empty bucket is awarded for rejected filling");
            helper.assertValueEqual(rig.count(Items.WHEAT_SEEDS), 1L, "the real seed remains unspent");
            helper.assertValueEqual(rig.count(ModItems.EMPTY_CUP.get()), 3L, "the three actual cups remain unchanged");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aMatchingTeaReloadRejectsUnspentMysteryFluidWithoutInventingARecipe(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "mystery_tea")) {
            TeapotWork work = rig.plan();
            helper.assertTrue(rig.level.getRecipeManager().byKey(TeapotStation.MYSTERY).isEmpty(), "the native protocol is never registered as a fake recipe");
            withMatchingSeedRecipe(rig, () -> {
                helper.assertTrue(TeapotStation.mystery(rig.level).isEmpty(), "an actual new matching native recipe removes the no-match option");
                helper.assertTrue(rig.step(work) instanceof Outcome.Failed, "already planned fluid acceptance rechecks native recipe selection");
                helper.assertTrue(TeapotStation.empty(rig.pot()), "stale no-match planning does not invest real water");
                helper.assertValueEqual(rig.count(Items.WATER_BUCKET), 1L, "the original full bucket is returned exactly once");
                helper.assertValueEqual(rig.count(Items.WHEAT_SEEDS), 1L, "the original seed is still physical stock");
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aMatchingTeaReloadRejectsTheNextIngredientWithoutRefundingAcceptedWater(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "mystery_tea")) {
            TeapotWork work = rig.plan(); helper.assertTrue(rig.step(work) instanceof Outcome.Done, "one real bucket is accepted before reload");
            withMatchingSeedRecipe(rig, () -> {
                helper.assertTrue(rig.step(work) instanceof Outcome.Failed, "the subsequent native ingredient acceptance rechecks no-match");
                helper.assertTrue(rig.pot().getTeaFluidId().equals(rig.job.fluid()) && rig.pot().getInput().isEmpty(), "accepted native water remains but no stale seed is inserted");
                helper.assertValueEqual(rig.count(Items.WATER_BUCKET), 0L, "accepted water is not refunded on a later recipe change");
                helper.assertValueEqual(rig.count(Items.BUCKET), 1L, "only the original accepted empty bucket is returned");
                helper.assertValueEqual(rig.count(Items.WHEAT_SEEDS), 1L, "only the still-unspent seed remains available");
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 3300)
    public static void mysteryTeaReloadAndCancellationKeepFourCupRecoveryWithoutReinvestingTheBucketOrSeed(GameTestHelper helper) {
        Rig rig = new Rig(helper, "mystery_tea"); rig.load(); rig.reload();
        TeapotWork work = rig.recover();
        helper.assertTrue(TeaBatch.read(rig.pot(), rig.level).orElseThrow().mode() == TeapotStation.Mode.MYSTERY, "the actual saved batch preserves its native fallback mode");
        helper.assertValueEqual(work.spec().needs().size(), 1, "fully invested fallback recovery only budgets its real cups");
        helper.assertValueEqual(work.spec().needs().getFirst().count(), 4L, "persisted mystery recovery budgets four cups, not twelve");
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the actual reloaded native ticker finishes the same batch");
            ready.assertServed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 3300)
    public static void partiallyAcceptedMysteryTeaRecoversItsSeedAndFourCupsWithoutAnotherFluidBucket(GameTestHelper helper) {
        Rig rig = new Rig(helper, "mystery_tea"); TeapotWork original = rig.plan();
        helper.assertTrue(rig.step(original) instanceof Outcome.Done, "the native pot accepts just its actual water bucket");
        rig.reload(); TeapotWork recovered = rig.recover();
        helper.assertTrue(recovered.spec().needs().stream().noneMatch(need -> Goods.matches(rig.job.bucket(), need.spec())), "partial mystery recovery never budgets a second fluid bucket");
        helper.assertTrue(rig.step(recovered) instanceof Outcome.Done, "the still-physical original seed is accepted exactly once");
        afterBrewing(rig, ready -> { helper.assertTrue(ready.step(recovered) instanceof Outcome.Done, "the recovered native fallback is served"); ready.assertServed(); });
    }

    @GameTest(template = "empty", timeoutTicks = 3300)
    public static void aFailureAfterNativeMysteryPotRemovalRestoresFourPortionsAndRefundsOnlyFourCups(GameTestHelper helper) {
        Rig rig = new Rig(helper, "mystery_tea"); TeapotWork work = rig.load();
        afterBrewing(rig, ready -> {
            var saved = ready.pot().saveWithoutMetadata(ready.level.registryAccess());
            ready.level.removeBlockEntity(ready.cell);
            TeapotBlockEntity throwing = new TeapotBlockEntity(ready.cell, ready.level.getBlockState(ready.cell)) {
                @Override public boolean takeTeapot(Level level, LivingEntity user) {
                    super.takeTeapot(level, user); throw new IllegalStateException("fixture: after actual mystery pot removal");
                }
            };
            throwing.loadWithComponents(saved, ready.level.registryAccess()); ready.level.setBlockEntity(throwing);
            helper.assertTrue(ready.step(work) instanceof Outcome.Failed, "post-removal native failure rolls back the same real batch");
            ready.assertRolledBack(); ready.reload();
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "one later retry serves exactly the restored batch");
            ready.assertServed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 3300)
    public static void refreshFailuresAfterMysteryLoadingAndServingCannotRefundAcceptedItemsOrLoseFinishedTea(GameTestHelper helper) {
        Rig rig = new Rig(helper, "mystery_tea"); AtomicInteger refreshes = new AtomicInteger();
        rig.changed = () -> { refreshes.incrementAndGet(); throw new IllegalStateException("fixture: failed colony refresh after native settlement"); };
        TeapotWork work = rig.load();
        helper.assertValueEqual(refreshes.get(), 2, "both completed native loading actions exercise their failed refresh callback");
        helper.assertValueEqual(rig.count(Items.WATER_BUCKET) + rig.count(Items.WHEAT_SEEDS), 0L, "accepted native water and seed are never refunded by notification failures");
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "post-serving refresh failure cannot turn committed native output into a failed transaction");
            helper.assertValueEqual(refreshes.get(), 3, "serving also exercises the failed refresh callback");
            ready.assertServed();
            helper.assertTrue(work.commit(ready.level, ready) instanceof Outcome.Done, "the production promise remains correctly completed");
        });
    }

    @GameTest(template = "empty")
    public static void mysteryTeaIsOnlyAnExactRequestedDishAndNeverABroadDrinkFallback(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "mystery_tea")) {
            var bench = new TeapotStation().freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).orElseThrow();
            ItemSpec mystery = Goods.specOf(rig.job.output());
            ItemSpec barley = Goods.specOf(TeacupRegistry.getItem(TeacupRegistry.BARLEY_TEA).getDefaultInstance());
            Produce broad = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(rig.at), ItemSpec.anyOf(List.of(mystery, barley)), 4, Produce.UNBOUNDED);
            helper.assertTrue(bench.options(broad, Grown.of(broad)).stream().flatMap(change -> change.replacement().nodes().stream())
                .flatMap(node -> node.spec().gives().stream()).noneMatch(amount -> amount.spec().equals(mystery)), "a broad drink order cannot choose intentional unmatched tea");
            helper.assertTrue(rig.plan().spec().gives().stream().anyMatch(amount -> amount.spec().equals(mystery) && amount.count() == 4), "the exact requested drink has a real four-cup plan");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void thePublishedTeaStanceMustReachBothThePotAndItsOnlyActualCupPosition(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea");
        var offered = edgeGeometry(rig);
        TeapotWork work = rig.plan(offered);
        helper.assertTrue(!work.spec().stances().cells().contains(WorldPos.of(rig.level, rig.cell.offset(4, 0, 2))),
            "a real pot-reaching edge that cannot reach the only cup position is not published to the planner");
        helper.assertTrue(work.spec().stances().cells().contains(WorldPos.of(rig.level, rig.cell.west(2))),
            "the same real catalogue retains a stance that reaches both physical operations");
        rig.actor.setPos(Vec3.atBottomCenterOf(rig.cell.west(2)));
        helper.assertTrue(rig.step(work) instanceof Outcome.Done && rig.step(work) instanceof Outcome.Done,
            "the planned safe stance loads the actual bucket and ingredient");
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the same published stance serves twelve actual cups");
            ready.assertServed();
        });
    }

    @GameTest(template = "empty")
    public static void noServingFootingMeansNoNewTeaInvestment(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "barley_tea")) {
            edgeGeometry(rig);
            var onlyFarPotStance = Stances.at(rig.level, rig.cell.offset(4, 0, 2));
            helper.assertTrue(new TeapotStation().freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at,
                onlyFarPotStance, rig.bookings, () -> {}).isEmpty(),
                "the planner cannot invest a new batch whose actual published footing cannot serve any cup");
            helper.assertTrue(TeapotStation.empty(rig.pot()), "the actual pot remains empty");
            helper.assertValueEqual(rig.count(rig.job.bucket().getItem()), 1L, "the original fluid bucket stays physical and unspent");
            helper.assertValueEqual(rig.count(rig.job.input().getItem()), 1L, "the original tea ingredient stays unspent");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void changingThePlayersCupLayoutReplansTheFinishedTeaWithoutReinvestingIngredients(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea");
        edgeGeometry(rig);
        rig.level.setBlockAndUpdate(rig.cell.east().below(), Blocks.STONE.defaultBlockState());
        rig.level.setBlockAndUpdate(rig.cell.west(), ModBlocks.EMPTY_CUP.get().defaultBlockState());
        TeapotWork work = rig.plan(Stances.at(rig.level, rig.cell.offset(4, 0, 2)));
        helper.assertTrue(rig.step(work) instanceof Outcome.Done && rig.step(work) instanceof Outcome.Done,
            "the first actual cup layout permits the original pot-reaching stance");
        afterBrewing(rig, ready -> {
            ready.level.setBlockAndUpdate(ready.cell.east(), ModBlocks.EMPTY_CUP.get().defaultBlockState());
            ready.level.setBlockAndUpdate(ready.cell.west(), Blocks.AIR.defaultBlockState());
            var before = ready.pot().saveWithoutMetadata(ready.level.registryAccess());
            helper.assertTrue(work.next(ready.level, ready) instanceof Workload.Within.Turn.Leave,
                "a different available cup position releases the now unsuitable old stance for recovery");
            helper.assertTrue(work.commit(ready.level, ready) instanceof Outcome.Failed,
                "repositioning never pretends the unserved original tea was delivered");
            work.ended(ready.level, Ending.DROPPED);
            helper.assertTrue(before.equals(ready.pot().saveWithoutMetadata(ready.level.registryAccess())),
                "replanning preserves the exact real finished tea, fluid, input and ownership");
            Stances fresh = new TeapotStation().stances(ready.level, ready.at).orElseThrow();
            TeapotWork recovered = ready.recover(fresh);
            helper.assertTrue(!recovered.spec().stances().cells().contains(WorldPos.of(ready.level, ready.cell.offset(4, 0, 2))),
                "recovery publishes a newly usable stance, rather than repeating the same blocked edge");
            var feet = recovered.spec().stances().cells().iterator().next().cell();
            ready.actor.setPos(Vec3.atBottomCenterOf(feet));
            helper.assertTrue(ready.step(recovered) instanceof Outcome.Done, "the preserved twelve real portions are served from the new safe stance");
            helper.assertTrue(ready.level.getBlockState(ready.cell.east()).is(ModBlocks.EMPTY_CUP.get()), "the player's new east cup remains untouched");
            ready.assertServed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aCancelledTeaRecoveryCreatesAFreshRootAndALateOldEndCannotRemoveIt(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); rig.load();
        TeapotWork old = rig.recover();
        old.ended(rig.level, Ending.DROPPED);
        TeapotWork fresh = rig.recover();
        helper.assertTrue(fresh != old && fresh.id().equals(old.id()), "a canceled recovery retains native batch identity but has a fresh unfailed executable instance");
        old.ended(rig.level, Ending.DROPPED);
        KitchenRecoveryWanted.assertKept(helper, fresh.recoveryGoal(), () -> new TeapotStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
        afterBrewing(rig, ready -> { helper.assertTrue(ready.step(fresh) instanceof Outcome.Done, "the fresh original batch serves once after late old cleanup"); ready.assertServed(); });
    }

    private static Stances edgeGeometry(Rig rig) {
        BlockPos bad = rig.cell.offset(4, 0, 2), safe = rig.cell.west(2), cup = rig.cell.west();
        for (Direction side : Direction.Plane.HORIZONTAL) rig.level.setBlockAndUpdate(rig.cell.relative(side).below(), Blocks.AIR.defaultBlockState());
        for (BlockPos support : List.of(bad.below(), safe.below(), cup.below())) rig.level.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        rig.actor.setPos(Vec3.atBottomCenterOf(bad));
        rig.helper.assertTrue(Footing.withFeetAt(rig.level, bad) && Footing.withFeetAt(rig.level, safe), "both candidates are actual standable footings");
        rig.helper.assertTrue(Reach.withinReach(bad, rig.cell) && Reach.inSight(rig.level, bad, rig.cell), "the old edge truly reaches and sees the actual pot");
        rig.helper.assertTrue(!Reach.withinReach(bad, cup), "the only cup position is outside that old stance's real reach");
        rig.helper.assertTrue(Reach.withinReach(safe, rig.cell) && Reach.inSight(rig.level, safe, rig.cell)
            && Reach.withinReach(safe, cup) && Reach.inSight(rig.level, safe, cup), "the alternative reaches and sees both native operations");
        rig.helper.assertValueEqual(TeaCups.places(rig.level, rig.cell), List.of(cup), "real table support leaves only the west cup position available");
        return Stances.at(WorldPos.of(rig.level, bad), WorldPos.of(rig.level, safe));
    }

    private static void withMatchingSeedRecipe(Rig rig, Runnable check) {
        List<RecipeHolder<?>> saved = List.copyOf(rig.level.getRecipeManager().getRecipes());
        List<RecipeHolder<?>> changed = new ArrayList<>(saved);
        changed.add(new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("folkways", "test/matching_seed_tea"),
            new TeapotRecipe(ResourceLocation.withDefaultNamespace("water"), Ingredient.of(Items.WHEAT_SEEDS), 1, 100,
                TeacupRegistry.getItem(TeacupRegistry.BARLEY_TEA).getDefaultInstance())));
        try { rig.level.getRecipeManager().replaceRecipes(changed); check.run(); }
        finally { rig.level.getRecipeManager().replaceRecipes(saved); }
    }

    private static void afterBrewing(Rig rig, Consumer<Rig> check) {
        rig.helper.startSequence().thenWaitUntil(() -> rig.helper.assertTrue(rig.pot().getStatus() == ITeapot.FINISHED,
            "the actual native teapot finishes its unchanged ingredient and brewing clocks"))
            .thenExecute(() -> { try { check.accept(rig); } finally { rig.close(); } }).thenSucceed();
    }
    private static final class Rig implements Worker, AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        final Stances stances;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(20);
        final ResidentEntity actor;
        final TeapotStation.Job job;
        Hold hold;
        TeapotWork current;
        Runnable changed = () -> {};
        Rig(GameTestHelper helper, String name) {
            this.helper = helper; level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(2, 1, 2));
            job = TeapotStation.jobs(level).stream().filter(job -> job.recipe().equals(name.equals("mystery_tea") ? TeapotStation.MYSTERY : ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "teapot/" + name))).findFirst().orElseThrow();
            for (int x = -1; x <= 1; x++) { for (int z = -1; z <= 1; z++) { level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState()); } }
            level.setBlockAndUpdate(cell.below(), Blocks.MAGMA_BLOCK.defaultBlockState()); level.setBlockAndUpdate(cell, ModBlocks.TEAPOT.get().defaultBlockState());
            at = WorldPos.of(level, cell); actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.south())); actor.setNoAi(true); level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition()); pack.setItem(0, job.bucket()); pack.setItem(1, new ItemStack(ModItems.EMPTY_CUP.get(), job.output().getCount())); pack.setItem(2, job.input().copy());
        }
        TeapotBlockEntity pot() { return (TeapotBlockEntity) level.getBlockEntity(cell); }
        TeapotWork plan() { return plan(stances); }
        TeapotWork plan(Stances offered) {
            var bench = new TeapotStation().freeze(UUID.randomUUID(), colony, level, at, offered, bookings, () -> changed.run()).orElseThrow();
            Produce wanted = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(job.output()), job.output().getCount(), Produce.UNBOUNDED);
            var option = bench.options(wanted, Grown.of(wanted)).stream().filter(change -> change.replacement().nodes().getFirst().spec().needs().stream()
                .anyMatch(need -> Goods.matches(job.input(), need.spec()))).findFirst().orElseThrow();
            hold = option.holds().getFirst(); helper.assertTrue(hold.take().isEmpty(), "the native teapot station is booked");
            current = (TeapotWork) option.replacement().nodes().getFirst(); return current;
        }
        TeapotWork load() {
            TeapotWork work = plan(); helper.assertTrue(step(work) instanceof Outcome.Done, "one real fluid bucket is accepted by native addTeaFluid");
            helper.assertTrue(step(work) instanceof Outcome.Done, "one real ingredient is accepted by native addIngredient");
            helper.assertValueEqual(pot().getTeaFluidId(), job.fluid(), "the actual teapot contains the requested native fluid");
            helper.assertTrue(ItemStack.matches(pot().getInput(), job.input()), "the actual native ingredient is present");
            helper.assertTrue(new TeapotStation().recovery(colony, level, at, stances, bookings, () -> {}).isEmpty(), "a booked ordinary recipe descendant is not published as an autonomous recovery root");
            return work;
        }
        TeapotWork recover() { return recover(stances); }
        TeapotWork recover(Stances offered) {
            if (hold != null) { hold.release(Ending.REVOKED); hold = null; }
            var recovered = new TeapotStation().recovery(colony, level, at, offered, bookings, () -> changed.run());
            helper.assertValueEqual(recovered.size(), 1, "the actual owned batch has one recovery task");
            current = (TeapotWork) recovered.getFirst().nodes().getFirst(); helper.assertTrue(current.planned(level).isEmpty(), "the recovery acquires the current native station turn"); return current;
        }
        Outcome step(Node work) {
            var within = (Workload.Within) work.spec().workload(); var turn = within.next(level, this);
            if (!(turn instanceof Workload.Within.Turn.Piece)) {
                helper.fail("expected native appliance action, got " + turn + "; refusal=" + work.refusal(level) + "; pot=" + pot().getStatus()
                    + "; time=" + pot().getCurrentTick() + "; input=" + pot().getInput() + "; result=" + pot().getResult());
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            Node piece = ((Workload.Within.Turn.Piece) turn).node(); Outcome outcome = Commit.run(level, piece, this);
            if (outcome instanceof Outcome.Done done) { done.made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "actual native goods fit")); within.ended(level, piece, Ending.DONE); }
            return outcome;
        }
        void reload() {
            var saved = pot().saveWithoutMetadata(level.registryAccess()); level.removeBlockEntity(cell);
            TeapotBlockEntity restored = new TeapotBlockEntity(cell, level.getBlockState(cell));
            restored.loadWithComponents(saved, level.registryAccess()); level.setBlockEntity(restored);
        }
        void assertServed() {
            helper.assertValueEqual(count(job.output().getItem()), (long) job.output().getCount(), "exactly the native number of physical filled cups enter the real pack");
            helper.assertValueEqual(count(ModItems.EMPTY_CUP.get()), 0L, "all actual empty cups become their native served portions");
            helper.assertValueEqual(count(Items.BUCKET), 1L, "the one actual empty fluid bucket is preserved");
            helper.assertValueEqual(count(job.input().getItem()), 0L, "the real tea ingredient was consumed once");
            helper.assertValueEqual(count(ModItems.TEAPOT.get()), 0L, "no duplicate pot is added to the pack");
            helper.assertTrue(TeapotStation.empty(pot()) && TeaBatch.read(pot(), level).isEmpty(), "the one actual empty teapot is placed back at its station");
            helper.assertTrue(drops().isEmpty(), "no filled cups or pot also escape as ground duplicates");
        }
        void assertRolledBack() {
            helper.assertValueEqual(count(job.output().getItem()), 0L, "cancelled native cup drops are not also delivered");
            helper.assertValueEqual(count(ModItems.EMPTY_CUP.get()), (long) job.output().getCount(), "only this failed action's real physical cups are returned");
            helper.assertValueEqual(count(Items.BUCKET), 1L, "previously completed fluid loading remains consumed with its one empty bucket");
            helper.assertValueEqual(count(job.input().getItem()), 0L, "the already accepted tea bag is never refunded");
            helper.assertValueEqual(count(ModItems.TEAPOT.get()), 0L, "a captured temporary full pot does not become an extra inventory copy");
            helper.assertTrue(pot().getStatus() == ITeapot.FINISHED && pot().getResult().getCount() == job.output().getCount() && TeaBatch.read(pot(), level).isPresent(), "the original actual native finished batch and ownership are restored");
            helper.assertTrue(drops().isEmpty(), "failed serving leaves no uncaptured real product or duplicate pot");
        }
        long count(Item item) { return Goods.countIn(pack, Goods.specOf(new ItemStack(item))); }
        List<ItemEntity> drops() { return level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(3)); }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new IllegalStateException("unexpected spill: " + stack); }
        public int rankOf(String perk) { return 0; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() { if (hold != null) { hold.release(Ending.DROPPED); } if (current != null) { current.ended(level, Ending.DROPPED); } actor.discard(); drops().forEach(ItemEntity::discard); }
    }
}
