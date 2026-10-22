package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiningGameTests {
    // Isolate the scheduler contract from optional furniture mods. The zone, world and resident are real.
    static final Map<WorldPos, Container> DISPLAYS = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        DiningTables.register(ResourceLocation.fromNamespaceAndPath("folkways", "test_dining_display"), new DiningTables.Table() {
            public boolean matches(ServerLevel level, BlockPos at) {
                return level.getBlockState(at).is(Blocks.GOLD_BLOCK) && DISPLAYS.containsKey(WorldPos.of(level, at));
            }
            public Container inventory(ServerLevel level, BlockPos at) { return DISPLAYS.get(WorldPos.of(level, at)); }
        });
        DiningDrinks.register(ResourceLocation.fromNamespaceAndPath("folkways", "test_table_drink"), new DiningDrinks.Drink() {
            public boolean matches(ItemStack stack) { return stack.is(Items.POTION); }
            public ItemStack finishUsing(ServerLevel level, Mob diner, ItemStack serving) {
                serving.shrink(1);
                diner.spawnAtLocation(new ItemStack(Items.GLASS_BOTTLE));
                return ItemStack.EMPTY;
            }
        });
        DiningDrinks.register(ResourceLocation.fromNamespaceAndPath("folkways", "test_unconsumed_drink"), new DiningDrinks.Drink() {
            public boolean matches(ItemStack stack) { return stack.is(Items.SPLASH_POTION); }
            public ItemStack finishUsing(ServerLevel level, Mob diner, ItemStack serving) {
                diner.spawnAtLocation(new ItemStack(Items.GLASS_BOTTLE));
                return new ItemStack(Items.GLASS_BOTTLE);
            }
        });
        DiningDrinks.registerSupply(ResourceLocation.fromNamespaceAndPath("folkways", "test_drink_cabinet"), new DiningDrinks.Supply() {
            public boolean matches(ServerLevel level, BlockPos at) { return level.getBlockState(at).is(Blocks.BARREL); }
            public Container inventory(ServerLevel level, BlockPos at) { return (Container) level.getBlockEntity(at); }
        });
        event.register(DiningGameTests.class);
    }

    @GameTest(template = "empty")
    public static void diningNeedsSelectedFoodAndAdjacentAccessibleFurniture(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(null);
            helper.assertTrue(f.goals().isEmpty(), "no food choice means no restocking");
            f.zone(Items.BREAD);
            helper.assertValueEqual(f.goals().size(), 2, "two native display slots are requested");
            f.level.setBlockAndUpdate(f.chair, Blocks.AIR.defaultBlockState());
            f.dining.refresh(f.level.getServer());
            helper.assertTrue(f.goals().isEmpty(), "a table without a chair creates no work");
            f.level.setBlockAndUpdate(f.chair, Blocks.OAK_STAIRS.defaultBlockState());
            f.level.setBlockAndUpdate(f.at, Blocks.AIR.defaultBlockState());
            f.dining.refresh(f.level.getServer());
            helper.assertTrue(f.goals().isEmpty(), "a chair without a table creates no work");
            f.level.setBlockAndUpdate(f.at, Blocks.GOLD_BLOCK.defaultBlockState());
            for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                if (!f.chair.relative(direction).equals(f.at)) {
                    f.level.setBlockAndUpdate(f.chair.relative(direction), Blocks.STONE.defaultBlockState());
                }
            }
            f.dining.refresh(f.level.getServer());
            helper.assertTrue(f.goals().isEmpty(), "a chair needs a walkable aisle");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void multiFoodMenuKeepsRequestsStableAndChoosesFreshDishesAfterDelivery(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD, Items.COOKED_BEEF);
            var seen = new java.util.HashSet<io.github.izakyl.folkways.core.api.terms.ItemSpec>();
            for (int serving = 0; serving < 32; serving++) {
                var goals = f.goals();
                var ids = goals.stream().map(node -> node.spec().id()).toList();
                var dishes = goals.stream().map(node -> node.spec().needs().getFirst().spec()).toList();
                helper.assertTrue(dishes.stream().allMatch(spec -> spec.item().isPresent()),
                    "each empty slot requests one concrete dish, rather than leaving cheapest-food selection to the planner");
                f.dining.refresh(f.level.getServer());
                var refreshed = f.goals();
                helper.assertValueEqual(refreshed.stream().map(node -> node.spec().id()).toList(), ids,
                    "surveying furniture keeps in-flight production and hauling node identities");
                helper.assertValueEqual(refreshed.stream().map(node -> node.spec().needs().getFirst().spec()).toList(), dishes,
                    "replanning keeps the same dish until its serving arrives");
                var dish = dishes.getFirst();
                seen.add(dish);
                var item = BuiltInRegistries.ITEM.get(dish.item().orElseThrow());
                f.worker.supplied = List.of(new ItemStack(item));
                helper.assertTrue(goals.getFirst().commit(f.level, f.worker) instanceof Outcome.Done,
                    "the selected dish can be delivered through the normal node");
                helper.assertTrue(f.display.getItem(0).is(item), "the requested real dish reaches the physical slot");
                helper.assertValueEqual(f.goals().size(), 1, "a filled slot does not request another serving");
                f.display.setItem(0, ItemStack.EMPTY);
            }
            helper.assertValueEqual(seen.size(), 2, "successive completed servings use both foods from the menu");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacingMenuReturnsOldSelectedDishAndRequestsTheNewFood(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD, Items.COOKED_BEEF);
            var old = f.goals().getFirst();
            var item = BuiltInRegistries.ITEM.get(old.spec().needs().getFirst().spec().item().orElseThrow());
            f.zone(Items.CARROT);
            f.worker.supplied = List.of(new ItemStack(item));
            var cancelled = (Outcome.Done) old.commit(f.level, f.worker);
            helper.assertValueEqual(cancelled.unused().stream().mapToInt(ItemStack::getCount).sum(), 1,
                "replacing a menu returns the old requested dish without placing it");
            helper.assertTrue(f.display.isEmpty(), "the cancelled request cannot fill the new menu");
            helper.assertTrue(f.goals().stream().allMatch(node -> node.spec().needs().getFirst().spec().item()
                .filter(BuiltInRegistries.ITEM.getKey(Items.CARROT)::equals).isPresent()),
                "new requests use the replacement menu exclusively");
            f.front.erase(f.zone);
            f.dining.refresh(f.level.getServer());
            helper.assertTrue(f.goals().isEmpty(), "removing the dining zone removes its pending requests");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void floorOutlineFindsFurnitureAboveItAndRestocksTheTable(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            ColonySettings chosen = f.front.zone(f.zone).orElseThrow().settings();
            f.front.erase(f.zone);
            f.zone = f.front.addZone(f.level, LivingContent.DINING,
                java.util.Set.of(f.at.below(), f.chair.below()), chosen).orElseThrow().id();
            f.dining.refresh(f.level.getServer());
            helper.assertValueEqual(f.goals().size(), 2, "floor cells discover the table and adjacent chair above them");
            f.worker.supplied = List.of(new ItemStack(Items.BREAD));
            helper.assertTrue(f.goals().getFirst().commit(f.level, f.worker) instanceof Outcome.Done,
                "furniture above the marked floor accepts delivery");
            helper.assertTrue(f.display.getItem(0).is(Items.BREAD), "one serving reaches the discovered table");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void restockingPreservesComponentsAndReturnsAllUnusedFood(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            ItemStack bread = new ItemStack(Items.BREAD, 3);
            bread.set(DataComponents.CUSTOM_NAME, Component.literal("Sunday loaf"));
            f.worker.supplied = List.of(bread);
            Outcome.Done result = (Outcome.Done) f.goals().getFirst().commit(f.level, f.worker);
            helper.assertValueEqual(f.display.getItem(0).getCount(), 1, "only one serving is placed");
            helper.assertTrue(ItemStack.isSameItemSameComponents(f.display.getItem(0), bread), "display retains components");
            helper.assertValueEqual(result.unused().getFirst().getCount(), 2, "other supplied servings return to the pack");
            helper.assertValueEqual(bread.getCount(), 3, "the handed-over snapshot is not mutated");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void staleRestockingDoesNotReplacePlayerFoodOrSpendCancelledZoneSupplies(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            Node pending = f.goals().getFirst();
            f.worker.supplied = List.of(new ItemStack(Items.BREAD, 2));
            ItemStack playerFood = new ItemStack(Items.APPLE);
            playerFood.set(DataComponents.CUSTOM_NAME, Component.literal("For a friend"));
            f.display.setItem(0, playerFood.copy());
            Outcome.Done blocked = (Outcome.Done) pending.commit(f.level, f.worker);
            helper.assertTrue(ItemStack.matches(f.display.getItem(0), playerFood), "manually placed food is untouched");
            helper.assertValueEqual(blocked.unused().getFirst().getCount(), 2, "occupied display returns every supply");
            f.display.clearContent();
            Node excessSlot = f.goals().get(1);
            ColonySettings chosen = f.front.zone(f.zone).orElseThrow().settings();
            f.front.setDrawn(f.zone, chosen.with(LivingContent.DINING_SERVINGS.key(), new ColonySettings.Value.Count(1)));
            Outcome.Done reduced = (Outcome.Done) excessSlot.commit(f.level, f.worker);
            helper.assertTrue(f.display.isEmpty(), "an old second-slot task cannot exceed the new one-serving target");
            helper.assertValueEqual(reduced.unused().getFirst().getCount(), 2, "lowering the target returns every supply");
            f.dining.refresh(f.level.getServer());
            pending = f.goals().getFirst();
            f.front.erase(f.zone);
            Outcome.Done cancelled = (Outcome.Done) pending.commit(f.level, f.worker);
            helper.assertTrue(f.display.isEmpty(), "deleted dining zone is not replenished");
            helper.assertValueEqual(cancelled.unused().getFirst().getCount(), 2, "deleted zone returns every supply");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void tableMealConsumesOnceNourishesAndReturnsTheNativeBowl(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.MUSHROOM_STEW);
            f.display.setItem(0, new ItemStack(Items.MUSHROOM_STEW));
            Node meal = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            Outcome.Done eaten = (Outcome.Done) meal.commit(f.level, f.worker);
            helper.assertTrue(f.display.isEmpty(), "the table serving was consumed");
            helper.assertValueEqual(f.living.foodLevelOf(f.person.getUUID()).orElseThrow(), 10, "one stew restores six food points");
            helper.assertValueEqual(eaten.made().stream().filter(item -> item.is(Items.BOWL)).mapToInt(ItemStack::getCount).sum(), 1,
                "the native interaction produces exactly one bowl");
            helper.assertTrue(f.living.mood(f.person.getUUID()).dined == f.level.getGameTime(), "dining records its own mood experience");
            helper.assertTrue(meal.commit(f.level, f.worker) instanceof Outcome.Failed, "replaying a finished meal cannot consume again");
            helper.assertValueEqual(f.living.foodLevelOf(f.person.getUUID()).orElseThrow(), 10, "replay grants no extra nourishment");
            LivingPresence loaded = new LivingPresence(f.colony);
            helper.assertTrue(loaded.mood(f.person.getUUID()).dined == f.level.getGameTime(), "the dining experience persists with the colony");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancellingMealLeavesFoodAndReleasesTheSeatAndServing(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            f.display.setItem(0, new ItemStack(Items.BREAD));
            Node first = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            first.spec().gesture().perform(f.person, 0, f.at);
            helper.assertValueEqual(f.person.leisureMotion(), "dining", "meal uses a seated eating pose");
            helper.assertTrue(f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false)
                .orElseThrow().take().get() == first, "the diner keeps the same reserved meal offered throughout its windup");
            first.released(f.level, f.worker, Ending.DROPPED);
            helper.assertValueEqual(f.display.getItem(0).getCount(), 1, "cancellation does not eat the serving");
            helper.assertValueEqual(f.living.foodLevelOf(f.person.getUUID()).orElseThrow(), 4, "cancellation adds no food");
            helper.assertValueEqual(f.person.leisureMotion(), "", "cancellation clears the pose");
            Node next = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            next.ended(f.level, Ending.DROPPED);
            helper.assertTrue(f.living.mood(f.person.getUUID()).dined < 0, "cancelled meals grant no dining mood");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void configuredLongMealConsumesOnlyAtCompletionAndStillCancelsCleanly(GameTestHelper helper) {
        var setting = (net.neoforged.neoforge.common.ModConfigSpec.IntValue)
            io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig.SPEC.getValues().get("living.diningMealTicks");
        int before = setting.get();
        try {
            setting.set(400);
            try (Fixture f = new Fixture(helper)) {
                f.zone(Items.BREAD);
                f.display.setItem(0, new ItemStack(Items.BREAD));
                Node first = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
                helper.assertValueEqual(((Workload.Once) first.spec().workload()).baseTicks(), 400,
                    "the real meal workload uses the configured twenty-second duration");
                for (int tick : List.of(0, 199, 399)) {
                    first.spec().gesture().perform(f.person, tick, f.at);
                    helper.assertValueEqual(f.display.getItem(0).getCount(), 1, "the eating gesture leaves its serving on the table");
                    helper.assertValueEqual(f.living.foodLevelOf(f.person.getUUID()).orElseThrow(), 4,
                        "a partially completed long meal grants no nourishment");
                }
                first.released(f.level, f.worker, Ending.DROPPED);
                helper.assertValueEqual(f.display.getItem(0).getCount(), 1, "cancelling the long meal preserves its serving");
                helper.assertValueEqual(f.person.leisureMotion(), "", "cancellation releases the long eating pose");
                Node retry = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
                helper.assertTrue(retry != first, "cancellation releases the actual seat and portion for a fresh reservation");
                helper.assertValueEqual(((Workload.Once) retry.spec().workload()).baseTicks(), 400,
                    "the replacement meal uses the same configured duration");
                retry.spec().gesture().perform(f.person, 0, f.at);
                helper.assertTrue(retry.commit(f.level, f.worker) instanceof Outcome.Done, "finishing the replacement meal commits normally");
                helper.assertTrue(f.display.isEmpty(), "completion consumes exactly the reserved serving");
                helper.assertValueEqual(f.living.foodLevelOf(f.person.getUUID()).orElseThrow(), 9,
                    "a completed bread serving restores its real five nutrition points");
                helper.assertValueEqual(f.person.leisureMotion(), "", "completion releases the dining pose immediately");
            }
        } finally {
            setting.set(before);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void teleportingAwayFromAnEngagedMealDoesNotEatAndReleasesReservations(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            f.display.setItem(0, new ItemStack(Items.BREAD));
            Node meal = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            meal.spec().gesture().perform(f.person, 0, f.at);
            helper.assertValueEqual(f.person.leisureMotion(), "dining", "the meal has actually started before teleporting");
            double x = f.person.getX(), y = f.person.getY(), z = f.person.getZ();
            f.person.teleportTo(x + 20, y, z);
            helper.assertTrue(meal.commit(f.level, f.worker) instanceof Outcome.Failed, "a distant resident cannot remotely finish the meal");
            helper.assertValueEqual(f.display.getItem(0).getCount(), 1, "teleport cancellation leaves table food untouched");
            helper.assertValueEqual(f.living.foodLevelOf(f.person.getUUID()).orElseThrow(), 4, "no remote nourishment is granted");
            helper.assertTrue(f.living.mood(f.person.getUUID()).dined < 0, "no remote dining mood is granted");
            helper.assertValueEqual(f.person.leisureMotion(), "", "teleport cancellation clears the pose");
            f.person.teleportTo(x, y, z);
            Node retry = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            helper.assertValueEqual(retry.spec().doing(), LivingContent.DINING_EATING, "returning can reserve the same seat and serving again");
            retry.ended(f.level, Ending.DROPPED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingTableFoodFallsBackToPocketRations(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            f.person.pack().setItem(0, new ItemStack(Items.BREAD, 2));
            Node meal = f.living.urges(f.worker, f.colony.view(f.level)).stream()
                .filter(urge -> urge.id().equals(LivingPresence.EAT)).findFirst().orElseThrow().take().get();
            helper.assertTrue(meal instanceof EatAction, "an empty table does not block carried food");
            helper.assertTrue(meal.commit(f.level, f.worker) instanceof Outcome.Done, "the pocket ration can be eaten");
            helper.assertValueEqual(f.person.pack().getItem(0).getCount(), 1, "one pocket ration is eaten");
            helper.assertTrue(f.living.mood(f.person.getUUID()).dined < 0, "pocket meals grant no table dining bonus");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void diningMoodSurvivesReloadAndExpiresAfterOneDay(GameTestHelper helper) {
        Mood mood = new Mood();
        mood.dined = 100;
        Mood reloaded = new Mood();
        reloaded.load(io.github.izakyl.folkways.base.persist.Reader.of(mood.save()));
        helper.assertTrue(reloaded.dined == 100, "dining timestamp survives reload");
        reloaded.step(100 + Mood.DAY - 1, 20, 1200, 0, false, false);
        helper.assertValueEqual(reloaded.reasons.get("dining"), 6, "dining contributes six mood points for a day");
        reloaded.step(100 + Mood.DAY, 20, 1200, 0, false, false);
        helper.assertValueEqual(reloaded.reasons.get("dining"), 0, "the dining bonus expires at one day");
        Mood oldSave = new Mood();
        oldSave.load(io.github.izakyl.folkways.base.persist.Reader.of(Writer.of().blob("times", new net.minecraft.nbt.CompoundTag()).tag()));
        oldSave.step(0, 20, 1200, 0, false, false);
        helper.assertValueEqual(oldSave.reasons.get("dining"), 0, "old saves have no invented dining experience");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void connectedBasicTablesShareTheActualCakeAtTheirFarEnd(GameTestHelper helper) {
        var slab = Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
            net.minecraft.world.level.block.state.properties.SlabType.TOP);
        try (Fixture f = new Fixture(helper, slab)) {
            f.zone(Items.CAKE);
            var settings = f.front.zone(f.zone).orElseThrow().settings();
            f.front.erase(f.zone);
            var cells = new java.util.HashSet<BlockPos>();
            cells.add(f.chair);
            for (int x = 0; x < 8; x++) {
                BlockPos at = f.at.east(x);
                f.level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
                f.level.setBlockAndUpdate(at, slab);
                cells.add(at);
            }
            f.zone = f.front.addZone(f.level, LivingContent.DINING, cells, settings).orElseThrow().id();
            f.dining.refresh(f.level.getServer());
            var far = f.at.east(7);
            Node supply = f.goals().stream().filter(node -> node.spec().focus().filter(far::equals).isPresent()).findFirst().orElseThrow();
            f.worker.supplied = List.of(new ItemStack(Items.CAKE));
            helper.assertTrue(supply.commit(f.level, f.worker) instanceof Outcome.Done, "one end places food at the opposite end");
            helper.assertTrue(f.level.getBlockState(far.above()).is(Blocks.CAKE), "food remains an actual distant world block");
            Node meal = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            helper.assertTrue(meal.commit(f.level, f.worker) instanceof Outcome.Done, "the only near-end seat shares the remote cake");
            helper.assertValueEqual(f.level.getBlockState(far.above()).getValue(net.minecraft.world.level.block.CakeBlock.BITES), 1,
                "one shared meal advances the native cake by exactly one bite");
            helper.assertTrue(f.level.getBlockState(f.at.above()).isAir(), "no duplicate cake appears beside the diner");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void tableDrinksUseTheirOwnSlotAndSurviveCanceledMeals(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            f.drinks(Items.POTION);
            var goals = f.goals();
            helper.assertValueEqual(goals.size(), 2, "the two-slot test table has one food slot and one drink slot");
            for (var goal : goals) {
                boolean drink = goal.spec().needs().getFirst().spec().item().orElseThrow().equals(ResourceLocation.withDefaultNamespace("potion"));
                f.worker.supplied = List.of(new ItemStack(drink ? Items.POTION : Items.BREAD));
                helper.assertTrue(goal.commit(f.level, f.worker) instanceof Outcome.Done, "both food and non-food drink are really delivered");
            }
            helper.assertTrue(f.display.getItem(0).is(Items.BREAD), "food remains in the first slot");
            helper.assertTrue(f.display.getItem(1).is(Items.POTION), "non-nutritious drink occupies its reserved last slot");
            Node canceled = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            canceled.ended(f.level, Ending.DROPPED);
            helper.assertTrue(f.display.getItem(1).is(Items.POTION), "canceling a meal does not consume its reserved drink");
            helper.assertValueEqual(f.person.getPersistentData().getInt("folkways:table_drinks"), 0, "canceled drinking has no completion history");
            Node meal = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            Outcome result = meal.commit(f.level, f.worker);
            helper.assertTrue(result instanceof Outcome.Done, "the real table meal completes");
            helper.assertTrue(f.display.isEmpty(), "exactly one food and one drink leave the table");
            var done = (Outcome.Done) result;
            helper.assertValueEqual(done.made().stream().filter(stack -> stack.is(Items.GLASS_BOTTLE)).mapToInt(ItemStack::getCount).sum(), 1,
                "the native dropped empty bottle is returned exactly once through the work outcome");
            helper.assertValueEqual(f.person.getPersistentData().getInt("folkways:table_drinks"), 1, "only actual consumption advances the drink history");
            helper.assertValueEqual(f.living.foodLevelOf(f.person.getUUID()).orElseThrow(), 9, "only bread adds nutrition; a drink is not a meal");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unconsumedDrinkAdapterCannotPublishBottlesOrHistory(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.display.setItem(1, new ItemStack(Items.SPLASH_POTION));
            var left = DiningDrinks.consume(f.level, f.person, f.display, 1);
            helper.assertTrue(left.isEmpty(), "an adapter that did not consume the drink cannot return or spawn bottles");
            helper.assertTrue(f.display.getItem(1).is(Items.SPLASH_POTION), "the unconsumed drink returns to its original table slot");
            helper.assertValueEqual(f.person.getPersistentData().getInt("folkways:table_drinks"), 0, "no fictitious drinking history is recorded");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void replacedTableDrinkIsLeftForThePlayer(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            f.drinks(Items.POTION);
            f.display.setItem(0, new ItemStack(Items.BREAD));
            f.display.setItem(1, new ItemStack(Items.POTION));
            Node meal = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            f.display.setItem(1, new ItemStack(Items.SPLASH_POTION));
            var result = (Outcome.Done) meal.commit(f.level, f.worker);
            helper.assertTrue(f.display.getItem(1).is(Items.SPLASH_POTION), "replacing the reserved bottle prevents drinking the player's new bottle");
            helper.assertTrue(result.made().isEmpty(), "the replaced bottle creates no empty container");
            helper.assertValueEqual(f.person.getPersistentData().getInt("folkways:table_drinks"), 0, "a skipped drink has no completed history");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void drinkCabinetsOnlyServeTheirMarkedDiningZoneAndRetractOnRemoval(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            BlockPos cabinet = f.at.west();
            f.level.setBlockAndUpdate(cabinet, Blocks.BARREL.defaultBlockState());
            Container stock = (Container) f.level.getBlockEntity(cabinet);
            for (int slot = 1; slot < stock.getContainerSize(); slot++) { stock.setItem(slot, new ItemStack(Items.STONE, 64)); }
            var cells = java.util.Set.of(f.at, f.chair, cabinet);
            var settings = f.front.zone(f.zone).orElseThrow().settings();
            f.front.erase(f.zone);
            f.zone = f.front.addZone(f.level, LivingContent.DINING, cells, settings).orElseThrow().id();
            f.dining.refresh(f.level.getServer());
            helper.assertFalse(f.colony.storesIn(f.level.dimension()).contains(WorldPos.of(f.level, cabinet)), "without a drink menu a cabinet is never automatically offered");
            f.drinks(Items.POTION);
            helper.assertFalse(f.colony.storesIn(f.level.dimension()).contains(WorldPos.of(f.level, cabinet)), "a cabinet outside the selected zone is not adopted");
            settings = f.front.zone(f.zone).orElseThrow().settings();
            f.front.erase(f.zone);
            f.zone = f.front.addZone(f.level, LivingContent.DINING, cells, settings).orElseThrow().id();
            f.dining.refresh(f.level.getServer());
            helper.assertTrue(f.colony.storesIn(f.level.dimension()).contains(WorldPos.of(f.level, cabinet)), "the marked cabinet exposes its real inventory as colony stock");
            Node refill = f.goals().stream().filter(node -> node.spec().focus().filter(cabinet::equals).isPresent()).findFirst().orElseThrow();
            helper.assertFalse(refill.acceptsInput(new io.github.izakyl.folkways.core.api.terms.Stash(WorldPos.of(f.level, cabinet))), "a refill cannot empty the same cabinet into itself");
            f.person.moveTo(cabinet.getX()+0.5, cabinet.getY(), cabinet.getZ()+1.5);
            f.worker.supplied = List.of(new ItemStack(Items.POTION));
            var result = (Outcome.Done) refill.commit(f.level, f.worker);
            helper.assertTrue(stock.getItem(0).is(Items.POTION), "a real supplied bottle reaches the real cabinet slot");
            helper.assertTrue(result.unused().isEmpty(), "the bottle is consumed once by cabinet delivery");
            f.front.erase(f.zone); f.zone = null;
            f.dining.refresh(f.level.getServer());
            helper.assertFalse(f.colony.storesIn(f.level.dimension()).contains(WorldPos.of(f.level, cabinet)), "removing the dining zone retracts the automatic store");
            helper.assertTrue(stock.getItem(0).is(Items.POTION), "retracting a cabinet leaves its native bottles intact");
        }
        helper.succeed();
    }

    static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ColonyFront front;
        final BlockPos at;
        final BlockPos chair;
        final Container display;
        final ResidentEntity person;
        final TestWorker worker;
        final LivingPresence living;
        final Dining dining;
        UUID zone;

        Fixture(GameTestHelper helper) { this(helper, Blocks.GOLD_BLOCK.defaultBlockState()); }

        Fixture(GameTestHelper helper, net.minecraft.world.level.block.state.BlockState tableBlock) {
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            front = ColonyFront.of(colony);
            at = helper.absolutePos(new BlockPos(4, 2, 4));
            chair = at.south();
            for (BlockPos floor : BlockPos.betweenClosed(at.offset(-2, -1, -2), at.offset(2, -1, 3))) {
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(floor.above(2), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(at, tableBlock);
            display = DiningTables.inventory(level, at).orElseGet(() -> new SimpleContainer(2));
            level.setBlockAndUpdate(chair, Blocks.OAK_STAIRS.defaultBlockState());
            DISPLAYS.put(WorldPos.of(level, at), display);
            person = PersonBody.RESIDENT.get().create(level);
            person.setNoAi(true);
            person.moveTo(at.getX() + 2.5, at.getY(), at.getZ() + 1.5);
            level.addFreshEntity(person);
            colony.keep(LivingContent.ID, Writer.of().children("hunger", List.of(person.getUUID()), id ->
                Writer.of().uuid("resident", id).integer("FoodLevel", 4).decimal("Saturation", 0).tag()).tag());
            living = new LivingPresence(colony);
            dining = new Dining(colony, living, new java.util.Random(0xD1A1A6L));
            worker = new TestWorker(person);
        }

        void zone(Item food, Item... additional) {
            if (zone != null) { front.erase(zone); }
            ColonySettings settings = ColonySettings.empty().with(LivingContent.DINING_SERVINGS.key(), new ColonySettings.Value.Count(2));
            if (food != null) {
                var foods = new java.util.ArrayList<Item>();
                foods.add(food);
                foods.addAll(List.of(additional));
                settings = settings.with(LivingContent.DINING_FOOD.key(), new ColonySettings.Value.Items(foods.stream()
                    .map(item -> ItemFilter.item(BuiltInRegistries.ITEM.getKey(item))).toList()));
            }
            zone = front.addZone(level, LivingContent.DINING, java.util.Set.of(at, chair), settings).orElseThrow().id();
            dining.refresh(level.getServer());
        }

        void drinks(Item drink) {
            var settings = front.zone(zone).orElseThrow().settings().with(LivingContent.DINING_DRINK.key(),
                new ColonySettings.Value.Items(List.of(ItemFilter.item(BuiltInRegistries.ITEM.getKey(drink)))));
            front.erase(zone);
            zone = front.addZone(level, LivingContent.DINING, java.util.Set.of(at, chair), settings).orElseThrow().id();
            dining.refresh(level.getServer());
        }

        List<Node> goals() { return dining.goals(colony.view(level)).stream().flatMap(grown -> grown.nodes().stream()).toList(); }
        public void close() { dining.clear(); person.discard(); DISPLAYS.remove(WorldPos.of(level, at)); colony.raze(); }
    }

    static final class TestWorker implements Worker {
        final ResidentEntity person;
        List<ItemStack> supplied = List.of();
        TestWorker(ResidentEntity person) { this.person = person; }
        public Resident resident() { return person.resident(); }
        public Mob body() { return person; }
        public Container pack() { return person.pack(); }
        public List<ItemStack> supplied() { return supplied; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new AssertionError("unexpected spill: " + stack); }
        public Effects effects() { return Effects.NONE; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
    }
}
