package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LivingEatingGameTests {

    @GameTest(template = "empty")
    public static void maidFoodQueryReceivesPersistedHungerAndMealUpdatesThroughEntityData(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, 6, new ItemStack(Items.BREAD, 3))) {
            meal.settle();
            var body = (io.github.izakyl.folkways.plugins.person.ResidentEntity) meal.body.mob();
            var observer = PersonBody.RESIDENT.get().create(helper.getLevel());
            helper.assertValueEqual(observer.foodLevel(), 20.0F, "before sync, a new resident is normally fed");
            meal.living.tick(helper.getLevel().getServer());
            var update = body.getEntityData().packDirty();
            helper.assertTrue(update != null, "the loaded hunger creates an entity tracking update");
            observer.getEntityData().assignValues(update);
            helper.assertValueEqual(observer.foodLevel(), 6.0F, "persisted Hunger reaches observers without book UI data");
            helper.assertValueEqual(io.github.izakyl.folkways.plugins.person.look.ResidentMolang.FOOD_LEVEL
                .value(observer, 0, 0, 0), 6.0, "the inclusive <= 6 expression threshold can activate");
            meal.eat();
            meal.living.tick(helper.getLevel().getServer());
            observer.getEntityData().assignValues(body.getEntityData().packDirty());
            helper.assertValueEqual(observer.foodLevel(), 11.0F, "eating bread updates the same synced hunger");
            helper.assertTrue(io.github.izakyl.folkways.plugins.person.look.ResidentMolang.FOOD_LEVEL
                .value(observer, 0, 0, 0) > 6, "the normal face returns after a meal");
            body.foodLevel(-5);
            helper.assertValueEqual(body.foodLevel(), 0.0F, "presentation hunger is clamped at zero");
            body.foodLevel(30);
            helper.assertValueEqual(body.foodLevel(), 20.0F, "presentation hunger is clamped at twenty");
        }
        helper.succeed();
    }
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LivingEatingGameTests.class);
    }

    @GameTest(template = "empty")
    public static void foodKeptAsPersonalEquipmentIsNeverAnEatingCandidateOrConsumedByAStaleMeal(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, 0, new ItemStack(Items.BREAD, 3), new ItemStack(Items.BREAD, 3))) {
            PersonalItems.mark(meal.body, meal.pack.getItem(0), "spare");
            ItemStack equipment = meal.pack.getItem(0).copy();
            meal.pack.setItem(1, ItemStack.EMPTY);
            helper.assertTrue(meal.living.urges(meal.worker, meal.colony.view(helper.getLevel())).stream()
                .noneMatch(urge -> urge.id().equals(LivingPresence.EAT)), "a hungry resident does not queue eating its personal spare equipment");
            helper.assertTrue(meal.action().commit(helper.getLevel(), meal.worker) instanceof Outcome.Failed,
                "an already queued pocket meal cannot select marked equipment");
            helper.assertTrue(EatAction.consume(helper.getLevel(), meal.worker, meal.living, meal.pack, 0) instanceof Outcome.Failed,
                "even a stale served-meal slot is rechecked before native consumption");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 0, "rejected equipment never nourishes the resident");
            meal.pack.setItem(1, new ItemStack(Items.BREAD, 3));
            meal.eat();
            helper.assertTrue(ItemStack.matches(meal.pack.getItem(0), equipment), "eating ordinary rations preserves all components and count of personal food equipment");
            helper.assertValueEqual(meal.pack.getItem(1).getCount(), 2, "the existing unmarked ration mechanism still consumes exactly one ordinary meal");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void safetyDefaultsOnForOldColoniesAndPersistsWhenDisabled(GameTestHelper helper) {
        var schema = new io.github.izakyl.folkways.front.api.Schema(
            List.of(LivingContent.FOOD_MODE, LivingContent.FOOD_SAFETY, LivingContent.FOOD));
        var migrated = ColonySettings.load(Reader.of(Writer.of().tag())).conformedTo(schema);
        helper.assertTrue(migrated.flag(LivingContent.FOOD_SAFETY.key()), "old colonies default to safe food");
        var disabled = migrated.with(LivingContent.FOOD_SAFETY.key(), new ColonySettings.Value.Flag(false));
        helper.assertFalse(ColonySettings.load(Reader.of(disabled.save())).conformedTo(schema)
            .flag(LivingContent.FOOD_SAFETY.key()), "disabled safety survives save/load");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disablingSafetyAllowsListedUnsafeFoodButStillHonorsTheList(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.ROTTEN_FLESH, 3), 0)) {
            helper.assertTrue(Fronts.of(meal.colony).settings(LivingContent.ID)
                .flag(LivingContent.FOOD_SAFETY.key()), "safety starts enabled");
            assertRefused(helper, meal);
            meal.rejectUnsafe(false);
            helper.assertTrue(Goods.matches(new ItemStack(Items.ROTTEN_FLESH),
                LivingContent.rations(meal.colony, meal.body).getFirst().spec()), "unsafe food can be reserved");
            meal.blacklist(true);
            helper.assertTrue(meal.action().commit(helper.getLevel(), meal.worker) instanceof Outcome.Failed,
                "disabling safety does not bypass the blacklist");
            meal.blacklist(false);
            meal.eat();
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 2, "one listed unsafe meal is consumed");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 4,
                "the unsafe meal nourishes the resident");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void enablingSafetyBeforeCommitRefusesAPreviouslySelectedUnsafeMeal(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.SPIDER_EYE, 3), 0)) {
            meal.rejectUnsafe(false);
            var action = meal.living.urges(meal.worker, meal.colony.view(helper.getLevel())).stream()
                .filter(urge -> urge.id().equals(LivingPresence.EAT)).findFirst().orElseThrow().take().get();
            meal.rejectUnsafe(true);
            helper.assertTrue(action.commit(helper.getLevel(), meal.worker) instanceof Outcome.Failed,
                "safety is rechecked when the action commits");
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "unsafe meal stays untouched");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void savedFoodSettingsKeepEmptyListsAndMigrateExistingMenus(GameTestHelper helper) {
        var schema = new io.github.izakyl.folkways.front.api.Schema(List.of(LivingContent.FOOD_MODE, LivingContent.FOOD));
        var bread = ItemFilter.item(net.minecraft.resources.ResourceLocation.withDefaultNamespace("bread"));
        var old = ColonySettings.empty().with(LivingContent.FOOD.key(), new ColonySettings.Value.Items(List.of(bread)));
        var migrated = ColonySettings.load(Reader.of(old.save())).conformedTo(schema);
        helper.assertTrue(migrated.choice(LivingContent.FOOD_MODE.key()).equals(LivingContent.WHITELIST),
            "existing menus gain whitelist mode");
        helper.assertTrue(migrated.entries(LivingContent.FOOD.key()).equals(List.of(bread)),
            "migration preserves the player's saved menu");
        for (var mode : List.of(LivingContent.WHITELIST, LivingContent.BLACKLIST)) {
            var empty = migrated.with(LivingContent.FOOD.key(), new ColonySettings.Value.Items(List.of()))
                .with(LivingContent.FOOD_MODE.key(), new ColonySettings.Value.Choice(mode));
            var loaded = ColonySettings.load(Reader.of(empty.save())).conformedTo(schema);
            helper.assertTrue(loaded.equals(empty), "an explicitly empty list survives reload in " + mode);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void farmersDelightDefaultsResolveWhenInstalledAndAreOptionalOtherwise(GameTestHelper helper) {
        var registry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
        var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "resident_food_farmers_delight"));
        boolean installed = registry.containsKey(net.minecraft.resources.ResourceLocation
            .fromNamespaceAndPath("farmersdelight", "cooked_rice"));
        var members = registry.getTag(tag).orElseThrow();
        helper.assertValueEqual(members.size(), installed ? 44 : 0,
            "all curated Farmer's Delight items resolve only when installed");
        for (var member : members) {
            var stack = member.value().getDefaultInstance();
            var food = stack.getFoodProperties(null);
            helper.assertTrue(FoodSafety.safe(stack, food) && food.nutrition() > 0,
                "every default Farmer's Delight ration is nourishing and safe: " + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(member.value()));
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void defaultWhitelistUsesCuratedReloadableFoodTags(GameTestHelper helper) {
        var settings = ColonySettings.byDefault(new io.github.izakyl.folkways.front.api.Schema(
            List.of(LivingContent.FOOD_MODE, LivingContent.FOOD)));
        helper.assertTrue(settings.choice(LivingContent.FOOD_MODE.key()).equals(LivingContent.WHITELIST),
            "new colonies start in whitelist mode");
        var food = FoodSafety.allowed(settings.items(LivingContent.FOOD.key()), false).orElseThrow();
        helper.assertTrue(Goods.matches(new ItemStack(Items.BREAD), food), "default food includes bread");
        helper.assertTrue(Goods.matches(new ItemStack(Items.COOKED_BEEF), food), "default food includes cooked meat");
        helper.assertFalse(Goods.matches(new ItemStack(Items.APPLE), food), "raw ingredients are opt-in");
        helper.assertFalse(Goods.matches(new ItemStack(Items.GOLDEN_APPLE), food), "valuable special food is opt-in");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void queryWhitelistControlsEatingAndRationPlanning(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, 0,
                new ItemStack(Items.APPLE, 3), new ItemStack(Items.BREAD, 3))) {
            meal.rules(ItemFilter.query("@minecraft bread").orElseThrow());
            var ration = LivingContent.rations(meal.colony, meal.body).getFirst();
            helper.assertTrue(Goods.members(ration.spec()).equals(java.util.Set.of(Items.BREAD)),
                "saved queries use the same matching rules in food planning");
            meal.eat();
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "unlisted safe food stays untouched");
            helper.assertValueEqual(meal.pack.getItem(1).getCount(), 2, "the listed food is eaten");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void blacklistQueriesExcludeFoodWithoutAllowingHarmfulMeals(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, 0,
                new ItemStack(Items.BREAD, 3), new ItemStack(Items.PUFFERFISH, 3), new ItemStack(Items.APPLE, 3))) {
            meal.rules(ItemFilter.query("@minecraft bread").orElseThrow());
            meal.blacklist(true);
            var ration = LivingContent.rations(meal.colony, meal.body).getFirst();
            helper.assertFalse(Goods.matches(new ItemStack(Items.BREAD), ration.spec()), "blacklisted food is not reserved");
            helper.assertFalse(Goods.matches(new ItemStack(Items.PUFFERFISH), ration.spec()), "safety still applies");
            helper.assertTrue(Goods.matches(new ItemStack(Items.APPLE), ration.spec()), "unlisted safe food is reserved");
            meal.eat();
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "blacklisted bread stays untouched");
            helper.assertValueEqual(meal.pack.getItem(1).getCount(), 3, "unsafe food stays untouched");
            helper.assertValueEqual(meal.pack.getItem(2).getCount(), 2, "unlisted safe food is eaten");
            var settings = (ColonySettings) Fronts.of(meal.colony).settings(LivingContent.ID);
            var loaded = ColonySettings.load(Reader.of(settings.save()));
            helper.assertTrue(loaded.equals(settings), "list mode and query rules survive save/load");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void emptyWhitelistRefusesFoodAndKeepsSettlerRequirement(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.BREAD, 3), 0)) {
            meal.rules();
            helper.assertTrue(LivingContent.rations(meal.colony, meal.body).isEmpty(), "empty whitelist reserves nothing");
            assertRefused(helper, meal);
            var need = meal.living.settlerNeeds(helper.getLevel().getServer()).stream()
                .filter(one -> one.kind() == LivingNeed.FOOD).findFirst().orElseThrow();
            helper.assertFalse(need.met(0), "empty whitelist cannot waive the food requirement");
            meal.blacklist(true);
            helper.assertFalse(LivingContent.rations(meal.colony, meal.body).isEmpty(), "empty blacklist allows safe food");
            meal.eat();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingListModeBeforeCommitRechecksTheSameRules(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.BREAD, 3), 0)) {
            var action = meal.living.urges(meal.worker, meal.colony.view(helper.getLevel())).stream()
                .filter(urge -> urge.id().equals(LivingPresence.EAT)).findFirst().orElseThrow().take().get();
            meal.blacklist(true);
            helper.assertTrue(action.commit(helper.getLevel(), meal.worker) instanceof Outcome.Failed,
                "switching to blacklist invalidates a previously selected meal");
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "newly forbidden food stays untouched");
            meal.blacklist(false);
            meal.eat();
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 2, "switching back keeps the same rules");
        }
        helper.succeed();
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
    public static void harmfulVanillaFoodsAreRefusedEvenWhenStarving(GameTestHelper helper) {
        for (var item : List.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.PUFFERFISH,
                Items.POISONOUS_POTATO, Items.CHICKEN)) {
            try (EatingFixture meal = new EatingFixture(helper, new ItemStack(item, 3), 0)) {
                assertRefused(helper, meal);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aMealWithAnyChanceOfAHarmfulEffectIsRefused(GameTestHelper helper) {
        ItemStack bread = new ItemStack(Items.BREAD, 3);
        bread.set(DataComponents.FOOD, new FoodProperties.Builder().nutrition(5)
            .effect(() -> new MobEffectInstance(MobEffects.REGENERATION, 100), 1.0F)
            .effect(() -> new MobEffectInstance(MobEffects.WITHER, 100), 0.01F).build());
        try (EatingFixture meal = new EatingFixture(helper, bread, 0)) {
            assertRefused(helper, meal);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void harmfulSuspiciousStewEffectsAreRefused(GameTestHelper helper) {
        ItemStack stew = new ItemStack(Items.SUSPICIOUS_STEW, 3);
        stew.set(DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(List.of(
            new SuspiciousStewEffects.Entry(MobEffects.REGENERATION, 100),
            new SuspiciousStewEffects.Entry(MobEffects.WITHER, 100))));
        try (EatingFixture meal = new EatingFixture(helper, stew, 0)) {
            assertRefused(helper, meal);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void beneficialSuspiciousStewStillFeedsAndReturnsItsBowl(GameTestHelper helper) {
        ItemStack stew = new ItemStack(Items.SUSPICIOUS_STEW);
        stew.set(DataComponents.SUSPICIOUS_STEW_EFFECTS, new SuspiciousStewEffects(List.of(
            new SuspiciousStewEffects.Entry(MobEffects.REGENERATION, 100))));
        try (EatingFixture meal = new EatingFixture(helper, stew, 0)) {
            Outcome.Done done = meal.eat();
            helper.assertTrue(meal.body.mob().hasEffect(MobEffects.REGENERATION), "beneficial effects apply");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 6,
                "safe stew nourishes the resident");
            helper.assertTrue(done.made().size() == 1 && done.made().getFirst().is(Items.BOWL),
                "safe stew returns its bowl");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unsafeFoodInTheFirstSlotDoesNotHideASafeMeal(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, 0,
                new ItemStack(Items.PUFFERFISH, 3), new ItemStack(Items.BREAD, 3))) {
            helper.assertTrue(meal.living.urges(meal.worker, meal.colony.view(helper.getLevel())).stream()
                .anyMatch(urge -> urge.id().equals(LivingPresence.EAT)), "the safe meal creates an eating urge");
            meal.eat();
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "unsafe food stays untouched");
            helper.assertValueEqual(meal.pack.getItem(1).getCount(), 2, "one safe meal is consumed");
            helper.assertTrue(meal.body.mob().getActiveEffects().isEmpty(), "no harmful effects apply");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 5,
                "only the safe meal nourishes the resident");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void foodSafetyIsRecheckedWhenTheEatingActionCommits(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.BREAD, 3), 0)) {
            var action = meal.living.urges(meal.worker, meal.colony.view(helper.getLevel())).stream()
                .filter(urge -> urge.id().equals(LivingPresence.EAT)).findFirst().orElseThrow().take().get();
            meal.pack.getItem(0).set(DataComponents.FOOD, new FoodProperties.Builder().nutrition(5)
                .effect(() -> new MobEffectInstance(MobEffects.POISON, 100), 1.0F).build());
            helper.assertTrue(action.commit(helper.getLevel(), meal.worker) instanceof Outcome.Failed,
                "a previously selected meal is refused if it becomes harmful");
            helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "changed food stays untouched");
            helper.assertTrue(meal.body.mob().getActiveEffects().isEmpty(), "the harmful use never runs");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 0,
                "the refused meal grants no nutrition");
        }
        helper.succeed();
    }

    private static void assertRefused(GameTestHelper helper, EatingFixture meal) {
        meal.body.mob().setHealth(1.0F);
        helper.assertFalse(meal.living.urges(meal.worker, meal.colony.view(helper.getLevel())).stream()
            .anyMatch(urge -> urge.id().equals(LivingPresence.EAT)), "unsafe food creates no eating urge");
        helper.assertTrue(meal.action().commit(helper.getLevel(), meal.worker) instanceof Outcome.Failed,
            "unsafe food is refused at consumption");
        helper.assertValueEqual(meal.pack.getItem(0).getCount(), 3, "unsafe food stays untouched");
        helper.assertTrue(meal.body.mob().isAlive() && meal.body.mob().getHealth() == 1.0F,
            "refusal protects a resident at one health point");
        helper.assertTrue(meal.body.mob().getActiveEffects().isEmpty(), "no food effects apply");
        helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 0,
            "refusal grants no nutrition");
    }

    @GameTest(template = "empty")
    public static void unsafeConfiguredFoodIsExcludedFromRationsAndDeliveries(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.BREAD), 0)) {
            meal.menu(Items.BREAD, Items.PUFFERFISH, Items.ROTTEN_FLESH, Items.SPIDER_EYE,
                Items.POISONOUS_POTATO, Items.CHICKEN);
            meal.settle();
            var ration = LivingContent.rations(meal.colony, meal.body).getFirst();
            helper.assertTrue(Goods.members(ration.spec()).equals(java.util.Set.of(Items.BREAD)),
                "only safe configured food is kept as a ration");
            helper.assertValueEqual(ration.count(), 12L, "three days use the safe meal's nutrition");
            var delivery = (Delivery) meal.living.goals(meal.colony.view(helper.getLevel())).getFirst().nodes().getFirst();
            helper.assertTrue(Goods.members(delivery.goods().spec()).equals(java.util.Set.of(Items.BREAD)),
                "ration delivery requests only safe configured food");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anUnsafeOnlyMenuDoesNotRequestOrReserveFood(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.PUFFERFISH), 0)) {
            meal.menu(Items.PUFFERFISH);
            meal.settle();
            helper.assertTrue(LivingContent.rations(meal.colony, meal.body).isEmpty(),
                "unsafe-only food is not reserved in a resident's pack");
            helper.assertTrue(meal.living.goals(meal.colony.view(helper.getLevel())).isEmpty(),
                "unsafe-only food is not requested from the pantry");
            var need = meal.living.settlerNeeds(helper.getLevel().getServer()).stream()
                .filter(one -> one.kind() == LivingNeed.FOOD).findFirst().orElseThrow();
            helper.assertValueEqual(need.have(), 0, "an unsafe-only menu still reports the missing food need");
            helper.assertFalse(need.met(0), "rejecting all configured food cannot waive a new settler's food need");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void harmfulEffectsOnCarriedFoodDoNotSuppressRestocking(GameTestHelper helper) {
        try (EatingFixture meal = new EatingFixture(helper, new ItemStack(Items.BREAD), 0)) {
            meal.menu(Items.BREAD);
            meal.settle();
            ItemStack poisonBread = new ItemStack(Items.BREAD, 64);
            poisonBread.set(DataComponents.FOOD, new FoodProperties.Builder().nutrition(5)
                .effect(() -> new MobEffectInstance(MobEffects.POISON, 100), 1.0F).build());
            meal.body.pack().setItem(0, poisonBread);
            var goals = meal.living.goals(meal.colony.view(helper.getLevel()));
            helper.assertValueEqual(goals.size(), 1, "unsafe carried food cannot count as a day's rations");
            var delivery = (Delivery) goals.getFirst().nodes().getFirst();
            helper.assertValueEqual(delivery.goods().count(), 12L, "all three days of safe food are still needed");
            helper.assertValueEqual(poisonBread.getCount(), 64, "ration planning leaves unsafe cargo untouched");
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
        hunger.load(io.github.izakyl.folkways.base.persist.Reader.of(
            Writer.of().integer("FoodLevel", 0).tag()));
        FoodProperties food = new FoodProperties.Builder().nutrition(24).build();
        helper.assertTrue(hunger.canEatMeal(food), "quality can raise a meal past twenty points");
        hunger.eat(food);
        helper.assertValueEqual(hunger.foodLevel(), Hunger.MAX_FOOD_LEVEL, "fullness is capped at twenty");
        helper.assertFalse(hunger.canEatMeal(food), "an oversized meal still waits while full");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void largeMealsAllowBoundedWasteButSmallPortionsStillFit(GameTestHelper helper) {
        Hunger hunger = new Hunger();
        for (int nutrition : new int[] {5, 9, 10, 14, 20, 24}) {
            var food = new FoodProperties.Builder().nutrition(nutrition).build();
            int effective = Math.min(20, nutrition);
            int threshold = 20 - effective + (effective >= 10 ? effective * 3 / 10 : 0);
            hunger.load(io.github.izakyl.folkways.base.persist.Reader.of(
                Writer.of().integer("FoodLevel", threshold).tag()));
            helper.assertTrue(hunger.canEatMeal(food), "meal fits at its waste allowance: " + nutrition);
            hunger.load(io.github.izakyl.folkways.base.persist.Reader.of(
                Writer.of().integer("FoodLevel", threshold + 1).tag()));
            helper.assertFalse(hunger.canEatMeal(food), "meal exceeding its waste allowance waits: " + nutrition);
        }
        var large = new ItemStack(Items.BREAD);
        large.set(net.minecraft.core.component.DataComponents.FOOD,
            new FoodProperties.Builder().nutrition(20).build());
        try (EatingFixture meal = new EatingFixture(helper, large, 6)) {
            meal.eat();
            helper.assertTrue(meal.pack.getItem(0).isEmpty(), "the large meal is consumed once");
            helper.assertValueEqual(meal.living.foodLevelOf(meal.body.id()).orElseThrow(), 20,
                "overflow nutrition is discarded and fullness remains capped");
        }
        helper.succeed();
    }

}
