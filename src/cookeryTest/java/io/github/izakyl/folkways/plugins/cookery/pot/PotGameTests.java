package io.github.izakyl.folkways.plugins.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IPot;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.container.SimpleInput;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.PotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.cookery.testing.NativeCookingMatrix;
import io.github.izakyl.folkways.plugins.cookery.testing.KitchenRecoveryWanted;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PotGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(PotGameTests.class); }

    @GameTest(template = "empty")
    public static void potBudgetDeduplicatesThePromisedBeefButRetainsUnexpectedResultRoom(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "pot/beef_to_cooked_beef_3", 1)) {
            var beef = Goods.specOf(Items.COOKED_BEEF.getDefaultInstance());
            var extras = rig.work.planningLeaves(List.of(new Amount(beef, 3)));
            helper.assertValueEqual(extras.stream().filter(amount -> amount.spec().equals(beef)).mapToLong(Amount::count).sum(),
                0L, "the three promised native beef pieces are not another three leftovers");
            helper.assertTrue(extras.stream().anyMatch(amount -> amount.spec().equals(Leaves.UNSEEN) && amount.count() > 0),
                "native dark or uncertain outputs retain their conservative capacity");
            helper.assertTrue(rig.work.planningLeaves(List.of()).equals(Leaves.merged(rig.work.spec().leaves())),
                "a pot recovery without downstream promises retains the full native take-out budget");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 350)
    public static void aRealFixedPotCooksThreeInputsAndTakesOutAllThreeWithTheActualShovel(GameTestHelper helper) {
        Rig rig = new Rig(helper, "pot/beef_to_cooked_beef_3", 1);
        rig.stockBeef(3, 1);
        rig.start();
        helper.assertValueEqual(PotBatch.stirs(rig.pot, rig.level), 0, "three native hits satisfy the real recipe's stir count");
        helper.assertValueEqual(rig.count(Items.BEEF), 0L, "the three actual beef inputs are already invested");
        helper.assertValueEqual(rig.count(ModItems.OIL.get()), 0L, "the real oil input is spent once");
        afterCooking(rig, ready -> {
            ready.finish(true);
            helper.assertValueEqual(ready.count(Items.COOKED_BEEF), 3L, "native no-carrier take-out hands over the whole three-item result");
            helper.assertTrue(PotStation.empty(ready.pot) && !PotBatch.claimed(ready.pot), "the actual pot is reusable after native reset");
            helper.assertValueEqual(ready.count(ModItems.KITCHEN_SHOVEL.get()), 1L, "the same reusable shovel remains physical");
            helper.assertTrue(ready.level.getEntitiesOfClass(ItemEntity.class, ready.body.getBoundingBox().inflate(2)).isEmpty(),
                "non-player native take-out is captured into actual logistics");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 350)
    public static void flexiblePotUsesNativeQualityAndConsumesItsActualBowl(GameTestHelper helper) {
        Rig rig = new Rig(helper, "flex_pot/scramble_egg_with_tomatoes", 1);
        rig.stockEggTomato(true);
        rig.start();
        afterCooking(rig, ready -> {
            ItemStack nativeResult = ready.pot.getResult().copy();
            helper.assertTrue(QualityUtils.hasQuality(nativeResult), "native flexible stir-fry attaches the actual quality");
            ready.finish(true);
            helper.assertTrue(ready.stacks().stream().anyMatch(stack -> ItemStack.matches(stack, nativeResult)),
                "the exact native result, including quality, reaches the pack");
            helper.assertValueEqual(ready.count(Items.BOWL), 0L, "native take-out spends the preflighted bowl exactly once");
            helper.assertValueEqual(ready.count(Items.EGG) + ready.count(ModItems.TOMATO.get()), 0L, "both real food ingredients are spent once");
            helper.assertValueEqual(ready.count(ModItems.KITCHEN_SHOVEL.get()), 1L, "the physical shared shovel is neither consumed nor copied");
        });
    }

    @GameTest(template = "empty")
    public static void missingCarriersNeverOilOrLoadTheRealPot(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "flex_pot/scramble_egg_with_tomatoes", 1)) {
            rig.stockEggTomato(false);
            CompoundTag before = rig.pot.saveWithFullMetadata(rig.level.registryAccess());
            Node load = ((Workload.Within.Turn.Piece) rig.work.next(rig.level, rig.worker)).node();
            helper.assertTrue(Commit.run(rig.level, load, rig.worker) instanceof Outcome.Failed,
                "missing native carriers fail before any oil or ingredient interaction");
            helper.assertTrue(before.equals(rig.pot.saveWithFullMetadata(rig.level.registryAccess())), "the real pot remains entirely unchanged");
            helper.assertValueEqual(rig.count(ModItems.OIL.get()), 1L, "the actual oil remains unspent");
            helper.assertValueEqual(rig.count(Items.EGG) + rig.count(ModItems.TOMATO.get()), 2L, "both physical foods remain available");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 350)
    public static void nativeFoodContainersReturnFromPotInsertionExactlyOnce(GameTestHelper helper) {
        Rig rig = new Rig(helper, "pot/cooked_rice_to_sticky_rice_cake_1", 1);
        ItemStack rice = new ItemStack(ModItems.COOKED_RICE.get());
        var food = rice.get(DataComponents.FOOD);
        // Native ItemUtils reads FOOD.usingConvertsTo before the crafting remainder or IHasContainer.
        // Exercise its real component-based container path, instead of assuming every rice item returns a bowl.
        rice.set(DataComponents.FOOD, new net.minecraft.world.food.FoodProperties(food.nutrition(), food.saturation(),
            food.canAlwaysEat(), food.eatSeconds(), Optional.of(new ItemStack(Items.BOWL)), food.effects()));
        helper.assertTrue(ItemUtils.getContainerItem(rice) == Items.BOWL, "this actual ingredient declares its native returned container");
        rig.put(rice);
        rig.put(new ItemStack(ModItems.OIL.get()));
        rig.start();
        helper.assertValueEqual(rig.count(Items.BOWL), 1L, "the real native ingredient insertion returns the rice bowl");
        afterCooking(rig, ready -> {
            ready.finish(true);
            helper.assertValueEqual(ready.count(Items.BOWL), 1L, "take-out does not replay the input's empty container");
            helper.assertValueEqual(ready.count(ModItems.COOKED_RICE.get()), 0L, "the actual original cooked rice is spent");
            helper.assertValueEqual(ready.stacks().stream().filter(stack -> stack.is(ready.plan.recipe().result().getItem())).mapToInt(ItemStack::getCount).sum(),
                1, "the native secondary cooking result is collected once");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 350)
    public static void defaultCookedRiceDoesNotInventABowlOmittedByNativeContainerLogic(GameTestHelper helper) {
        Rig rig = new Rig(helper, "pot/cooked_rice_to_sticky_rice_cake_1", 1);
        ItemStack rice = new ItemStack(ModItems.COOKED_RICE.get());
        helper.assertTrue(rice.getCraftingRemainingItem().is(Items.BOWL) && ItemUtils.getContainerItem(rice) == Items.AIR,
            "the pinned native API's FOOD branch returns no insertion container despite the crafting bowl remainder");
        rig.put(rice);
        rig.put(new ItemStack(ModItems.OIL.get()));
        rig.start();
        helper.assertValueEqual(rig.count(Items.BOWL), 0L, "Folkways does not generate a bowl that the native insertion never produced");
        afterCooking(rig, ready -> {
            ready.finish(true);
            helper.assertValueEqual(ready.count(Items.BOWL), 0L, "take-out does not add an omitted native byproduct later");
            helper.assertValueEqual(ready.count(ModItems.COOKED_RICE.get()), 0L, "the original ingredient is actually consumed");
        });
    }
    @GameTest(template = "empty")
    public static void aFailureAfterNativeIngredientAcceptanceRollsBackOilFoodAndClaimTogether(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "pot/beef_to_cooked_beef_3", 1)) {
            rig.stockBeef(3, 1);
            rig.replace(new PotBlockEntity(rig.cell, rig.pot.getBlockState()) {
                int accepted;
                public boolean addIngredient(Level level, LivingEntity user, ItemStack stack) {
                    boolean result = super.addIngredient(level, user, stack);
                    if (++accepted == 2) throw new IllegalStateException("after the second native insertion");
                    return result;
                }
            });
            CompoundTag before = rig.pot.saveWithFullMetadata(rig.level.registryAccess());
            Node load = ((Workload.Within.Turn.Piece) rig.work.next(rig.level, rig.worker)).node();
            boolean threw = false;
            try { Commit.run(rig.level, load, rig.worker); } catch (RuntimeException expected) { threw = true; }
            helper.assertTrue(threw, "the injected native acceptance failure is exercised");
            helper.assertTrue(before.equals(rig.pot.saveWithFullMetadata(rig.level.registryAccess())), "all actual oil, inputs and ownership roll back together");
            helper.assertValueEqual(rig.count(Items.BEEF), 3L, "exactly the original three beef return through core refund");
            helper.assertValueEqual(rig.count(ModItems.OIL.get()), 1L, "exactly the original oil returns");
            helper.assertTrue(!PotBatch.claimed(rig.pot), "failed native loading leaves no stale batch claim");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 350)
    public static void aFailureAfterNativeTakeOutRestoresTheBatchAndDiscardsItsCapturedProduct(GameTestHelper helper) {
        Rig rig = new Rig(helper, "flex_pot/scramble_egg_with_tomatoes", 1);
        rig.stockEggTomato(true);
        rig.start();
        afterCooking(rig, ready -> {
            ready.replace(new PotBlockEntity(ready.cell, ready.pot.getBlockState()) {
                public boolean takeOutProduct(Level level, LivingEntity user, ItemStack hand) {
                    boolean result = super.takeOutProduct(level, user, hand);
                    if (result) throw new IllegalStateException("after real native take-out");
                    return result;
                }
            });
            ItemStack dish = ready.pot.getResult().copy();
            Node take = ((Workload.Within.Turn.Piece) ready.work.next(ready.level, ready.worker)).node();
            boolean threw = false;
            try { Commit.run(ready.level, take, ready.worker); } catch (RuntimeException expected) { threw = true; }
            helper.assertTrue(threw, "the injected post-take native failure is exercised");
            helper.assertTrue(ready.pot.getStatus() == IPot.FINISHED && ItemStack.matches(ready.pot.getResult(), dish),
                "the actual native batch and its quality are restored for one later retry");
            helper.assertValueEqual(ready.count(Items.BOWL), 1L, "the real drawn bowl is refunded once");
            helper.assertValueEqual(ready.count(dish.getItem()), 0L, "the rolled-back captured dish was not delivered");
            ready.replace(new PotBlockEntity(ready.cell, ready.pot.getBlockState()));
            ready.finish(true);
            helper.assertValueEqual(ready.count(dish.getItem()), 1L, "one later native take-out collects exactly one dish");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 350)
    public static void aReloadedOwnedPotContinuesItsNativeTickerWithoutReinvestingFood(GameTestHelper helper) {
        Rig rig = new Rig(helper, "flex_pot/scramble_egg_with_tomatoes", 1);
        rig.stockEggTomato(true);
        rig.start();
        rig.work.ended(rig.level, Ending.REVOKED);
        rig.claim.release(Ending.REVOKED);
        rig.replace(new PotBlockEntity(rig.cell, rig.pot.getBlockState()));
        var recovery = rig.station.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> { });
        helper.assertValueEqual(recovery.size(), 1, "the actual persisted batch supplies one exclusive recovery task");
        rig.work = (PotWork) recovery.getFirst().nodes().getFirst();
        helper.assertTrue(rig.work.planned(rig.level).isEmpty(), "recovery acquires its real kitchen turn");
        KitchenRecoveryWanted.assertKept(helper, recovery.getFirst(), () -> rig.station.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
        afterCooking(rig, ready -> {
            ItemStack dish = ready.pot.getResult().copy();
            ready.step();
            KitchenRecoveryWanted.assertKept(helper, recovery.getFirst(), () -> ready.station.recovery(ready.colony, ready.level, ready.at, ready.stances, ready.bookings, () -> {}));
            ready.finish(true);
            helper.assertTrue(ready.station.activeRecovery(ready.colony, ready.level, ready.at, ready.bookings).isEmpty(), "ended pot recovery is removed from wanted");
            helper.assertTrue(ready.stacks().stream().anyMatch(stack -> ItemStack.matches(stack, dish)), "recovery preserves the native quality");
            helper.assertValueEqual(ready.count(Items.EGG) + ready.count(ModItems.TOMATO.get()) + ready.count(ModItems.OIL.get()),
                0L, "reload never reinvests or refunds already consumed materials");
            helper.assertTrue(ready.bookings.free(ready.at), "the recovered native batch releases its actual booking");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void twoNativePotBatchesReuseTheSameShovelAndConsumeOnlyTwoOilItems(GameTestHelper helper) {
        Rig rig = new Rig(helper, "pot/beef_to_cooked_beef_3", 2);
        rig.stockBeef(6, 2);
        rig.start();
        helper.startSequence().thenWaitUntil(rig::finished).thenExecute(() -> {
            for (int step = 0; step < 8; step++) {
                rig.step();
                if (rig.pot.getStatus() == IPot.COOKING && PotBatch.stirs(rig.pot, rig.level) == 0) return;
            }
            throw new AssertionError("the actual second batch did not reach native cooking");
        }).thenWaitUntil(rig::finished).thenExecute(() -> {
            try {
                rig.finish(true);
                helper.assertValueEqual(rig.count(Items.COOKED_BEEF), 6L, "two real native batches provide all six products");
                helper.assertValueEqual(rig.count(Items.BEEF) + rig.count(ModItems.OIL.get()), 0L, "both batches spend exactly the preflighted foods and oils");
                helper.assertValueEqual(rig.count(ModItems.KITCHEN_SHOVEL.get()), 1L, "continuous cooking reuses the same physical shovel");
            } finally { rig.close(); }
        }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void aTimedOutNativePotReturnsDarkCuisineButDoesNotSucceedOrRefundTheOriginalDish(GameTestHelper helper) {
        Rig rig = new Rig(helper, "pot/beef_to_cooked_beef_3", 1);
        rig.stockBeef(3, 1);
        rig.start();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.pot.getStatus() == IPot.BURNT,
            "the real native 800-tick take-out window expires into burnt state")).thenExecute(() -> {
                try {
                    rig.finish(false);
                    helper.assertValueEqual(rig.count(FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE)), 1L,
                        "native burnt take-out provides the real dark cuisine");
                    helper.assertValueEqual(rig.count(Items.COOKED_BEEF), 0L, "the original food goal did not gain a fake successful output");
                    helper.assertValueEqual(rig.count(Items.BEEF) + rig.count(ModItems.OIL.get()), 0L,
                        "a late native batch cannot refund spent foods or oil");
                    helper.assertTrue(!PotBatch.claimed(rig.pot) && rig.bookings.free(rig.at), "the failed food target leaves no stuck claim or booking");
                } finally { rig.close(); }
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void aFullyBurntNativePotLeavesRealCharcoalAndEndsItsOldClaimWithoutRefundingInputs(GameTestHelper helper) {
        Rig rig = new Rig(helper, "pot/beef_to_cooked_beef_3", 1);
        rig.stockBeef(3, 1);
        rig.start();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(PotStation.empty(rig.pot),
            "the real native cooking, take-out and burnt windows finish before reset")).thenExecute(() -> {
                try {
                    long charcoal = rig.level.getEntitiesOfClass(ItemEntity.class, new AABB(rig.cell).inflate(2)).stream()
                        .filter(drop -> drop.getItem().is(Items.CHARCOAL)).mapToLong(drop -> drop.getItem().getCount()).sum();
                    helper.assertTrue(charcoal >= 1 && charcoal <= 3, "native destruction leaves its real one-to-three charcoal items");
                    helper.assertTrue(rig.work.refusal(rig.level).isPresent(), "the obsolete food work explicitly fails rather than waiting forever");
                    helper.assertTrue(rig.station.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> { }).isEmpty(),
                        "a burnt-out empty pot has no fake recoverable meal");
                    rig.work.ended(rig.level, new Ending.Failed(rig.work.id(), CookeryRefusal.STATION_CHANGED));
                    rig.claim.release(Ending.REVOKED);
                    helper.assertTrue(!PotBatch.claimed(rig.pot) && rig.bookings.free(rig.at), "native reset clears stale ownership and releases the kitchen");
                    helper.assertValueEqual(rig.count(Items.BEEF) + rig.count(ModItems.OIL.get()) + rig.count(Items.COOKED_BEEF), 0L,
                        "only native charcoal remains, without spent input refunds or recreated food");
                } finally { rig.close(); }
            }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void withdrawnAndReadmittedPotTurnsCannotReviveAnOldLoadingPiece(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "pot/beef_to_cooked_beef_3", 1)) {
            rig.stockBeef(3, 1);
            Node load = ((Workload.Within.Turn.Piece) rig.work.next(rig.level, rig.worker)).node();
            rig.bookings.withdraw(rig.at);
            rig.bookings.admit(rig.at);
            helper.assertTrue(Commit.run(rig.level, load, rig.worker) instanceof Outcome.Failed, "a re-marked station cannot reuse the stale physical turn");
            helper.assertTrue(PotStation.empty(rig.pot), "the old loading piece never oils or fills the real pot");
            helper.assertValueEqual(rig.count(Items.BEEF), 3L, "all original food inputs remain physical");
            helper.assertValueEqual(rig.count(ModItems.OIL.get()), 1L, "the stale turn never spends its oil");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyNativeFixedAndFlexiblePotRecipeHasACompletePlanningSnapshot(GameTestHelper helper) {
        var fixed = helper.getLevel().getRecipeManager().getAllRecipesFor(ModRecipes.POT_RECIPE);
        var flexible = helper.getLevel().getRecipeManager().getAllRecipesFor(ModRecipes.FLEX_POT_RECIPE);
        helper.assertTrue(!fixed.isEmpty() && !flexible.isEmpty(), "both actual native pot recipe types are loaded");
        for (var recipe : fixed) helper.assertTrue(PotPlan.of(helper.getLevel(), recipe).isPresent(), "actual fixed recipe is covered: " + recipe.id());
        for (var recipe : flexible) helper.assertTrue(PotPlan.of(helper.getLevel(), recipe).isPresent(), "actual flexible recipe is covered: " + recipe.id());
        helper.succeed();
    }

    @GameTestGenerator
    public static List<TestFunction> allNativePotRecipes() {
        List<TestFunction> tests = new ArrayList<>(NativeCookingMatrix.recipes("pot", 198, 400, PotGameTests::nativeRecipe));
        tests.addAll(NativeCookingMatrix.recipes("flex_pot", 27, 400, PotGameTests::nativeRecipe));
        return List.copyOf(tests);
    }

    private static void nativeRecipe(GameTestHelper helper, ResourceLocation recipe) {
        Rig rig = new Rig(helper, recipe.getPath(), 1);
        List<ItemStack> inputs = NativeCookingMatrix.ingredients(rig.plan.recipe().ingredients(), rig.plan.flexible(),
            chosen -> rig.plan.selected(rig.level, new SimpleInput(chosen))).orElseThrow(() ->
                new AssertionError("no actual ingredient assignment selects the pinned native recipe: " + recipe));
        Map<Item, Long> expected = new LinkedHashMap<>();
        NativeCookingMatrix.count(expected, new ItemStack(ModItems.KITCHEN_SHOVEL.get()));
        for (ItemStack input : inputs) {
            if (input.isEmpty()) continue;
            rig.put(input);
            Item container = ItemUtils.getContainerItem(input);
            if (container != Items.AIR) NativeCookingMatrix.count(expected, container.getDefaultInstance());
        }
        rig.put(new ItemStack(ModItems.OIL.get()));
        if (rig.plan.carrier().isPresent()) {
            ItemStack carrier = java.util.Arrays.stream(rig.plan.recipe().carrier().getItems()).filter(stack -> !stack.isEmpty())
                .findFirst().orElseThrow().copyWithCount(rig.plan.count());
            rig.put(carrier);
        }
        rig.start();
        afterCooking(rig, ready -> {
            ItemStack actualProduct = ready.pot.getResult().copy();
            helper.assertTrue(ItemStack.matches(actualProduct, ready.plan.result(ready.level, inputs)),
                "the requested native resource actually cooked its own result and native quality: " + recipe);
            ready.finish(true);
            helper.assertTrue(ready.stacks().stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, actualProduct))
                    .mapToLong(ItemStack::getCount).sum() == actualProduct.getCount(),
                "the entire native product stack and all its components reached real logistics: " + recipe);
            NativeCookingMatrix.count(expected, actualProduct);
            NativeCookingMatrix.inventory(helper, ready.pack, expected, recipe);
            helper.assertTrue(ready.level.getEntitiesOfClass(ItemEntity.class, new AABB(ready.cell).inflate(3)).isEmpty(),
                "native products and containers are not duplicated as loose drops: " + recipe);
        });
    }
    @GameTest(template = "empty", timeoutTicks = 350)
    public static void explicitSuspiciousStirFryUsesNativeNoMatchCookingAndOneActualBowl(GameTestHelper helper) {
        Rig rig = new Rig(helper, PotPlan.Mode.SUSPICIOUS);
        rig.stockProcedural(true); long started = rig.level.getGameTime(); rig.start();
        helper.assertValueEqual(rig.pot.getCurrentTick(), 200, "native unmatched cooking sets its real 200-tick clock");
        afterCooking(rig, ready -> {
            helper.assertTrue(ready.level.getGameTime() - started >= 200, "the actual native cooking clock elapsed");
            ready.finish(true);
            helper.assertValueEqual(ready.count(FoodBiteRegistry.getItem(FoodBiteRegistry.SUSPICIOUS_STIR_FRY)), 1L, "exactly one native suspicious dish is collected");
            ready.assertProceduralSpent();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1350)
    public static void explicitDarkCuisineWaitsThroughTheNativeTakeoutWindowBeforeCollecting(GameTestHelper helper) {
        Rig rig = new Rig(helper, PotPlan.Mode.DARK); rig.stockProcedural(true);
        long started = rig.level.getGameTime(); rig.start();
        helper.startSequence().thenWaitUntil(rig::finished).thenExecute(() -> {
            helper.assertTrue(rig.work.next(rig.level, rig.worker) instanceof Workload.Within.Turn.Wait,
                "an explicit dark target does not prematurely serve the suspicious dish");
            helper.assertValueEqual(rig.count(Items.BOWL), 1L, "the bowl remains physical while waiting to burn");
        }).thenWaitUntil(() -> helper.assertTrue(rig.pot.getStatus() == IPot.BURNT, "the real native 800-tick serving window must expire"))
            .thenExecute(() -> {
                try {
                    helper.assertTrue(rig.level.getGameTime() - started >= 1000, "both actual native windows elapsed without state acceleration");
                    rig.finish(true);
                    helper.assertValueEqual(rig.count(FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE)), 1L, "one native dark cuisine fulfills its explicit target");
                    helper.assertValueEqual(rig.count(FoodBiteRegistry.getItem(FoodBiteRegistry.SUSPICIOUS_STIR_FRY)), 0L, "waiting did not also award the earlier dish");
                    rig.assertProceduralSpent();
                } finally { rig.close(); }
            }).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void aMissingBowlRejectsProceduralCookingBeforeOilOrSeedInvestment(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, PotPlan.Mode.SUSPICIOUS)) {
            rig.stockProcedural(false); CompoundTag before = rig.pot.saveWithFullMetadata(rig.level.registryAccess());
            Node load = ((Workload.Within.Turn.Piece) rig.work.next(rig.level, rig.worker)).node();
            helper.assertTrue(Commit.run(rig.level, load, rig.worker) instanceof Outcome.Failed, "a real bowl is required before any native loading");
            helper.assertTrue(before.equals(rig.pot.saveWithFullMetadata(rig.level.registryAccess())), "missing carriers leave the actual pot unchanged");
            helper.assertValueEqual(rig.count(Items.WHEAT_SEEDS), 1L, "the actual seed remains available");
            helper.assertValueEqual(rig.count(ModItems.OIL.get()), 1L, "the actual oil remains available");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void proceduralPotTargetsRequireAnExactDishWithoutInjectingManagerRecipes(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, PotPlan.Mode.DARK)) {
            var bench = rig.station.freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).orElseThrow();
            ItemSpec dark = Goods.specOf(FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE).getDefaultInstance());
            ItemSpec suspicious = Goods.specOf(FoodBiteRegistry.getItem(FoodBiteRegistry.SUSPICIOUS_STIR_FRY).getDefaultInstance());
            Produce broad = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(rig.at), ItemSpec.anyOf(List.of(dark, suspicious)), 1, Produce.UNBOUNDED);
            helper.assertTrue(bench.options(broad, Grown.of(broad)).isEmpty(), "a broad food request never intentionally cooks a failed dish");
            for (ItemSpec dish : List.of(dark, suspicious)) {
                Produce exact = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(rig.at), dish, 1, Produce.UNBOUNDED);
                helper.assertTrue(!bench.options(exact, Grown.of(exact)).isEmpty(), "the explicit native special dish is genuinely plannable");
            }
            for (PotPlan.Mode mode : List.of(PotPlan.Mode.DARK, PotPlan.Mode.SUSPICIOUS)) {
                helper.assertTrue(rig.level.getRecipeManager().byKey(PotPlan.proceduralId(mode)).isEmpty(), "the private native protocol is not a fake recipe registration");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aNewMatchingRecipeRejectsAnUnspentProceduralPotLoad(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, PotPlan.Mode.SUSPICIOUS)) {
            rig.stockProcedural(true); Node load = ((Workload.Within.Turn.Piece) rig.work.next(rig.level, rig.worker)).node();
            withMatchingSeedRecipe(rig, () -> {
                helper.assertTrue(PotPlan.find(rig.level, rig.plan.id()).isEmpty(), "recipe reload removes the deliberate no-match option");
                helper.assertTrue(Commit.run(rig.level, load, rig.worker) instanceof Outcome.Failed, "the already planned load rechecks actual native recipe selection");
                helper.assertTrue(PotStation.empty(rig.pot) && !PotBatch.claimed(rig.pot), "the actual pot never accepts stale fallback inputs");
                helper.assertValueEqual(rig.count(Items.WHEAT_SEEDS) + rig.count(ModItems.OIL.get()) + rig.count(Items.BOWL), 3L, "all three real unspent items remain exactly once");
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aNewMatchingRecipeRejectsStartingButNeverRefundsAcceptedProceduralInputs(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, PotPlan.Mode.SUSPICIOUS)) {
            rig.stockProcedural(true); rig.step();
            Node start = ((Workload.Within.Turn.Piece) rig.work.next(rig.level, rig.worker)).node();
            withMatchingSeedRecipe(rig, () -> {
                helper.assertTrue(Commit.run(rig.level, start, rig.worker) instanceof Outcome.Failed, "the real start interaction also rechecks no-match after reload");
                helper.assertTrue(rig.pot.getStatus() == IPot.PUT_INGREDIENT && !rig.pot.isEmpty(), "accepted raw inputs remain physically in the native pot");
                helper.assertValueEqual(rig.count(Items.WHEAT_SEEDS) + rig.count(ModItems.OIL.get()), 0L, "already accepted seed and oil are never refunded");
                helper.assertValueEqual(rig.count(Items.BOWL), 1L, "only the unspent bowl remains in the real pack");
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1350)
    public static void cancelledReloadedDarkCuisineKeepsItsNativeModeAndSpendsNoSecondInput(GameTestHelper helper) {
        Rig rig = new Rig(helper, PotPlan.Mode.DARK); rig.stockProcedural(true); rig.start();
        rig.work.ended(rig.level, Ending.REVOKED); rig.claim.release(Ending.REVOKED);
        rig.replace(new PotBlockEntity(rig.cell, rig.pot.getBlockState()));
        withMatchingSeedRecipe(rig, () -> helper.assertTrue(PotBatch.load(rig.pot, rig.level, rig.colony).isPresent(),
            "a native batch already cooking survives later recipe reload without returning accepted inputs"));
        var recovery = rig.station.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {});
        helper.assertValueEqual(recovery.size(), 1, "one persisted procedural batch yields one recovery goal");
        rig.work = (PotWork) recovery.getFirst().nodes().getFirst();
        helper.assertTrue(rig.work.planned(rig.level).isEmpty(), "the recovered special batch acquires its kitchen turn");
        helper.assertTrue(rig.work.spec().needs().stream().allMatch(need -> Goods.matches(Items.BOWL.getDefaultInstance(), need.spec())), "recovery budgets only the unspent real bowl");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.pot.getStatus() == IPot.BURNT, "recovery waits for the actual native burnt state"))
            .thenExecute(() -> {
                try {
                    rig.finish(true);
                    helper.assertValueEqual(rig.count(FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE)), 1L, "saved dark mode is not changed into suspicious success");
                    rig.assertProceduralSpent();
                    helper.assertTrue(rig.bookings.free(rig.at), "native recovery releases its real turn");
                } finally { rig.close(); }
            }).thenSucceed();
    }

    private static void withMatchingSeedRecipe(Rig rig, Runnable check) {
        List<RecipeHolder<?>> saved = List.copyOf(rig.level.getRecipeManager().getRecipes());
        List<RecipeHolder<?>> changed = new ArrayList<>(saved);
        changed.add(new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("folkways", "test/matching_seed_pot"),
            new PotRecipe(200, 0, Ingredient.of(Items.BOWL), List.of(Ingredient.of(Items.WHEAT_SEEDS)), Items.COOKED_BEEF.getDefaultInstance())));
        try { rig.level.getRecipeManager().replaceRecipes(changed); check.run(); }
        finally { rig.level.getRecipeManager().replaceRecipes(saved); }
    }

    private static void afterCooking(Rig rig, Consumer<Rig> assertion) {
        rig.helper.startSequence().thenWaitUntil(rig::finished).thenExecute(() -> {
            try { assertion.accept(rig); } finally { rig.close(); }
        }).thenSucceed();
    }

    private static final class Rig implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos cell;
        final WorldPos at;
        final Stances stances;
        final UUID colony = UUID.randomUUID();
        final PotPlan plan;
        final KitchenBookings bookings = new KitchenBookings();
        final PotStation station = new PotStation();
        final SimpleContainer pack = new SimpleContainer(36);
        final ResidentEntity body;
        final Worker worker;
        final Hold claim;
        PotBlockEntity pot;
        PotWork work;

        Rig(GameTestHelper helper, String recipe, int runs) {
            this(helper, ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", recipe), runs);
        }
        Rig(GameTestHelper helper, PotPlan.Mode mode) { this(helper, PotPlan.proceduralId(mode), 1); }
        Rig(GameTestHelper helper, ResourceLocation recipe, int runs) {
            this.helper = helper;
            level = helper.getLevel();
            cell = helper.absolutePos(new BlockPos(1, 1, 1));
            at = WorldPos.of(level, cell);
            stances = Stances.at(level, cell.east());
            level.setBlockAndUpdate(cell.below(), Blocks.CAMPFIRE.defaultBlockState());
            level.setBlockAndUpdate(cell.east().below(), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(cell, ModBlocks.POT.get().defaultBlockState());
            pot = (PotBlockEntity) level.getBlockEntity(cell);
            pot.getPersistentData();
            body = PersonBody.RESIDENT.get().create(level);
            body.setNoAi(true);
            body.moveTo(cell.getX() + 1.5, cell.getY(), cell.getZ() + 0.5);
            level.addFreshEntity(body);
            ItemStack shovel = new ItemStack(ModItems.KITCHEN_SHOVEL.get());
            shovel.setDamageValue(17);
            shovel.set(DataComponents.CUSTOM_NAME, Component.literal("One actual kitchen shovel"));
            put(shovel);
            plan = PotPlan.find(level, recipe).orElseThrow();
            work = new PotWork(colony, at, stances, plan, runs, bookings, () -> { });
            claim = bookings.hold(at, work.id());
            helper.assertTrue(claim.take().isEmpty() && work.planned(level).isEmpty(), "the actual pot task acquires its exclusive turn");
            worker = new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return body; }
                public Container pack() { return pack; }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) { return Tools.from(stacks(), need).orElse(ItemStack.EMPTY); }
                public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new AssertionError("unexpected spill: " + stack); }
                public int rankOf(String perk) { return 0; }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }

        void put(ItemStack stack) { helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "fixture has real room for " + stack); }
        long count(Item item) { return Goods.countIn(pack, Goods.specOf(item.getDefaultInstance())); }
        List<ItemStack> stacks() { return IntStream.range(0, pack.getContainerSize()).mapToObj(pack::getItem).filter(stack -> !stack.isEmpty()).toList(); }
        void stockBeef(int beef, int oil) { put(new ItemStack(Items.BEEF, beef)); put(new ItemStack(ModItems.OIL.get(), oil)); }
        void stockEggTomato(boolean bowl) {
            put(new ItemStack(Items.EGG)); put(new ItemStack(ModItems.TOMATO.get())); put(new ItemStack(ModItems.OIL.get()));
            if (bowl) put(new ItemStack(Items.BOWL));
        }
        void stockProcedural(boolean bowl) {
            put(Items.WHEAT_SEEDS.getDefaultInstance()); put(ModItems.OIL.get().getDefaultInstance());
            if (bowl) put(Items.BOWL.getDefaultInstance());
        }
        void assertProceduralSpent() {
            helper.assertValueEqual(count(Items.WHEAT_SEEDS) + count(ModItems.OIL.get()) + count(Items.BOWL), 0L, "one actual seed, oil and bowl are consumed exactly once");
            helper.assertValueEqual(count(ModItems.KITCHEN_SHOVEL.get()), 1L, "the actual shared shovel remains singular");
            helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(2)).isEmpty(), "native serving creates no uncaptured duplicate or charcoal");
            helper.assertTrue(PotStation.empty(pot) && !PotBatch.claimed(pot), "the actual emptied pot has no remaining batch claim");
        }
        void start() {
            for (int step = 0; step < 8; step++) {
                step();
                if (pot.getStatus() == IPot.COOKING && PotBatch.stirs(pot, level) == 0) return;
            }
            throw new AssertionError("native pot did not start with all required real stirs");
        }
        boolean step() {
            var turn = work.next(level, worker);
            if (turn instanceof Workload.Within.Turn.Leave) return true;
            helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "native preparation/take-out does not stall: " + turn);
            Node piece = ((Workload.Within.Turn.Piece) turn).node();
            Outcome outcome = Commit.run(level, piece, worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "actual pot interaction succeeds: " + outcome);
            ((Outcome.Done) outcome).made().forEach(this::put);
            piece.released(level, worker, Ending.DONE);
            piece.ended(level, Ending.DONE);
            work.ended(level, piece, Ending.DONE);
            return false;
        }
        void finish(boolean success) {
            for (int step = 0; step < 8; step++) {
                if (step()) {
                    Outcome result = work.commit(level, worker);
                    helper.assertTrue(success ? result instanceof Outcome.Done : result instanceof Outcome.Failed,
                        "the original food goal has the correct actual result: " + result);
                    work.ended(level, success ? Ending.DONE : new Ending.Failed(work.id(), CookeryRefusal.STATION_CHANGED));
                    claim.release(success ? Ending.DONE : Ending.REVOKED);
                    return;
                }
            }
            throw new AssertionError("native pot did not finish");
        }
        void finished() {
            helper.assertTrue(pot.getStatus() == IPot.FINISHED, "the real block ticker cooks to FINISHED: status="
                + pot.getStatus() + ", remaining=" + pot.getCurrentTick());
        }
        void replace(PotBlockEntity replacement) {
            CompoundTag saved = pot.saveWithFullMetadata(level.registryAccess());
            replacement.loadWithComponents(saved, level.registryAccess());
            level.removeBlockEntity(cell);
            level.setBlockEntity(replacement);
            pot = replacement;
        }
        public void close() { claim.release(Ending.REVOKED); body.discard(); }
    }
}
