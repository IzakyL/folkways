package io.github.izakyl.folkways.cookery.stockpot;

import io.github.izakyl.folkways.effect.Effects;
import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.container.StockpotInput;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.soupbase.SoupBaseManager;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotVisuals;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModSoupBases;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.Quality;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.testing.KitchenRecoveryWanted;
import io.github.izakyl.folkways.cookery.CookeryRefusal;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.cookery.testing.NativeCookingMatrix;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.SimpleContainer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StockpotGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StockpotGameTests.class);
    }

    @GameTest(template = "empty")
    public static void stockpotBudgetKeepsItsRealBucketAndLidWithoutDuplicatingTheMeal(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            StockpotWork work = new StockpotWork(rig.colony, rig.at, rig.stances, rig.plan, 1, false, rig.bookings, () -> {});
            var extras = work.planningLeaves(List.of(new Amount(rig.plan.produces(), rig.plan.portions())));
            helper.assertValueEqual(extras.stream().filter(amount -> amount.spec().equals(rig.plan.produces())).mapToLong(Amount::count).sum(),
                0L, "served rice is promised once, with no phantom second result");
            helper.assertValueEqual(extras.stream().filter(amount -> amount.spec().equals(Goods.specOf(Items.BUCKET.getDefaultInstance())))
                .mapToLong(Amount::count).sum(), 1L, "the soup's emptied real bucket keeps its slot");
            helper.assertValueEqual(extras.stream().filter(amount -> amount.spec().equals(StockpotLoad.lid())).mapToLong(Amount::count).sum(),
                1L, "the actual reusable lid is still returned once");
            StockpotTake recovery = new StockpotTake(rig.colony, UUID.randomUUID(), rig.at, rig.stances, rig.plan,
                rig.bookings, () -> {}, true);
            helper.assertTrue(recovery.planningLeaves(List.of()).equals(Leaves.merged(recovery.spec().leaves())),
                "an autonomous recovered batch reserves all physical portions and its lid before it has any consumer");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void threeRiceInputsBecomeThreeSeparatelyServedBowls(GameTestHelper helper) {
        Rig rig = new Rig(helper, "rice_3", false);
        rig.stockRice(3, 3);
        ItemStack held = new ItemStack(Items.STICK);
        rig.body.presentHeldItem(held);
        rig.load();
        helper.assertValueEqual(rig.count(Items.BOWL), 3L, "one prepared set of bowls remains available");
        helper.assertValueEqual(rig.count(Items.BUCKET), 1L, "the real soup-base bucket is returned");
        helper.assertTrue(rig.pot.hasLid(), "a real lid starts cooking");
        afterCooking(rig, ready -> {
            helper.assertValueEqual(ready.pot.getTakeoutCount(), 3, "three separate servings are in the real pot");
            ready.take();
            helper.assertValueEqual(ready.count(ModItems.COOKED_RICE.get()), 3L, "all three servings are collected");
            helper.assertValueEqual(ready.count(Items.BOWL), 0L, "exactly three carriers were consumed");
            helper.assertValueEqual(ready.count(ModItems.STOCKPOT_LID.get()), 1L, "the resident's lid is returned");
            helper.assertTrue(ready.pot.getStatus() == IStockpot.PUT_SOUP_BASE && !StockpotBatch.claimed(ready.pot),
                "the next batch can use the empty, unclaimed pot");
            helper.assertTrue(ready.body.getMainHandItem() == held, "the resident's former main hand is restored");
            helper.assertTrue(ready.level.getEntitiesOfClass(ItemEntity.class,
                ready.body.getBoundingBox().inflate(2)).isEmpty(), "containers and meals do not leak onto the ground");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void oneLidAndOneSetOfBowlsCanCookTwoConsecutiveBatches(GameTestHelper helper) {
        Rig rig = new Rig(helper, "rice_3", false);
        rig.stockRice(3, 3);
        rig.load();
        helper.startSequence().thenWaitUntil(() -> rig.finished()).thenExecute(() -> {
            rig.take();
            // A real eat-and-return cycle is covered by the meal tests; this fixture returns those same bowls.
            for (int index = 0; index < rig.pack.getContainerSize(); index++) {
                if (rig.pack.getItem(index).is(ModItems.COOKED_RICE.get())) {
                    rig.pack.setItem(index, ItemStack.EMPTY);
                }
            }
            rig.put(new ItemStack(Items.BOWL, 3));
            rig.put(new ItemStack(Items.WATER_BUCKET));
            rig.put(new ItemStack(ModItems.RICE_SEED.get(), 3));
            rig.nextBatch();
            rig.load();
        }).thenWaitUntil(() -> rig.finished()).thenExecute(() -> {
            try {
                rig.take();
                helper.assertValueEqual(rig.count(ModItems.COOKED_RICE.get()), 3L, "the second batch is served");
                helper.assertValueEqual(rig.count(ModItems.STOCKPOT_LID.get()), 1L, "one lid serves both batches");
                helper.assertValueEqual(rig.count(Items.BUCKET), 2L, "both water buckets are returned");
            } finally {
                rig.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void missingCarriersOrIngredientsLeaveEverythingUnchanged(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 2);
            CompoundTag before = rig.pot.saveWithFullMetadata(rig.level.registryAccess());
            Outcome shortBowls = Commit.run(rig.level, rig.load, rig.worker);
            helper.assertTrue(shortBowls instanceof Outcome.Failed, "a short set of carriers cannot start cooking");
            helper.assertValueEqual(rig.count(ModItems.RICE_SEED.get()), 3L, "rice is refunded before any API operation");
            helper.assertValueEqual(rig.count(Items.WATER_BUCKET), 1L, "the soup base is not spent");
            helper.assertTrue(before.equals(rig.pot.saveWithFullMetadata(rig.level.registryAccess())),
                "missing carriers did not alter the pot");
            rig.put(new ItemStack(Items.BOWL));
            for (int index = 0; index < rig.pack.getContainerSize(); index++) {
                if (rig.pack.getItem(index).is(ModItems.RICE_SEED.get())) {
                    rig.pack.getItem(index).shrink(1);
                    break;
                }
            }
            helper.assertTrue(Commit.run(rig.level, rig.load, rig.worker) instanceof Outcome.Failed,
                "a missing ingredient also cannot start cooking");
            helper.assertTrue(before.equals(rig.pot.saveWithFullMetadata(rig.level.registryAccess())),
                "the pot stays untouched after either shortage");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void noHeatPreventsLoadingAndRefundsAllSupplies(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 3);
            rig.level.setBlockAndUpdate(rig.cell.below(), Blocks.STONE.defaultBlockState());
            helper.assertTrue(!rig.load.ready(rig.level), "the worker waits for a real heat source");
            helper.assertTrue(Commit.run(rig.level, rig.load, rig.worker) instanceof Outcome.Failed,
                "even a direct commit refuses a cold pot");
            helper.assertValueEqual(rig.count(ModItems.RICE_SEED.get()), 3L, "cold-pot rice stays in the pack");
            helper.assertValueEqual(rig.count(Items.BOWL), 3L, "cold-pot bowls stay in the pack");
            helper.assertTrue(rig.pot.getStatus() == IStockpot.PUT_SOUP_BASE && !rig.pot.hasLid(),
                "no heat cannot leave a half-loaded pot");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playerContentsAreNeitherReplacedNorAdopted(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 3);
            rig.pot.addSoupBase(rig.level, rig.body, new ItemStack(Items.MILK_BUCKET));
            rig.pot.addIngredient(rig.level, rig.body, new ItemStack(Items.CARROT));
            CompoundTag before = rig.pot.saveWithFullMetadata(rig.level.registryAccess());
            helper.assertTrue(Commit.run(rig.level, rig.load, rig.worker) instanceof Outcome.Failed,
                "a player's existing batch is protected");
            helper.assertTrue(before.equals(rig.pot.saveWithFullMetadata(rig.level.registryAccess())),
                "a rejected task leaves every player input in place");
            helper.assertTrue(StockpotWorkshops.recovery(rig.colony, rig.level, rig.at, rig.stances,
                rig.bookings, () -> { }).isEmpty(), "unclaimed player contents are not recovery work");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void anExistingNamedLidIsBorrowedAndReplaced(GameTestHelper helper) {
        Rig rig = new Rig(helper, "rice_3", true);
        ItemStack lid = ModItems.STOCKPOT_LID.get().getDefaultInstance();
        lid.set(DataComponents.CUSTOM_NAME, Component.literal("Family kitchen"));
        rig.pot.setLidItem(lid.copy());
        rig.stockRice(3, 3);
        rig.load();
        afterCooking(rig, ready -> {
            ready.take();
            helper.assertTrue(ready.pot.hasLid()
                && ItemStack.isSameItemSameComponents(lid, ready.pot.getLidItem()),
                "the player's lid and its components return to their original place");
            helper.assertValueEqual(ready.count(ModItems.STOCKPOT_LID.get()), 0L,
                "borrowing a lid does not create or consume another one");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void savedCookingBatchRecoversWithoutDrawingTheIngredientsAgain(GameTestHelper helper) {
        Rig rig = new Rig(helper, "rice_3", false);
        rig.stockRice(3, 3);
        rig.load();
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(rig.pot.getStatus() == IStockpot.COOKING, "the real block ticker has started cooking");
            rig.reload();
            rig.claim.release(Ending.DROPPED);
            rig.bookings.clear();
            List<Grown> recovery = StockpotWorkshops.recovery(rig.colony, rig.level, rig.at, rig.stances,
                rig.bookings, () -> { });
            helper.assertValueEqual(recovery.size(), 1, "the saved batch offers one recovery task");
            rig.take = recovery.getFirst().nodes().getFirst();
            helper.assertTrue(rig.take.id().equals(rig.batch), "recovery preserves the old batch identity");
            helper.assertTrue(rig.take.planned(rig.level).isEmpty(), "recovery acquires the shared kitchen booking");
            KitchenRecoveryWanted.assertKept(helper, recovery.getFirst(), () -> StockpotWorkshops.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
        });
        afterCooking(rig, ready -> {
            ready.take();
            helper.assertValueEqual(ready.count(ModItems.COOKED_RICE.get()), 3L, "the saved batch is served once");
            helper.assertValueEqual(ready.count(ModItems.RICE_SEED.get()), 0L, "ingredients were spent only once");
            KitchenRecoveryWanted.assertKept(helper, ((StockpotTake)ready.take).recoveryGoal(), () -> StockpotWorkshops.recovery(ready.colony, ready.level, ready.at, ready.stances, ready.bookings, () -> {}));
            ready.take.ended(ready.level, Ending.DONE);
            helper.assertTrue(StockpotWorkshops.activeRecovery(ready.colony, ready.level, ready.at, ready.bookings).isEmpty(), "ended stockpot recovery is removed from wanted");
            helper.assertTrue(ready.bookings.free(ready.at), "finished recovery releases its booking");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void finishedBatchRecoversForAnotherResident(GameTestHelper helper) {
        Rig rig = new Rig(helper, "rice_3", false);
        rig.stockRice(3, 3);
        rig.load();
        afterCooking(rig, ready -> {
            ready.reload();
            ready.claim.release(Ending.DROPPED);
            ready.body.discard();
            ready.body = ready.newBody();
            ready.worker = ready.worker();
            ready.take = StockpotWorkshops.recovery(ready.colony, ready.level, ready.at, ready.stances,
                ready.bookings, () -> { }).getFirst().nodes().getFirst();
            helper.assertTrue(ready.take.planned(ready.level).isEmpty(), "a different resident can recover the pot");
            ready.take();
            helper.assertValueEqual(ready.count(ModItems.COOKED_RICE.get()), 3L, "a finished saved batch is not lost");
            ready.take.ended(ready.level, Ending.DONE);
        });
    }

    @GameTest(template = "empty")
    public static void aFailedSecondIngredientRollsBackSoupLidClaimAndGoods(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 3);
            rig.replace(new StockpotBlockEntity(rig.cell, rig.pot.getBlockState()) {
                private int added;
                @Override
                public boolean addIngredient(Level level, LivingEntity user, ItemStack item) {
                    return ++added != 2 && super.addIngredient(level, user, item);
                }
            });
            CompoundTag before = rig.pot.saveWithFullMetadata(rig.level.registryAccess());
            helper.assertTrue(Commit.run(rig.level, rig.load, rig.worker) instanceof Outcome.Failed,
                "a real API rejection during loading fails the transaction");
            helper.assertTrue(before.equals(rig.pot.saveWithFullMetadata(rig.level.registryAccess())),
                "all block state, inputs and the new claim were rolled back");
            helper.assertValueEqual(rig.count(ModItems.RICE_SEED.get()), 3L, "all rice is refunded once");
            helper.assertValueEqual(rig.count(Items.WATER_BUCKET), 1L, "the full soup bucket is refunded once");
            helper.assertValueEqual(rig.count(Items.BUCKET), 0L, "the rolled-back empty bucket is discarded");
            helper.assertValueEqual(rig.count(Items.BOWL), 3L, "carriers are refunded once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void aFailedSecondServingRestoresAllPortionsAndCarriers(GameTestHelper helper) {
        Rig rig = new Rig(helper, "rice_3", false);
        rig.stockRice(3, 3);
        rig.load();
        afterCooking(rig, ready -> {
            ready.replace(new StockpotBlockEntity(ready.cell, ready.pot.getBlockState()) {
                private int taken;
                @Override
                public boolean takeOutProduct(Level level, LivingEntity user, ItemStack carrier) {
                    return ++taken != 2 && super.takeOutProduct(level, user, carrier);
                }
            });
            helper.assertTrue(Commit.run(ready.level, ready.take, ready.worker) instanceof Outcome.Failed,
                "a second-serving failure fails the whole take transaction");
            helper.assertTrue(ready.pot.hasLid() && ready.pot.getStatus() == IStockpot.FINISHED,
                "the lid and finished state are restored");
            helper.assertValueEqual(ready.pot.getTakeoutCount(), 3, "all original servings remain in the pot");
            helper.assertValueEqual(ready.count(Items.BOWL), 3L, "each supplied carrier is restored once");
            helper.assertValueEqual(ready.count(ModItems.COOKED_RICE.get()), 0L, "no partially taken meal is delivered");
            ready.replace(new StockpotBlockEntity(ready.cell, ready.pot.getBlockState()));
            ready.take();
            helper.assertValueEqual(ready.count(ModItems.COOKED_RICE.get()), 3L, "a later retry gets exactly three meals");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void recipesWithoutCarriersUseTheSameRealCookingFlow(GameTestHelper helper) {
        Rig rig = new Rig(helper, "zongzi_count_3", false);
        rig.put(new ItemStack(Items.WATER_BUCKET));
        rig.put(new ItemStack(ModItems.STOCKPOT_LID.get()));
        rig.put(new ItemStack(ModItems.RAW_ZONGZI.get(), 3));
        rig.load();
        afterCooking(rig, ready -> {
            ready.take();
            helper.assertValueEqual(ready.count(ModItems.ZONGZI.get()), 3L, "carrier-free dishes are served individually");
            helper.assertTrue(ready.plan.carrier().isEmpty(), "this is the real empty-carrier recipe");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void aFishBucketSoupBaseReturnsItsBucketAndCooksItsFixedRecipe(GameTestHelper helper) {
        Rig rig = new Rig(helper, "seafood_miso_soup_cod_entity", false);
        rig.put(new ItemStack(Items.COD_BUCKET));
        rig.put(new ItemStack(ModItems.STOCKPOT_LID.get()));
        rig.put(new ItemStack(ModItems.LETTUCE.get(), 3));
        rig.put(new ItemStack(Items.BOWL));
        rig.load();
        helper.assertValueEqual(rig.count(Items.BUCKET), 1L, "the fish-bucket soup base returns a real empty bucket");
        afterCooking(rig, ready -> {
            ready.take();
            helper.assertValueEqual(ready.count(ModItems.SEAFOOD_MISO_SOUP.get()), 1L,
                "a non-water soup base is resolved by its registered soup-base predicate");
        });
    }

    @GameTest(template = "empty")
    public static void changedPlayerInputsReleaseOnlyOurClaim(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 3);
            rig.load();
            rig.claim.release(Ending.DROPPED);
            rig.pot.getInputs().set(0, new ItemStack(Items.CARROT));
            CompoundTag before = rig.pot.saveCustomOnly(rig.level.registryAccess());
            helper.assertTrue(StockpotWorkshops.recovery(rig.colony, rig.level, rig.at, rig.stances,
                rig.bookings, () -> { }).isEmpty(), "changed inputs are not silently cooked as the old order");
            CompoundTag after = rig.pot.saveCustomOnly(rig.level.registryAccess());
            before.getCompound("NeoForgeData").remove(StockpotBatch.KEY);
            helper.assertTrue(before.equals(after), "only our ownership metadata is relinquished");
            rig.claim = rig.bookings.hold(rig.at, rig.batch);
            helper.assertTrue(rig.claim.take().isEmpty(), "the old graph still owns its kitchen turn");
            helper.assertTrue(rig.take.ready(rig.level), "a lost claim must not leave the old take waiting forever");
            helper.assertTrue(Commit.run(rig.level, rig.take, rig.worker) instanceof Outcome.Failed,
                "the stale take fails without touching player ingredients");
            rig.claim.release(new Ending.Failed(rig.take.id(),
                CookeryRefusal.STATION_CHANGED));
            helper.assertTrue(rig.bookings.free(rig.at), "ending the old graph frees the kitchen");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSecondColonyCannotRecoverTheFirstColonysPot(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 3);
            rig.load();
            rig.claim.release(Ending.DROPPED);
            helper.assertTrue(StockpotWorkshops.recovery(UUID.randomUUID(), rig.level, rig.at, rig.stances,
                rig.bookings, () -> { }).isEmpty(), "another colony cannot adopt the ingredients");
            helper.assertTrue(StockpotBatch.ours(rig.pot, rig.colony), "the real owner retains its claim");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void overlappingIngredientPredicatesAreMatchedWithoutSpendingGoods(GameTestHelper helper) {
        List<ItemStack> supplied = List.of(new ItemStack(Items.CARROT), new ItemStack(Items.POTATO));
        var picked = StockpotSupplies.draw(supplied, List.of(
            stack -> stack.is(Items.CARROT) || stack.is(Items.POTATO), stack -> stack.is(Items.CARROT))).orElseThrow();
        helper.assertTrue(picked.slots().getFirst().is(Items.POTATO) && picked.slots().get(1).is(Items.CARROT),
            "a broad slot leaves the narrow slot's only ingredient available");
        helper.assertValueEqual(supplied.getFirst().getCount(), 1, "matching does not mutate supplied snapshots");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aResidentMovedAwayCannotLoadThePotRemotely(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 3);
            rig.body.moveTo(rig.cell.getX() + 8.5, rig.cell.getY(), rig.cell.getZ() + 0.5, 0, 0);
            helper.assertTrue(Commit.run(rig.level, rig.load, rig.worker) instanceof Outcome.Failed,
                "the worker must still stand at a reachable kitchen stance when committing");
            helper.assertValueEqual(rig.count(ModItems.RICE_SEED.get()), 3L, "moving away cannot spend ingredients");
            helper.assertTrue(rig.pot.getStatus() == IStockpot.PUT_SOUP_BASE,
                "a stale route cannot operate a remote pot");
        }
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    @GameTest(template = "empty")
    public static void refusedRecipesAreNeitherPlannedNorCommitted(GameTestHelper helper) throws Exception {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 3);
            var field = FolkwaysVanillaConfig.class.getDeclaredField("REFUSED_RECIPES");
            field.setAccessible(true);
            var setting = (ModConfigSpec.ConfigValue<List<? extends String>>)
                field.get(null);
            List<? extends String> before = setting.get();
            try {
                List<String> refused = new ArrayList<>(before);
                refused.add(rig.plan.id().toString());
                setting.set(List.copyOf(refused));
                var holder = StockpotPlan.find(rig.level, rig.plan.id()).orElseThrow();
                helper.assertTrue(StockpotPlan.of(rig.level, holder).isEmpty(),
                    "a server's refused recipe is excluded when freezing plans");
                helper.assertTrue(Commit.run(rig.level, rig.load, rig.worker) instanceof Outcome.Failed,
                    "a recipe refused after planning is also rejected at commit");
                helper.assertValueEqual(rig.count(ModItems.RICE_SEED.get()), 3L,
                    "refusing an old plan does not consume goods");
            } finally {
                setting.set(before);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void planningAThreeServingBatchNeedsOnlyThreeBowls(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.claim.release(Ending.DROPPED);
            var fixture = new PlanningFixture(helper, 36);
            var source = new Stash(fixture.source);
            List<Stock.Holding> holdings = List.of(
                new Stock.Holding(source,
                    new ItemStack(ModItems.RICE_SEED.get(), 3)),
                new Stock.Holding(source, new ItemStack(Items.WATER_BUCKET)),
                new Stock.Holding(source, new ItemStack(Items.BOWL, 3)),
                new Stock.Holding(source,
                    new ItemStack(ModItems.STOCKPOT_LID.get())));
            var stock = new Stock(holdings, Map.of(source, 27));
            var workshop = StockpotWorkshops.freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at,
                rig.stances, rig.bookings, () -> { }).orElseThrow();
            var crafting = rig.body.licences().of(WaresContent.trade())
                .orElseThrow();
            var hauling = rig.body.licences().of(Vocations.required(
                Vocations.HAULING)).orElseThrow();
            var hand = new Crew.Hand(rig.body.resident(), fixture.source,
                36, 36, 1, List.of(crafting, hauling), List.of(), Optional.empty());
            var known = new Known(
                fixture.situation(stock, List.of(workshop)), List.of(hand));
            var spec = NodeSpec.of(UUID.randomUUID(),
                CookeryContent.ID,
                new WorkSite.AtBlock(fixture.target),
                Stances.at(fixture.target), Workload.Once.of(1))
                .needs(new Need(
                    Goods.specOf(ModItems.COOKED_RICE.get().getDefaultInstance()), 3)).done();
            Node consumer = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            };
            var solver = new Solving();
            try {
                var planned = solver.handle(known, List.of(new Message.Submitted(
                    CookeryContent.ID, Grown.of(consumer))), 0);
                helper.assertTrue(planned.diagnosis().shortfalls().isEmpty(),
                    "exactly three bowls feed both preparation and serving: " + planned.diagnosis());
                helper.assertTrue(planned.weave().vertices().values().stream()
                    .anyMatch(vertex -> vertex.node() instanceof StockpotWork),
                    "the real stockpot workshop supplied executable cooking work");
                helper.assertTrue(planned.schedule().unassigned().isEmpty(),
                    "the whole batch can be scheduled for the available resident");
            } finally {
                solver.closed();
                fixture.worker.body().discard();
            }
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void releasingAKitchenPreservesEveryInvestedItemAndOtherOwners(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 3);
            rig.load();
            CompoundTag before = rig.pot.saveCustomOnly(rig.level.registryAccess());
            StockpotWorkshops.release(UUID.randomUUID(), rig.level, rig.at);
            helper.assertTrue(before.equals(rig.pot.saveCustomOnly(rig.level.registryAccess())),
                "unmarking another colony cannot change this kitchen");
            StockpotWorkshops.release(rig.colony, rig.level, rig.at);
            before.getCompound("NeoForgeData").remove(StockpotBatch.KEY);
            helper.assertTrue(before.equals(rig.pot.saveCustomOnly(rig.level.registryAccess())),
                "unmarking removes ownership alone, preserving soup, ingredients and the exact lid");
        }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 450)
    public static void threeSingleServingBatchesUseOneLidAndExactlyThreeBowls(GameTestHelper helper) throws Exception {
        Rig rig = new Rig(helper, "rice_1", false);
        rig.stockRice(3, 3);
        rig.put(new ItemStack(Items.WATER_BUCKET));
        rig.put(new ItemStack(Items.WATER_BUCKET));
        var field = FolkwaysVanillaConfig.class.getDeclaredField("REFUSED_RECIPES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var setting = (ModConfigSpec.ConfigValue<List<? extends String>>) field.get(null);
        List<? extends String> before = setting.get();
        Workshop workshop;
        try {
            List<String> refused = new ArrayList<>(before);
            for (int portions = 2; portions <= 9; portions++) {
                refused.add("kaleidoscope_cookery:stockpot/rice_" + portions);
            }
            setting.set(List.copyOf(refused));
            workshop = StockpotWorkshops.freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at,
                rig.stances, rig.bookings, () -> { }).orElseThrow();
        } finally {
            setting.set(before);
        }
        var ask = Produce.of(CookeryContent.ID,
            new WorkSite.AtBlock(rig.at), rig.plan.produces(), 3,
            Produce.UNBOUNDED);
        var offer = workshop.options(ask, Grown.of(ask)).getFirst();
        StockpotWork work = (StockpotWork) offer.replacement().nodes().getFirst();
        rig.claim.release(Ending.DROPPED);
        rig.claim = offer.holds().getFirst();
        helper.assertTrue(rig.claim.take().isEmpty(), "one booking covers all three single-serving recipe batches");
        helper.startSequence().thenWaitUntil(() -> rig.drive(work)).thenExecute(() -> {
            try {
                helper.assertValueEqual(rig.count(ModItems.COOKED_RICE.get()), 3L, "the whole production offer is delivered");
                helper.assertValueEqual(rig.count(Items.BOWL), 0L, "three servings consume exactly three bowls");
                helper.assertValueEqual(rig.count(Items.BUCKET), 3L, "each real soup bucket is returned");
                helper.assertValueEqual(rig.count(ModItems.STOCKPOT_LID.get()), 1L, "the same lid serves every batch");
            } finally {
                rig.close();
            }
        }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void aRotatedMovingFrameUsesStorageFeetForTheRealStockpotInteraction(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_3", false)) {
            rig.stockRice(3, 3);
            UUID frameId = UUID.randomUUID();
            Vec3[] origin = {Vec3.atLowerCornerOf(rig.cell.offset(40, 8, 12))};
            WorldSpaces.Frame frame = new WorldSpaces.Frame() {
                public UUID id() { return frameId; }
                public Level level() { return rig.level; }
                public BlockPos storageOrigin() { return rig.cell; }
                public Vec3 toWorld(Vec3 local) { return origin[0].add(-local.z, local.y, local.x); }
                public Vec3 toLocal(Vec3 world) {
                    Vec3 delta = world.subtract(origin[0]);
                    return new Vec3(delta.z, delta.y, -delta.x);
                }
            };
            Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
                public Optional<WorldSpaces.Frame> frame(Level host, UUID key) {
                    return host == rig.level && key.equals(frameId) ? Optional.of(frame) : Optional.empty();
                }
                public Optional<WorldSpaces.Frame> containing(Level host, BlockPos cell) { return Optional.empty(); }
                public Optional<WorldSpaces.Frame> aboard(Entity entity) { return Optional.empty(); }
            });
            WorldPos at = new WorldPos(new Realm.Frame(frameId), BlockPos.ZERO);
            WorldPos footing = new WorldPos(at.realm(), new BlockPos(1, 0, 0));
            rig.claim.release(Ending.DROPPED);
            Hold turn = rig.bookings.hold(at, rig.batch);
            helper.assertTrue(turn.take().isEmpty(), "the frame kitchen has its own booking");
            try {
                rig.body.setPos(WorldSpaces.world(rig.level, footing).orElseThrow());
                Node loading = new StockpotLoad(rig.colony, rig.batch, at, Stances.at(footing), rig.plan, false,
                    rig.bookings, () -> { });
                Outcome outcome = Commit.run(rig.level, loading, rig.worker);
                helper.assertTrue(outcome instanceof Outcome.Done,
                    "a rotated world-space resident operates the plot-space pot: " + outcome);
                ((Outcome.Done) outcome).made().forEach(rig::put);
                CompoundTag before = rig.pot.saveWithFullMetadata(rig.level.registryAccess());
                origin[0] = origin[0].add(5, 0, 3);
                Node taking = new StockpotTake(rig.colony, rig.batch, at, Stances.at(footing), rig.plan,
                    rig.bookings, () -> { }, false);
                helper.assertTrue(Commit.run(rig.level, taking, rig.worker) instanceof Outcome.Failed,
                    "a frame moving away invalidates the old stance before any interaction");
                helper.assertTrue(before.equals(rig.pot.saveWithFullMetadata(rig.level.registryAccess())),
                    "the missed frame interaction leaves every real ingredient and lid intact");
            } finally {
                turn.release(Ending.DROPPED);
                remove.run();
            }
        }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 250)
    public static void recoveryReservesRoomForBothTheMealAndReturnedLid(GameTestHelper helper) {
        Rig rig = new Rig(helper, "rice_1", false);
        rig.stockRice(1, 1);
        rig.load();
        afterCooking(rig, ready -> {
            ready.claim.release(Ending.DROPPED);
            ready.take = StockpotWorkshops.recovery(ready.colony, ready.level, ready.at, ready.stances,
                ready.bookings, () -> { }).getFirst().nodes().getFirst();
            helper.assertTrue(ready.take.planned(ready.level).isEmpty(), "recovery obtains its kitchen turn");
            helper.assertTrue(!assignedWithRoom(ready, ready.take, 1),
                "one empty pack cell cannot recover both a meal and a lid after fetching its bowl");
            helper.assertTrue(assignedWithRoom(ready, ready.take, 2),
                "two empty cells are enough for the actual recovery outputs");
            SimpleContainer small = new SimpleContainer(new ItemStack(Items.STONE, 64),
                Containers.extract(ready.pack, stack -> stack.is(Items.BOWL), 1), ItemStack.EMPTY);
            Outcome outcome = Commit.run(ready.level, ready.take, ready.worker(small));
            helper.assertTrue(outcome instanceof Outcome.Done, "the accepted small pack can perform the real recovery");
            for (ItemStack made : ((Outcome.Done) outcome).made()) {
                helper.assertTrue(Containers.insert(small, made).isEmpty(), "the planned outputs fit without spilling");
            }
            helper.assertValueEqual(Goods.countIn(small, ready.plan.produces()), 1L, "the real meal is retained");
            helper.assertValueEqual(Goods.countIn(small, StockpotLoad.lid()), 1L, "the real lid is retained");
            ready.take.ended(ready.level, Ending.DONE);
        });
    }

    @GameTest(template = "empty")
    public static void recoveryCapacityCountsEveryNonStackableServing(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_1", false)) {
            StockpotRecipe recipe = new StockpotRecipe(List.of(Ingredient.of(ModItems.RICE_SEED.get())),
                ModSoupBases.WATER, new ItemStack(Items.MUSHROOM_STEW, 3), 1, Ingredient.of(Items.BOWL),
                StockpotVisuals.DEFAULT);
            StockpotPlan plan = StockpotPlan.of(rig.level, new RecipeHolder<>(
                ResourceLocation.fromNamespaceAndPath("folkways_test", "three_non_stackable_servings"), recipe))
                .orElseThrow();
            Node taking = new StockpotTake(rig.colony, rig.batch, rig.at, rig.stances, plan,
                rig.bookings, () -> { }, false);
            helper.assertTrue(!assignedWithRoom(rig, taking, 3),
                "three empty cells cannot hold three non-stackable meals and a lid");
            helper.assertTrue(assignedWithRoom(rig, taking, 4),
                "four empty cells can recover all real item stacks");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 250)
    public static void cancellingMultiBatchWorkKeepsUnspentGoodsAndRecoversOnlyTheInvestedBatch(GameTestHelper helper) {
        Rig rig = new Rig(helper, "rice_1", false);
        rig.stockRice(3, 3);
        rig.put(new ItemStack(Items.WATER_BUCKET));
        rig.put(new ItemStack(Items.WATER_BUCKET));
        StockpotWork work = new StockpotWork(rig.colony, rig.at, rig.stances, rig.plan, 3, false,
            rig.bookings, () -> { });
        rig.claim.release(Ending.DROPPED);
        rig.claim = rig.bookings.hold(rig.at, work.id());
        helper.assertTrue(rig.claim.take().isEmpty(), "the multi-batch work owns the station");
        var interrupted = (Workload.Within.Turn.Piece) work.next(rig.level, rig.worker);
        work.ended(rig.level, interrupted.node(), Ending.DROPPED);
        var resumed = (Workload.Within.Turn.Piece) work.next(rig.level, rig.worker);
        helper.assertTrue(!interrupted.node().id().equals(resumed.node().id()),
            "an urge resumes the same batch under a fresh action id");
        rig.apply(resumed.node());
        work.ended(rig.level, resumed.node(), Ending.DONE);
        work.ended(rig.level, Ending.DROPPED);
        rig.claim.release(Ending.DROPPED);
        helper.assertValueEqual(rig.count(ModItems.RICE_SEED.get()), 2L, "unstarted batches retain their rice");
        helper.assertValueEqual(rig.count(Items.WATER_BUCKET), 2L, "unstarted batches retain their full buckets");
        rig.take = StockpotWorkshops.recovery(rig.colony, rig.level, rig.at, rig.stances,
            rig.bookings, () -> { }).getFirst().nodes().getFirst();
        helper.assertTrue(rig.take.planned(rig.level).isEmpty(), "the invested batch alone is recoverable");
        afterCooking(rig, ready -> {
            ready.take();
            helper.assertValueEqual(ready.count(ModItems.COOKED_RICE.get()), 1L, "only the invested batch makes a meal");
            helper.assertValueEqual(ready.count(ModItems.RICE_SEED.get()), 2L, "recovery does not replay raw inputs");
            helper.assertValueEqual(ready.count(Items.WATER_BUCKET), 2L, "recovery does not replay soup bases");
            helper.assertValueEqual(ready.count(Items.BOWL), 2L, "only one carrier is spent");
            helper.assertValueEqual(ready.count(ModItems.STOCKPOT_LID.get()), 1L, "the reusable lid survives cancellation");
            ready.take.ended(ready.level, Ending.DONE);
        });
    }

    @GameTest(template = "empty")
    public static void aStaleNewBatchReleasesItsTurnForAnOlderInvestedBatch(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_1", false)) {
            rig.stockRice(1, 1);
            StockpotWork later = new StockpotWork(rig.colony, rig.at, rig.stances, rig.plan, 1, false,
                rig.bookings, () -> { });
            rig.load();
            rig.claim.release(Ending.DROPPED);
            Hold laterTurn = rig.bookings.hold(rig.at, later.id());
            helper.assertTrue(laterTurn.take().isEmpty(), "a stale empty-pot snapshot reaches the queue head");
            helper.assertTrue(later.refusal(rig.level).isPresent()
                && later.next(rig.level, rig.worker) instanceof Workload.Within.Turn.Leave,
                "the old invested batch causes an explicit failure instead of an endless wait");
            laterTurn.release(new Ending.Failed(later.id(),
                CookeryRefusal.STATION_CHANGED));
            helper.assertTrue(StockpotWorkshops.recovery(rig.colony, rig.level, rig.at, rig.stances,
                rig.bookings, () -> { }).size() == 1, "recovery can take the released queue");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unmarkingAndRemarkingCannotReviveOldStockpotWork(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_1", false)) {
            rig.stockRice(1, 1);
            rig.claim.release(Ending.DROPPED);
            StockpotWork old = new StockpotWork(rig.colony, rig.at, rig.stances, rig.plan, 1, false,
                rig.bookings, () -> { });
            Hold oldTurn = rig.bookings.hold(rig.at, old.id());
            helper.assertTrue(oldTurn.take().isEmpty(), "the original work has a valid ticket");
            rig.bookings.withdraw(rig.at);
            rig.bookings.admit(rig.at);
            Hold newTurn = rig.bookings.hold(rig.at, old.id());
            helper.assertTrue(newTurn.take().isEmpty(), "a newly marked kitchen can issue a fresh ticket");
            helper.assertTrue(old.refusal(rig.level).isPresent()
                && old.next(rig.level, rig.worker) instanceof Workload.Within.Turn.Leave,
                "the old object cannot borrow a new ticket with the same UUID");
            helper.assertValueEqual(rig.count(ModItems.RICE_SEED.get()), 1L, "withdrawn work does not spend ingredients");
            helper.assertTrue(rig.pot.getStatus() == IStockpot.PUT_SOUP_BASE, "withdrawn work does not load the pot");
            oldTurn.release(Ending.DROPPED);
            helper.assertTrue(rig.bookings.first(rig.at, old.id()), "releasing an old ticket preserves the new one");
            newTurn.release(Ending.DROPPED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anEmptyAbandonedMarkerCanBeReclaimedWithoutAdoptingForeignFood(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "rice_1", false)) {
            CompoundTag marker = new CompoundTag();
            marker.putUUID("colony", UUID.randomUUID());
            marker.putUUID("batch", UUID.randomUUID());
            rig.pot.getPersistentData().put(StockpotBatch.KEY, marker);
            var workshop = StockpotWorkshops.freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at,
                rig.stances, rig.bookings, () -> { }).orElseThrow();
            var ask = Produce.of(CookeryContent.ID,
                new WorkSite.AtBlock(rig.at), rig.plan.produces(), 1,
                Produce.UNBOUNDED);
            helper.assertTrue(!StockpotBatch.claimed(rig.pot) && !workshop.options(ask, Grown.of(ask)).isEmpty(),
                "a physically empty pot without its former holding can be reused");
            rig.stockRice(1, 1);
            rig.load();
            CompoundTag before = rig.pot.saveWithFullMetadata(rig.level.registryAccess());
            var foreign = StockpotWorkshops.freeze(UUID.randomUUID(), UUID.randomUUID(), rig.level, rig.at,
                rig.stances, rig.bookings, () -> { }).orElseThrow();
            helper.assertTrue(foreign.options(ask, Grown.of(ask)).isEmpty()
                && before.equals(rig.pot.saveWithFullMetadata(rig.level.registryAccess())),
                "a foreign pot with invested food remains entirely protected");
        }
        helper.succeed();
    }

    private static boolean assignedWithRoom(Rig rig, Node taking, int free) {
        var fixture = new PlanningFixture(rig.helper, free + 1);
        var source = new Stash(fixture.source);
        long bowls = taking.spec().needs().stream().mapToLong(Need::count).sum();
        var stock = new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.BOWL, (int) bowls))),
            Map.of(source, 27));
        var crafting = rig.body.licences().of(WaresContent.trade()).orElseThrow();
        var hauling = rig.body.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow();
        var hand = new Crew.Hand(rig.body.resident(), fixture.source, free, free + 1, 1,
            List.of(crafting, hauling), List.of(), Optional.empty());
        var known = new Known(fixture.situation(stock, List.of()), List.of(hand));
        var solver = new Solving();
        try {
            var planned = solver.handle(known, List.of(new Message.Submitted(CookeryContent.ID,
                Grown.of(taking))), 0);
            return planned.schedule().tours().values().stream().anyMatch(tour -> tour.nodes().contains(taking.id()));
        } finally {
            solver.closed();
            fixture.worker.body().discard();
        }
    }
    @GameTest(template = "empty", timeoutTicks = 450)
    public static void flexibleUdonUsesOneOfEachIngredientAndKeepsTheNativeQuality(GameTestHelper helper) {
        Rig rig = new Rig(helper, ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "flex_stockpot/udon_noodle"), false);
        rig.stockUdon();
        rig.load();
        afterCooking(rig, ready -> {
            helper.assertValueEqual(ready.pot.saveCustomOnly(ready.level.registryAccess()).getString("RecipeId"),
                ready.plan.id().toString(), "the real stockpot selected the native flexible recipe");
            ItemStack actual = ready.pot.getResult().copyWithCount(1);
            helper.assertTrue(QualityUtils.hasQuality(actual), "native flexible cooking attaches its real quality component");
            ready.take();
            helper.assertValueEqual(ready.count(ModItems.UDON_NOODLE.get()), 1L, "one native flexible serving is collected");
            helper.assertTrue(java.util.stream.IntStream.range(0, ready.pack.getContainerSize()).mapToObj(ready.pack::getItem).anyMatch(stack -> ItemStack.matches(stack, actual)),
                "the delivered dish keeps the native result's exact quality and components");
            helper.assertValueEqual(ready.count(ModItems.LETTUCE.get()) + ready.count(Items.EGG)
                + ready.count(ModItems.RAW_NOODLES.get()), 0L, "the minimal three real ingredients were each consumed once");
            helper.assertValueEqual(ready.count(Items.BUCKET), 1L, "native soup-base insertion returns its real bucket");
            helper.assertValueEqual(ready.count(Items.BOWL), 0L, "native take-out consumes exactly one bowl");
            helper.assertValueEqual(ready.count(ModItems.STOCKPOT_LID.get()), 1L, "the actual lid is returned for later work");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void flexibleBatchReloadRecoversItsRealQualityWithoutReplayingInputs(GameTestHelper helper) {
        Rig rig = new Rig(helper, ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "flex_stockpot/udon_noodle"), false);
        rig.stockUdon();
        rig.load();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.pot.getStatus() == IStockpot.COOKING,
            "the native flexible batch has actually started")).thenExecute(() -> {
                rig.claim.release(Ending.DROPPED);
                rig.reload();
                var recovery = StockpotWorkshops.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> { });
                helper.assertValueEqual(recovery.size(), 1, "the persisted flexible batch produces one recovery task");
                rig.take = recovery.getFirst().nodes().getFirst();
                helper.assertTrue(rig.take.planned(rig.level).isEmpty(), "the recovery owns a real exclusive kitchen turn");
            }).thenWaitUntil(rig::finished).thenExecute(() -> {
                try {
                    ItemStack result = rig.pot.getResult().copyWithCount(1);
                    rig.take();
                    rig.take.ended(rig.level, Ending.DONE);
                    helper.assertTrue(java.util.stream.IntStream.range(0, rig.pack.getContainerSize()).mapToObj(rig.pack::getItem).anyMatch(stack -> ItemStack.matches(stack, result)),
                        "reload keeps the native flexible quality and delivers the same real serving");
                    helper.assertValueEqual(rig.count(ModItems.UDON_NOODLE.get()), 1L, "recovery never creates a second meal");
                    helper.assertValueEqual(rig.count(Items.BUCKET), 1L, "recovery never repeats soup-base insertion");
                    helper.assertTrue(rig.bookings.free(rig.at), "the recovered flexible batch releases its actual turn");
                } finally {
                    rig.close();
                }
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 450)
    public static void changingAFlexibleResultComponentRelinquishesOwnershipWithoutTakingThePlayersFood(GameTestHelper helper) {
        Rig rig = new Rig(helper, ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "flex_stockpot/udon_noodle"), false);
        rig.stockUdon();
        rig.load();
        afterCooking(rig, ready -> {
            Quality changed = QualityUtils.getQuality(ready.pot.getResult()) == Quality.POOR ? Quality.SUPERB : Quality.POOR;
            QualityUtils.setQuality(ready.pot.getResult(), changed);
            helper.assertTrue(StockpotWorkshops.recovery(ready.colony, ready.level, ready.at, ready.stances,
                ready.bookings, () -> { }).isEmpty(), "a changed result is no longer the resident's saved batch");
            helper.assertTrue(Commit.run(ready.level, ready.take, ready.worker) instanceof Outcome.Failed,
                "the old planned take cannot extract the player's changed result");
            helper.assertValueEqual(ready.count(ModItems.UDON_NOODLE.get()), 0L, "no changed dish was silently adopted");
            helper.assertValueEqual(ready.count(Items.BOWL), 1L, "the planned carrier remains a real unspent bowl");
            helper.assertTrue(ready.pot.getTakeoutCount() == 1 && QualityUtils.getQuality(ready.pot.getResult()) == changed,
                "the player's changed dish and its native serving count remain in the real pot");
        });
    }

    @GameTest(template = "empty")
    public static void everyNativeFlexibleStockpotRecipeHasAPlanningSnapshot(GameTestHelper helper) {
        var recipes = helper.getLevel().getRecipeManager().getAllRecipesFor(ModRecipes.FLEX_STOCKPOT_RECIPE);
        helper.assertTrue(!recipes.isEmpty(), "the actual integration mod supplies flexible recipes");
        for (var recipe : recipes) {
            helper.assertTrue(StockpotPlan.of(helper.getLevel(), recipe).isPresent(),
                "the actual recipe has soup, ingredients, carriers and a planning snapshot: " + recipe.id());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void thePlannerChoosesFlexibleUdonWhenOnlyThreeIngredientsExist(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "flex_stockpot/udon_noodle"), false)) {
            rig.claim.release(Ending.DROPPED);
            var fixture = new PlanningFixture(helper, 36);
            var source = new Stash(fixture.source);
            List<ItemStack> raw = List.of(new ItemStack(ModItems.LETTUCE.get()), new ItemStack(Items.EGG),
                new ItemStack(ModItems.RAW_NOODLES.get()), new ItemStack(Items.WATER_BUCKET),
                new ItemStack(Items.BOWL), ModItems.STOCKPOT_LID.get().getDefaultInstance());
            var stock = new Stock(raw.stream().map(stack -> new Stock.Holding(source, stack)).toList(), Map.of(source, 27));
            var workshop = StockpotWorkshops.freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at,
                rig.stances, rig.bookings, () -> { }).orElseThrow();
            var crafting = rig.body.licences().of(WaresContent.trade()).orElseThrow();
            var hauling = rig.body.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow();
            var hand = new Crew.Hand(rig.body.resident(), fixture.source, 36, 36, 1,
                List.of(crafting, hauling), List.of(), Optional.empty());
            Known known = new Known(fixture.situation(stock, List.of(workshop)), List.of(hand));
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(fixture.target),
                Stances.at(fixture.target), Workload.Once.of(1)).needs(new Need(Goods.specOf(ModItems.UDON_NOODLE.get().getDefaultInstance()), 1)).done();
            Node consumer = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            };
            Solving solver = new Solving();
            try {
                var result = solver.handle(known, List.of(new Message.Submitted(CookeryContent.ID, Grown.of(consumer))), 0);
                helper.assertTrue(result.diagnosis().shortfalls().isEmpty() && result.diagnosis().unassigned().isEmpty(),
                    "the real fixed+flexible catalogue finds the feasible three-ingredient native route: " + result.diagnosis());
            } finally {
                solver.closed();
                fixture.worker.body().discard();
            }
        }
        helper.succeed();
    }
    @GameTestGenerator
    public static List<TestFunction> allNativeStockpotRecipes() {
        List<TestFunction> tests = new ArrayList<>(NativeCookingMatrix.recipes("stockpot", 77, 1100, StockpotGameTests::nativeRecipe));
        tests.addAll(NativeCookingMatrix.recipes("flex_stockpot", 28, 1100, StockpotGameTests::nativeRecipe));
        return List.copyOf(tests);
    }

    private static void nativeRecipe(GameTestHelper helper, ResourceLocation recipe) {
        Rig rig = new Rig(helper, recipe, false);
        List<ItemStack> inputs = NativeCookingMatrix.ingredients(rig.plan.recipe().ingredients(), rig.plan.flexible(),
            chosen -> rig.plan.selected(rig.level, new StockpotInput(chosen, rig.plan.recipe().soupBase()))).orElseThrow(() ->
                new AssertionError("no actual ingredient assignment selects the pinned native recipe: " + recipe));
        var base = SoupBaseManager.getSoupBase(rig.plan.recipe().soupBase());
        ItemStack soup = base.getDisplayStack().copyWithCount(1);
        if (soup.isEmpty() || !base.isSoupBase(soup)) {
            soup = Goods.members(rig.plan.soup().spec()).stream().map(Item::getDefaultInstance)
                .filter(base::isSoupBase).findFirst().orElseThrow();
        }
        Map<Item, Long> expected = new LinkedHashMap<>();
        NativeCookingMatrix.count(expected, new ItemStack(Items.BUCKET));
        NativeCookingMatrix.count(expected, ModItems.STOCKPOT_LID.get().getDefaultInstance());
        rig.put(soup);
        for (ItemStack input : inputs) {
            if (input.isEmpty()) continue;
            rig.put(input);
            Item container = ItemUtils.getContainerItem(input);
            if (container != Items.AIR) NativeCookingMatrix.count(expected, container.getDefaultInstance());
        }
        if (rig.plan.carrier().isPresent()) {
            ItemStack carrier = java.util.Arrays.stream(rig.plan.recipe().carrier().getItems()).filter(stack -> !stack.isEmpty())
                .findFirst().orElseThrow().copyWithCount(rig.plan.portions());
            rig.put(carrier);
        }
        rig.put(ModItems.STOCKPOT_LID.get().getDefaultInstance());
        rig.load();
        afterCooking(rig, ready -> {
            helper.assertValueEqual(ready.pot.saveCustomOnly(ready.level.registryAccess()).getString("RecipeId"), recipe.toString(),
                "the real block executed the pinned native resource rather than silently selecting another recipe");
            ItemStack actualProduct = ready.pot.getResult().copyWithCount(ready.plan.portions());
            helper.assertTrue(ItemStack.matches(actualProduct, ready.plan.expectedResult(ready.level, inputs)),
                "the actual resource cooked its complete native output and quality: " + recipe);
            ready.take();
            long matching = java.util.stream.IntStream.range(0, ready.pack.getContainerSize()).mapToObj(ready.pack::getItem)
                .filter(stack -> ItemStack.isSameItemSameComponents(stack, actualProduct)).mapToLong(ItemStack::getCount).sum();
            helper.assertValueEqual(matching, (long) ready.plan.portions(), "every separately served native portion retains its exact components: " + recipe);
            NativeCookingMatrix.count(expected, actualProduct);
            NativeCookingMatrix.inventory(helper, ready.pack, expected, recipe);
            helper.assertTrue(ready.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(ready.cell).inflate(3)).isEmpty(),
                "all native products and containers reach actual logistics without loose duplicates: " + recipe);
        });
    }
    private static void afterCooking(Rig rig, Consumer<Rig> assertion) {
        rig.helper.startSequence().thenWaitUntil(rig::finished).thenExecute(() -> {
            try {
                assertion.accept(rig);
            } finally {
                rig.close();
            }
        }).thenSucceed();
    }

    private static final class Rig implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos cell;
        final WorldPos at;
        final Stances stances;
        final UUID colony = UUID.randomUUID();
        final StockpotPlan plan;
        final boolean originalLid;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(36);
        StockpotBlockEntity pot;
        ResidentEntity body;
        Worker worker;
        UUID batch;
        Hold claim;
        Node load;
        Node take;

        Rig(GameTestHelper helper, String recipe, boolean originalLid) {
            this(helper, ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "stockpot/" + recipe), originalLid);
        }

        Rig(GameTestHelper helper, ResourceLocation recipe, boolean originalLid) {
            this.helper = helper;
            level = helper.getLevel();
            cell = helper.absolutePos(new BlockPos(1, 1, 1));
            at = WorldPos.of(level, cell);
            stances = Stances.at(at.at(cell.east()));
            this.originalLid = originalLid;
            level.setBlockAndUpdate(cell.below(), Blocks.CAMPFIRE.defaultBlockState());
            level.setBlockAndUpdate(cell.east().below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(cell, ModBlocks.STOCKPOT.get().defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH));
            pot = (StockpotBlockEntity) level.getBlockEntity(cell);
            pot.getPersistentData();
            body = newBody();
            worker = worker();
            if (originalLid) {
                pot.onLitClick(level, body, ModItems.STOCKPOT_LID.get().getDefaultInstance());
            }
            var holder = StockpotPlan.find(level, recipe).orElse(null);
            helper.assertTrue(holder != null, "the real integration mod supplies recipe " + recipe);
            plan = StockpotPlan.of(level, holder).orElseThrow();
            nextBatch();
        }

        ResidentEntity newBody() {
            ResidentEntity resident = PersonBody.RESIDENT.get().create(level);
            resident.setNoAi(true);
            resident.moveTo(cell.getX() + 1.5, cell.getY(), cell.getZ() + 0.5, 0, 0);
            level.addFreshEntity(resident);
            return resident;
        }

        void nextBatch() {
            if (claim != null) {
                claim.release(Ending.DONE);
            }
            batch = UUID.randomUUID();
            claim = bookings.hold(at, batch);
            helper.assertTrue(claim.take().isEmpty(), "the fixture obtains a real kitchen booking");
            load = new StockpotLoad(colony, batch, at, stances, plan, originalLid, bookings, () -> { });
            take = new StockpotTake(colony, batch, at, stances, plan, bookings, () -> { }, false);
        }

        void stockUdon() {
            put(new ItemStack(ModItems.LETTUCE.get()));
            put(new ItemStack(Items.EGG));
            put(new ItemStack(ModItems.RAW_NOODLES.get()));
            put(new ItemStack(Items.WATER_BUCKET));
            put(new ItemStack(Items.BOWL));
            if (!originalLid) {
                put(ModItems.STOCKPOT_LID.get().getDefaultInstance());
            }
        }

        void stockRice(int rice, int bowls) {
            put(new ItemStack(ModItems.RICE_SEED.get(), rice));
            put(new ItemStack(Items.WATER_BUCKET));
            put(new ItemStack(Items.BOWL, bowls));
            if (!originalLid) {
                put(ModItems.STOCKPOT_LID.get().getDefaultInstance());
            }
        }

        void put(ItemStack stack) {
            helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "the fixture pack has room for " + stack);
        }

        long count(Item item) {
            return Goods.countIn(pack, Goods.specOf(item.getDefaultInstance()));
        }

        void load() {
            apply(load);
        }

        void take() {
            apply(take);
        }

        void apply(Node node) {
            Outcome outcome = Commit.run(level, node, worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "the production node commits: " + outcome);
            ((Outcome.Done) outcome).made().forEach(this::put);
        }

        void drive(StockpotWork work) {
            for (int steps = 0; steps < 4; steps++) {
                Workload.Within.Turn turn = work.next(level, worker);
                if (turn instanceof Workload.Within.Turn.Piece action) {
                    Outcome outcome = Commit.run(level, action.node(), worker);
                    helper.assertTrue(outcome instanceof Outcome.Done, "the real inner stockpot action commits: " + outcome);
                    ((Outcome.Done) outcome).made().forEach(this::put);
                    work.ended(level, action.node(), Ending.DONE);
                } else if (turn instanceof Workload.Within.Turn.Leave) {
                    helper.assertTrue(work.commit(level, worker) instanceof Outcome.Done,
                        "the outer production offer completes only after every serving");
                    return;
                } else {
                    helper.assertTrue(false, "waiting for the real stockpot: " + pot.saveCustomOnly(level.registryAccess()));
                }
            }
            helper.assertTrue(false, "more real work remains for the next server tick");
        }
        void finished() {
            var current = level.getBlockEntity(cell);
            helper.assertTrue(current instanceof StockpotBlockEntity, "the real stockpot remains in the test world");
            pot = (StockpotBlockEntity) current;
            helper.assertTrue(pot.getStatus() == IStockpot.FINISHED, "the real block ticker finishes the batch: "
                + "status=" + pot.getStatus() + ", lid=" + pot.hasLid() + ", heat=" + pot.hasHeatSource(level)
                + ", remaining=" + pot.saveCustomOnly(level.registryAccess()).getInt("CurrentTick")
                + ", testTick=" + helper.getTick()
                + ", removed=" + pot.isRemoved() + ", chunkStatus=" + level.getChunkAt(cell).getFullStatus()
                + ", entitiesLoaded=" + level.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.asLong(cell))
                + ", entityTicking=" + level.isPositionEntityTicking(cell)
                + ", inputs=" + pot.getInputs());
        }

        void reload() {
            replace(new StockpotBlockEntity(cell, pot.getBlockState()));
        }

        void replace(StockpotBlockEntity replacement) {
            CompoundTag saved = pot.saveWithFullMetadata(level.registryAccess());
            replacement.loadWithComponents(saved, level.registryAccess());
            level.removeBlockEntity(cell);
            level.setBlockEntity(replacement);
            pot = replacement;
        }

        Worker worker() {
            return worker(pack);
        }

        Worker worker(Container inventory) {
            return new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return body; }
                public Container pack() { return inventory; }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) {
                    if (!stack.isEmpty()) {
                        throw new AssertionError("unexpected spill: " + stack);
                    }
                }
                public Effects effects() { return body.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }

        @Override
        public void close() {
            claim.release(Ending.DROPPED);
            take.ended(level, Ending.DROPPED);
            body.discard();
        }
    }
}
