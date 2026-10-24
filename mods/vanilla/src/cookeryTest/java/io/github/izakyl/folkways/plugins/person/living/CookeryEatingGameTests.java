package io.github.izakyl.folkways.plugins.person.living;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModEffects;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.Quality;
import com.github.ysbbbbbb.kaleidoscopecookery.item.quality.QualityUtils;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.terms.Goods;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryEatingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CookeryEatingGameTests.class);
    }

    @GameTest(template = "empty")
    public static void defaultCookeryRationsContainPreparedMealsAndNoRawOrSpecialFoods(GameTestHelper helper) {
        var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("folkways", "resident_food_cookery"));
        var members = BuiltInRegistries.ITEM.getTag(tag).orElseThrow();
        helper.assertValueEqual(members.size(), 40, "the supported Cookery version resolves every curated meal");
        for (var member : members) {
            var stack = member.value().getDefaultInstance();
            var food = stack.getFoodProperties(null);
            helper.assertTrue(FoodSafety.safe(stack, food) && food.nutrition() > 0,
                "every default Cookery ration is nourishing and safe: " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(member.value()));
        }
        helper.assertTrue(new ItemStack(ModItems.COOKED_RICE.get()).is(tag), "rice is a default ration");
        helper.assertTrue(new ItemStack(ModItems.FRIED_EGG.get()).is(tag), "fried eggs are a default ration");
        helper.assertFalse(new ItemStack(ModItems.RED_CHILI.get()).is(tag), "raw chili is not a default ration");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void teaEggQualityChangesNutritionAndEffectDuration(GameTestHelper helper) {
        ItemStack eggs = new ItemStack(ModItems.TEA_EGG.get(), 3);
        QualityUtils.setQuality(eggs, Quality.SUPERB);
        try (EatingFixture meal = new EatingFixture(helper, eggs, 0)) {
            var expected = eggs.getFoodProperties(meal.body.mob());
            meal.eat();
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 2, "only one quality egg is consumed");
            helper.assertTrue(QualityUtils.getQuality(meal.pack.getItem(0)) == Quality.SUPERB,
                "the remaining stack preserves quality");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), expected.nutrition(),
                "nutrition uses the actual quality stack");
            helper.assertValueEqual(meal.body.mob().getEffect(ModEffects.SULFUR).getDuration(),
                expected.effects().getFirst().effect().getDuration(), "quality also changes effect duration");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void poorQualityMealCanBeChosenWhenItsBaseNutritionWouldNotFit(GameTestHelper helper) {
        ItemStack beef = new ItemStack(ModItems.BRAISED_BEEF.get(), 3);
        QualityUtils.setQuality(beef, Quality.POOR);
        try (EatingFixture meal = new EatingFixture(helper, beef, 14)) {
            helper.assertTrue(meal.living.urges(meal.worker, meal.colony.view(helper.getLevel())).stream()
                .anyMatch(urge -> urge.id().equals(LivingPresence.EAT)), "meal selection reads quality");
            Outcome.Done done = meal.eat();
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 17,
                "poor quality beef nourishes three points");
            helper.assertTrue(done.made().size() == 1 && count(done, Items.BOWL) == 1,
                "stacked bowl food returns exactly one bowl");
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 2, "two portions remain");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void superbMealWaitsUntilItsActualNutritionFits(GameTestHelper helper) {
        ItemStack eggs = new ItemStack(ModItems.TEA_EGG.get(), 3);
        QualityUtils.setQuality(eggs, Quality.SUPERB);
        try (EatingFixture meal = new EatingFixture(helper, eggs, 16)) {
            helper.assertTrue(meal.action().commit(helper.getLevel(), meal.worker) instanceof Outcome.Failed,
                "five quality nutrition points do not fit in a four-point gap");
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "refusal does not consume food");
            helper.assertFalse(meal.body.mob().hasEffect(ModEffects.SULFUR), "refusal does not grant effects");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cookedRiceUsesItsCraftingRemainderAsAnEatingContainer(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(ModItems.COOKED_RICE.get(), 3), 0)) {
            Outcome.Done done = meal.eat();
            helper.assertTrue(done.made().size() == 1 && count(done, Items.BOWL) == 1,
                "craftRemainder returns one bowl when usingConvertsTo is absent");
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 2, "rice is consumed once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void bambooTubeRiceDoesNotDuplicateItsExplicitReturnedContainer(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(ModItems.BAMBOO_TUBE_RICE.get()), 0)) {
            Outcome.Done done = meal.eat();
            helper.assertTrue(done.made().size() == 1 && count(done, Items.BAMBOO) == 1,
                "finishUsingItem's explicit container is preserved once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void blockFoodCollectsItsOwnByproductsAndLeavesOldGroundItemsAlone(GameTestHelper helper) {
        ItemStack pork = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "dongpo_pork")), 3);
        QualityUtils.setQuality(pork, Quality.POOR);
        try (EatingFixture meal = new EatingFixture(helper, pork, 0)) {
            ItemEntity old = new ItemEntity(helper.getLevel(), meal.body.mob().getX(), meal.body.mob().getY(),
                meal.body.mob().getZ(), new ItemStack(Items.BAMBOO, 7));
            helper.getLevel().addFreshEntity(old);
            try {
                Outcome.Done done = meal.eat();
                helper.assertValueEqual(count(done, Items.BOWL), 1, "one bowl is returned");
                helper.assertValueEqual(count(done, Items.BAMBOO), 1, "the bamboo byproduct enters stowing");
                helper.assertTrue(done.made().size() == 2, "both outputs are accounted for once");
                helper.assertTrue(old.isAlive() && old.getItem().getCount() == 7,
                    "eating cannot pick up unrelated ground items");
                helper.assertValueEqual(meal.pack.getItem(0).getCount(), 2, "only one block food is consumed");
            } finally {
                old.discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void superbTwentyFourPointDishCanFeedAnEmptyResident(GameTestHelper helper) {
        ItemStack pork = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "dongpo_pork")));
        QualityUtils.setQuality(pork, Quality.SUPERB);
        try (EatingFixture meal = new EatingFixture(helper, pork, 0)) {
            helper.assertValueEqual(pork.getFoodProperties(meal.body.mob()).nutrition(), 24,
                "the real quality dish exceeds the vanilla fullness cap");
            meal.eat();
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 20,
                "the oversized dish feeds an empty resident to full");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chiliIsRefusedBeforeItsSpecialEatingBehaviorCanKillAResident(GameTestHelper helper) {
        for (var item : List.of(ModItems.RED_CHILI.get(), ModItems.GREEN_CHILI.get())) {
            try (EatingFixture meal = new EatingFixture(helper, new ItemStack(item, 3), 0)) {
                helper.assertTrue(meal.pack.getItem(0).getFoodProperties(meal.body.mob()).effects().isEmpty(),
                    "chili damage is not declared as a food effect");
                meal.body.mob().setHealth(1.0F);
                helper.assertFalse(meal.living.urges(meal.worker, meal.colony.view(helper.getLevel())).stream()
                    .anyMatch(urge -> urge.id().equals(LivingPresence.EAT)), "chili creates no eating urge");
                helper.assertTrue(meal.action().commit(helper.getLevel(), meal.worker) instanceof Outcome.Failed,
                    "chili is refused before its damage callback");
                helper.assertTrue(meal.body.mob().isAlive() && meal.body.mob().getHealth() == 1.0F,
                    "refusing chili protects a resident at one health point");
                helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "chili stays untouched");
                helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 0,
                    "refusal grants no nutrition");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void chiliesAreNotReservedAsRationsWithSafeRice(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(ModItems.RED_CHILI.get()), 0)) {
            meal.menu(ModItems.RED_CHILI.get(), ModItems.GREEN_CHILI.get(), ModItems.COOKED_RICE.get());
            var ration = LivingContent.rations(meal.colony, meal.body).getFirst();
            helper.assertTrue(Goods.members(ration.spec()).equals(java.util.Set.of(ModItems.COOKED_RICE.get())),
                "the direct-damage food tag also filters configured rations");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void harmfulCookeryDishesAreRefusedIncludingQualityMeals(GameTestHelper helper) {
        List<ItemStack> dishes = new ArrayList<>();
        for (String id : List.of("dark_cuisine", "suspicious_stir_fry", "stargazy_pie")) {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", id));
            helper.assertFalse(item == Items.AIR, "the harmful Cookery dish is installed: " + id);
            dishes.add(new ItemStack(item, 3));
            if (!id.equals("stargazy_pie")) {
                continue;
            }
            for (Quality quality : List.of(Quality.POOR, Quality.SUPERB)) {
                ItemStack dish = new ItemStack(item, 3);
                QualityUtils.setQuality(dish, quality);
                dishes.add(dish);
            }
        }
        for (ItemStack dish : dishes) {
            Quality quality = QualityUtils.getQuality(dish);
            String description = dish.getItem() + " (" + quality + ")";
            try (EatingFixture meal = new EatingFixture(helper, dish, 0)) {
                helper.assertFalse(meal.living.urges(meal.worker, meal.colony.view(helper.getLevel())).stream()
                    .anyMatch(urge -> urge.id().equals(LivingPresence.EAT)), "no eating urge for " + description);
                helper.assertTrue(meal.action().commit(helper.getLevel(), meal.worker) instanceof Outcome.Failed,
                    "refused even when starving: " + description);
                helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "the dish stays untouched");
                helper.assertTrue(QualityUtils.getQuality(meal.pack.getItem(0)) == quality,
                    "refusal preserves the dish's quality");
                helper.assertTrue(meal.body.mob().getActiveEffects().isEmpty(), "no harmful effects apply");
                helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 0,
                    "refusal grants no nutrition");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anExceptionAfterConsumptionPreservesFoodOutputsWithoutRefundingTheDish(GameTestHelper helper) {
        ItemStack pork = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "dongpo_pork")), 3);
        QualityUtils.setQuality(pork, Quality.POOR);
        try (EatingFixture meal = new EatingFixture(helper, pork, 0)) {
            int[] attempts = {0};
            Consumer<VanillaGameEvent> listener = event -> {
                if (event.getCause() == meal.body.mob() && event.getVanillaEvent().equals(GameEvent.EAT)) {
                    attempts[0]++;
                    throw new IllegalStateException("test: food consumed before a game-event listener failed");
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, VanillaGameEvent.class, listener);
            try {
                Outcome.Done done = meal.eat();
                helper.assertValueEqual(meal.pack.getItem(0).getCount(), 2,
                    "an already consumed dish cannot be refunded");
                helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 6,
                    "the completed consumption still nourishes the resident");
                helper.assertValueEqual(count(done, Items.BOWL), 1, "the consumption container is preserved");
                helper.assertValueEqual(count(done, Items.BAMBOO), 1, "the captured byproduct is preserved");
                helper.assertValueEqual(attempts[0], 1, "the real food interaction is never replayed");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anExceptionBeforeConsumptionReturnsTheFoodAndDiscardsCanceledByproducts(GameTestHelper helper) {
        ItemStack pork = new ItemStack(BuiltInRegistries.ITEM.get(
            ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "dongpo_pork")), 3);
        QualityUtils.setQuality(pork, Quality.POOR);
        try (EatingFixture meal = new EatingFixture(helper, pork, 0)) {
            Consumer<EntityJoinLevelEvent> listener = event -> {
                if (event.getLevel() == helper.getLevel() && event.getEntity() instanceof ItemEntity item
                        && item.getItem().is(Items.BAMBOO)
                        && meal.body.mob().getBoundingBox().inflate(1).contains(item.position())) {
                    throw new IllegalStateException("test: a byproduct appeared before food consumption");
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, EntityJoinLevelEvent.class, listener);
            try {
                Outcome outcome = meal.action().commit(helper.getLevel(), meal.worker);
                helper.assertTrue(outcome instanceof Outcome.Failed, "the unconsumed interaction fails");
                helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "the actual unconsumed bite is returned");
                helper.assertTrue(QualityUtils.getQuality(meal.pack.getItem(0)) == Quality.POOR,
                    "returning the bite preserves its quality");
                helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 0,
                    "a failed pre-consumption use grants no nutrition");
                helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    meal.body.mob().getBoundingBox().inflate(1)).isEmpty(),
                    "canceled byproducts cannot coexist with the refunded food");
            } finally {
                NeoForge.EVENT_BUS.unregister(listener);
            }
        }
        helper.succeed();
    }

    private static int count(Outcome.Done done, net.minecraft.world.item.Item item) {
        return done.made().stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

}
