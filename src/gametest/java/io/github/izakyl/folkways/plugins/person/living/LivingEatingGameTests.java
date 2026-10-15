package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.work.Outcome;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingEatingGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LivingEatingGameTests.class);
    }

    @GameTest(template = "empty")
    public static void breadIsConsumedOnceWithoutChangingTheHeldTool(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.BREAD, 3), 0)) {
            meal.body.mob().setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new ItemStack(Items.IRON_AXE));
            Outcome.Done done = meal.eat();
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 2, "one bread is consumed");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 5,
                "the resident's own belly receives bread nutrition once");
            helper.assertTrue(done.made().isEmpty(), "bread has no container");
            helper.assertTrue(meal.body.mob().getMainHandItem().is(Items.IRON_AXE), "eating preserves tools");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void vanillaStewReturnsExactlyOneBowl(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.MUSHROOM_STEW), 0)) {
            Outcome.Done done = meal.eat();
            helper.assertTrue(meal.pack.getItem(0).isEmpty(), "the stew is consumed");
            helper.assertTrue(done.made().size() == 1 && done.made().getFirst().is(Items.BOWL)
                && done.made().getFirst().getCount() == 1, "one bowl follows normal output stowing");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 6,
                "stew still feeds the resident");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void honeyRemovesPoisonAndReturnsOneBottleWithAFullPack(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.HONEY_BOTTLE, 3), 0)) {
            meal.body.mob().addEffect(new MobEffectInstance(MobEffects.POISON, 600));
            Outcome.Done done = meal.eat();
            helper.assertFalse(meal.body.mob().hasEffect(MobEffects.POISON), "the real honey use removes poison");
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 2, "one honey bottle is consumed");
            helper.assertTrue(done.made().size() == 1 && done.made().getFirst().is(Items.GLASS_BOTTLE)
                && done.made().getFirst().getCount() == 1, "a full pack cannot erase the returned bottle");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void goldenAppleAppliesItsRealFoodEffects(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.GOLDEN_APPLE), 0)) {
            meal.eat();
            helper.assertTrue(meal.body.mob().hasEffect(MobEffects.REGENERATION), "regeneration applies");
            helper.assertTrue(meal.body.mob().hasEffect(MobEffects.ABSORPTION), "absorption applies");
            helper.assertTrue(meal.pack.getItem(0).isEmpty(), "the apple is consumed once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aMealThatDoesNotFitIsLeftUntouched(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.BREAD, 3), 16)) {
            Outcome outcome = meal.action().commit(helper.getLevel(), meal.worker);
            helper.assertTrue(outcome instanceof Outcome.Failed, "a meal too large waits");
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "no food is consumed on refusal");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 16,
                "refusal does not feed the resident");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oversizedMealsCanFeedAnEmptyBelly(GameTestHelper helper) {
        Hunger hunger = new Hunger();
        hunger.load(io.github.izakyl.folkways.core.api.persist.Reader.of(
            Writer.of().integer("FoodLevel", 0).tag()));
        FoodProperties food = new FoodProperties.Builder().nutrition(24).build();
        helper.assertTrue(hunger.canEatWithoutWaste(food), "quality can raise a meal past twenty points");
        hunger.eat(food);
        helper.assertValueEqual(hunger.foodLevel(), Hunger.MAX_FOOD_LEVEL, "fullness is capped at twenty");
        helper.assertFalse(hunger.canEatWithoutWaste(food), "an oversized meal still waits while full");
        helper.succeed();
    }

}
