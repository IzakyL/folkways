package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
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
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class DiningGameTests {
    // Isolate the scheduler contract from optional furniture mods. The zone, world and resident are real.
    static final Map<WorldPos, Container> DISPLAYS = new ConcurrentHashMap<>();
    static int cabinetOpens;
    static final java.util.Set<WorldPos> REJECTED_CABINETS = ConcurrentHashMap.newKeySet();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        DiningTables.register(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_dining_display"), new DiningTables.Table() {
            public boolean matches(ServerLevel level, BlockPos at) {
                return level.getBlockState(at).is(Blocks.GOLD_BLOCK) && DISPLAYS.containsKey(WorldPos.of(level, at));
            }
            public Container inventory(ServerLevel level, BlockPos at) { return DISPLAYS.get(WorldPos.of(level, at)); }
        });
        plainTable();
        DiningDrinks.register(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_table_drink"), new DiningDrinks.Drink() {
            public boolean matches(ItemStack stack) { return stack.is(Items.POTION); }
            public ItemStack finishUsing(ServerLevel level, Mob diner, ItemStack serving) {
                serving.shrink(1);
                diner.spawnAtLocation(new ItemStack(Items.GLASS_BOTTLE));
                return ItemStack.EMPTY;
            }
        });
        DiningDrinks.register(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_unconsumed_drink"), new DiningDrinks.Drink() {
            public boolean matches(ItemStack stack) { return stack.is(Items.SPLASH_POTION); }
            public ItemStack finishUsing(ServerLevel level, Mob diner, ItemStack serving) {
                diner.spawnAtLocation(new ItemStack(Items.GLASS_BOTTLE));
                return new ItemStack(Items.GLASS_BOTTLE);
            }
        });
        DiningDrinks.registerSupply(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_drink_cabinet"), new DiningDrinks.Supply() {
            public boolean matches(ServerLevel level, BlockPos at) { return level.getBlockState(at).is(Blocks.BARREL); }
            public Container inventory(ServerLevel level, BlockPos at) {
                cabinetOpens++;
                Container stock = (Container) level.getBlockEntity(at);
                if (!REJECTED_CABINETS.contains(WorldPos.of(level, at))) { return stock; }
                return new Container() {
                    public int getContainerSize() { return stock.getContainerSize(); }
                    public boolean isEmpty() { return stock.isEmpty(); }
                    public ItemStack getItem(int slot) { return stock.getItem(slot); }
                    public ItemStack removeItem(int slot, int amount) { return stock.removeItem(slot, amount); }
                    public ItemStack removeItemNoUpdate(int slot) { return stock.removeItemNoUpdate(slot); }
                    public void setItem(int slot, ItemStack stack) { stock.setItem(slot, stack); }
                    public void setChanged() { stock.setChanged(); }
                    public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
                    public void clearContent() { stock.clearContent(); }
                    public boolean canPlaceItem(int slot, ItemStack stack) { return slot != 0; }
                };
            }
        });
        event.register(DiningGameTests.class);
    }

    private static boolean plainTable;

    // Chiseled quartz is a stable table with no inventory of its own, for every dining test that needs one.
    static synchronized void plainTable() {
        if (plainTable) { return; }
        DiningTables.register(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_plain_table"),
            new DiningTables.Table() {
                public boolean stable() { return true; }
                public boolean matches(ServerLevel level, BlockPos at) {
                    return level.getBlockState(at).is(Blocks.CHISELED_QUARTZ_BLOCK);
                }
                public Container inventory(ServerLevel level, BlockPos at) { return null; }
            });
        plainTable = true;
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void multiFoodMenuKeepsRequestsStableAndChoosesFreshDishesAfterDelivery(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD, Items.COOKED_BEEF);
            var seen = new java.util.HashSet<io.github.izakyl.folkways.core.item.api.ItemSpec>();
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void floorOutlineFindsFurnitureAboveItAndRestocksTheTable(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            f.rezone(java.util.Set.of(f.at.below(), f.chair.below()));
            helper.assertValueEqual(f.goals().size(), 2, "floor cells discover the table and adjacent chair above them");
            f.worker.supplied = List.of(new ItemStack(Items.BREAD));
            helper.assertTrue(f.goals().getFirst().commit(f.level, f.worker) instanceof Outcome.Done,
                "furniture above the marked floor accepts delivery");
            helper.assertTrue(f.display.getItem(0).is(Items.BREAD), "one serving reaches the discovered table");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void tableMealConsumesOnceNourishesAndReturnsTheNativeBowl(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.MUSHROOM_STEW);
            f.display.setItem(0, new ItemStack(Items.MUSHROOM_STEW));
            Node meal = f.urge().orElseThrow().take().get();
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void configuredLongMealConsumesOnlyAtCompletionAndStillCancelsCleanly(GameTestHelper helper) {
        var setting = (net.neoforged.neoforge.common.ModConfigSpec.IntValue)
            io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig.SPEC.getValues().get("living.diningMealTicks");
        int before = setting.get();
        try {
            setting.set(400);
            try (Fixture f = new Fixture(helper)) {
                f.zone(Items.BREAD);
                f.display.setItem(0, new ItemStack(Items.BREAD));
                Node first = f.urge().orElseThrow().take().get();
                helper.assertValueEqual(((Workload.Once) first.spec().workload()).baseTicks(), 400,
                    "the real meal workload uses the configured twenty-second duration");
                for (int tick : List.of(0, 199, 399)) {
                    first.spec().gesture().perform(f.person, tick, f.at);
                    helper.assertValueEqual(f.display.getItem(0).getCount(), 1, "the eating gesture leaves its serving on the table");
                    helper.assertValueEqual(f.living.foodLevelOf(f.person.getUUID()).orElseThrow(), 4,
                        "a partially completed long meal grants no nourishment");
                    helper.assertValueEqual(f.person.leisureMotion(), "dining", "meal uses a seated eating pose");
                }
                helper.assertTrue(f.urge().orElseThrow().take().get() == first,
                    "the diner keeps the same reserved meal offered throughout its windup");
                first.released(f.level, f.worker, Ending.DROPPED);
                helper.assertValueEqual(f.display.getItem(0).getCount(), 1, "cancelling the long meal preserves its serving");
                helper.assertValueEqual(f.person.leisureMotion(), "", "cancellation releases the long eating pose");
                helper.assertTrue(f.living.mood(f.person.getUUID()).dined < 0, "cancelled meals grant no dining mood");
                Node retry = f.urge().orElseThrow().take().get();
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void teleportingAwayFromAnEngagedMealDoesNotEatAndReleasesReservations(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            f.display.setItem(0, new ItemStack(Items.BREAD));
            Node meal = f.urge().orElseThrow().take().get();
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
            Node retry = f.urge().orElseThrow().take().get();
            helper.assertValueEqual(retry.spec().doing(), LivingContent.DINING_EATING, "returning can reserve the same seat and serving again");
            retry.ended(f.level, Ending.DROPPED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void missingTableFoodFallsBackToPocketRations(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            f.person.pack().setItem(0, new ItemStack(Items.BREAD, 2));
            f.observeFixtureWrites();
            Node meal = f.living.urges(f.worker, f.colony.view(f.level)).stream()
                .filter(urge -> urge.id().equals(LivingPresence.EAT)).findFirst().orElseThrow().take().get();
            helper.assertTrue(meal instanceof EatAction, "an empty table does not block carried food");
            helper.assertTrue(meal.commit(f.level, f.worker) instanceof Outcome.Done, "the pocket ration can be eaten");
            helper.assertValueEqual(f.person.pack().getItem(0).getCount(), 1, "one pocket ration is eaten");
            helper.assertTrue(f.living.mood(f.person.getUUID()).dined < 0, "pocket meals grant no table dining bonus");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void connectedBasicTablesShareTheActualCakeAtTheirFarEnd(GameTestHelper helper) {
        var table = Blocks.CHISELED_QUARTZ_BLOCK.defaultBlockState();
        try (Fixture f = new Fixture(helper, table)) {
            f.zone(Items.CAKE);
            var cells = new java.util.HashSet<BlockPos>();
            cells.add(f.chair);
            for (int x = 0; x < 8; x++) {
                BlockPos at = f.at.east(x);
                f.level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
                f.level.setBlockAndUpdate(at, table);
                cells.add(at);
            }
            f.rezone(cells);
            var far = f.at.east(7);
            Node supply = f.goals().stream().filter(node -> node.spec().focus().filter(far::equals).isPresent()).findFirst().orElseThrow();
            f.worker.supplied = List.of(new ItemStack(Items.CAKE));
            helper.assertTrue(supply.commit(f.level, f.worker) instanceof Outcome.Done, "one end places food at the opposite end");
            helper.assertTrue(f.level.getBlockState(far.above()).is(Blocks.CAKE), "food remains an actual distant world block");
            Node meal = f.urge().orElseThrow().take().get();
            helper.assertTrue(meal.commit(f.level, f.worker) instanceof Outcome.Done, "the only near-end seat shares the remote cake");
            helper.assertValueEqual(f.level.getBlockState(far.above()).getValue(net.minecraft.world.level.block.CakeBlock.BITES), 1,
                "one shared meal advances the native cake by exactly one bite");
            helper.assertTrue(f.level.getBlockState(f.at.above()).isAir(), "no duplicate cake appears beside the diner");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void dinersDrinkDirectlyAtTheCabinetWithoutFoodOrPackSpace(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            BlockPos cabinet = f.cabinet(Items.POTION);
            for (int slot = 0; slot < f.person.pack().getContainerSize(); slot++) { f.person.pack().setItem(slot, new ItemStack(Items.STONE, 64)); }
            Node canceled = f.drinking().orElseThrow().take().get();
            helper.assertValueEqual(canceled.spec().doing(), LivingContent.DINING_DRINKING, "a bar visit is independent of table food");
            canceled.ended(f.level, Ending.DROPPED);
            helper.assertTrue(f.stock(cabinet).getItem(0).is(Items.POTION), "canceling leaves the bottle in the cabinet");
            Node drink = f.drinking().orElseThrow().take().get();
            f.person.moveTo(f.chair.getX() + .5, f.chair.getY(), f.chair.getZ() + .5);
            Outcome result = drink.commit(f.level, f.worker);
            helper.assertTrue(result instanceof Outcome.Done, "a seated drink consumes directly from the cabinet even with a full pack");
            helper.assertTrue(f.stock(cabinet).getItem(0).isEmpty(), "one real cabinet serving is consumed");
            helper.assertValueEqual(((Outcome.Done) result).made().stream().filter(stack -> stack.is(Items.GLASS_BOTTLE)).mapToInt(ItemStack::getCount).sum(), 1,
                "the native empty bottle is returned exactly once");
            helper.assertValueEqual(f.person.getPersistentData().getInt("folkways_vanilla:table_drinks"), 1, "only consumption advances drinking history");
            helper.assertValueEqual(f.living.mood(f.person.getUUID()).drank, f.level.getGameTime(), "drinking records its own mood experience");
            helper.assertTrue(f.goals().stream().anyMatch(goal -> goal.spec().focus().filter(cabinet::equals).isPresent()), "the emptied slot requests replenishment");
            helper.assertTrue(f.drinking().isEmpty(), "a completed drink has a cooldown");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unconsumedDrinkAdapterCannotPublishBottlesOrHistory(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.display.setItem(1, new ItemStack(Items.SPLASH_POTION));
            var left = DiningDrinks.consume(f.level, f.person, f.display, 1);
            helper.assertTrue(left.isEmpty(), "an adapter that did not consume the drink cannot return or spawn bottles");
            helper.assertTrue(f.display.getItem(1).is(Items.SPLASH_POTION), "the unconsumed drink returns to its original table slot");
            helper.assertValueEqual(f.person.getPersistentData().getInt("folkways_vanilla:table_drinks"), 0, "no fictitious drinking history is recorded");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void carriedCargoBottlesAreNeverDrunkWithAMeal(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            f.drinks(Items.POTION);
            f.display.setItem(0, new ItemStack(Items.BREAD));
            f.person.pack().setItem(0, new ItemStack(Items.POTION));
            Node meal = f.urge().orElseThrow().take().get();
            helper.assertValueEqual(meal.spec().doing(), LivingContent.DINING_EATING,
                "an area without a cabinet serves meals without a drink");
            var result = (Outcome.Done) meal.commit(f.level, f.worker);
            helper.assertTrue(f.person.pack().getItem(0).is(Items.POTION), "a bottle not collected for this meal stays untouched");
            helper.assertTrue(result.made().isEmpty(), "no empty container is produced");
            helper.assertValueEqual(f.person.getPersistentData().getInt("folkways_vanilla:table_drinks"), 0, "a skipped drink has no completed history");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void drinkCabinetsOnlyServeTheirMarkedDiningZoneAndRetractOnRemoval(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            BlockPos cabinet = f.at.west();
            f.level.setBlockAndUpdate(cabinet, Blocks.BARREL.defaultBlockState());
            Container stock = (Container) f.level.getBlockEntity(cabinet);
            for (int slot = 1; slot < stock.getContainerSize(); slot++) { stock.setItem(slot, new ItemStack(Items.STONE, 64)); }
            var cells = java.util.Set.of(f.at, f.chair, cabinet);
            f.rezone(cells);
            helper.assertFalse(f.colony.storesIn(f.level.dimension()).contains(WorldPos.of(f.level, cabinet)), "without a drink menu a cabinet is never automatically offered");
            f.drinks(Items.POTION);
            helper.assertFalse(f.colony.storesIn(f.level.dimension()).contains(WorldPos.of(f.level, cabinet)), "a cabinet outside the selected zone is not adopted");
            f.rezone(cells);
            helper.assertTrue(f.colony.storesIn(f.level.dimension()).contains(WorldPos.of(f.level, cabinet)), "the marked cabinet exposes its real inventory as colony stock");
            Node refill = f.goals().stream().filter(node -> node.spec().focus().filter(cabinet::equals).isPresent()).findFirst().orElseThrow();
            helper.assertFalse(refill.acceptsInput(new io.github.izakyl.folkways.core.inventory.api.Stash(WorldPos.of(f.level, cabinet))), "a refill cannot empty the same cabinet into itself");
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void displayAndCabinetObservationsAreReusedAndDirtyCoverageDefersRefills(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            class CountingDisplay extends SimpleContainer {
                int reads;
                CountingDisplay() { super(2); }
                @Override public ItemStack getItem(int slot) { reads++; return super.getItem(slot); }
            }
            CountingDisplay display = new CountingDisplay();
            DISPLAYS.put(WorldPos.of(f.level, f.at), display);
            f.zone(Items.BREAD);
            BlockPos cabinet = f.cabinet(Items.POTION);
            f.publish();
            helper.assertValueEqual(f.observed(f.at).size(), 2, "empty display requests two dishes");
            helper.assertTrue(f.dining.drinking(f.worker, f.colony.view(f.level)).isPresent(),
                "an observed bottle offers a drink");
            display.reads = 0;
            cabinetOpens = 0;
            for (int i = 0; i < 4; i++) {
                f.dining.goals(f.colony.view(f.level));
                f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false);
                f.dining.drinking(f.worker, f.colony.view(f.level));
            }
            helper.assertValueEqual(display.reads, 0, "unchanged decisions never read the display");
            helper.assertValueEqual(cabinetOpens, 0, "unchanged decisions do not reopen the cabinet");
            display.setItem(0, new ItemStack(Items.BREAD));
            f.colony.inventoryFeedback().changed(io.github.izakyl.folkways.core.inventory.api.Stash.at(WorldPos.of(f.level, f.at)));
            helper.assertValueEqual(f.observed(f.at).size(), 0, "a dirty display is unknown rather than empty");
            f.stock(cabinet).setItem(0, ItemStack.EMPTY);
            f.colony.inventoryFeedback().changed(io.github.izakyl.folkways.core.inventory.api.Stash.at(WorldPos.of(f.level, cabinet)));
            helper.assertTrue(f.dining.drinking(f.worker, f.colony.view(f.level)).isEmpty(),
                "dirty coverage does not serve an old bottle");
            f.publish();
            helper.assertValueEqual(f.observed(f.at).size(), 1, "the notified serving is visible after observation");
            helper.assertTrue(f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).isPresent(),
                "the observed serving can be selected");
            helper.assertFalse(f.observed(cabinet).isEmpty(), "the notified empty slot requests a refill");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void ongoingDrinkUsesObservationsUntilTake(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            BlockPos cabinet = f.cabinet(Items.POTION);
            f.publish();
            Node drink = f.dining.drinking(f.worker, f.colony.view(f.level)).orElseThrow().take().get();
            cabinetOpens = 0;
            for (int i = 0; i < 4; i++) {
                helper.assertTrue(drink.refusal(f.level).isEmpty(), "the observed bottle remains available");
            }
            helper.assertValueEqual(cabinetOpens, 0, "ongoing checks do not open the cabinet");
            f.person.moveTo(f.chair.getX() + .5, f.chair.getY(), f.chair.getZ() + .5);
            f.stock(cabinet).getItem(0).shrink(1);
            helper.assertTrue(drink.commit(f.level, f.worker) instanceof Outcome.Failed,
                "the actual take rejects an unreported removal");
            helper.assertTrue(f.colony.inventory().snapshot().store(WorldPos.of(f.level, cabinet)).orElseThrow()
                .state() != io.github.izakyl.folkways.core.inventory.api.InventorySnapshot.State.OBSERVED,
                "the execution mismatch marks the observation stale");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void refusedCabinetSlotIsNotRetriedOnEveryPass(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            BlockPos cabinet = f.cabinet(Items.POTION);
            f.stock(cabinet).setItem(0, ItemStack.EMPTY);
            f.publish();
            Node refill = f.observed(cabinet).getFirst();
            WorldPos at = WorldPos.of(f.level, cabinet);
            REJECTED_CABINETS.add(at);
            try {
                f.person.moveTo(cabinet.getX() + .5, cabinet.getY(), cabinet.getZ() + 1.5);
                f.worker.supplied = List.of(new ItemStack(Items.POTION));
                refill.commit(f.level, f.worker);
                helper.assertTrue(f.stock(cabinet).getItem(0).isEmpty(), "the refusing slot stays empty");
                f.publish();
                for (int i = 0; i < 4; i++) {
                    helper.assertTrue(f.observed(cabinet).isEmpty(), "the rejected slot is backed off after reobservation");
                }
            } finally {
                REJECTED_CABINETS.remove(at);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unloadedFurnitureRetainsUnknownMembership(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            BlockPos cabinet = f.cabinet(Items.POTION);
            f.publish();
            WorldPos table = WorldPos.of(f.level, f.at);
            WorldPos counter = WorldPos.of(f.level, cabinet);
            var data = io.github.izakyl.folkways.core.colony.internal.ColonyData.find(f.level.getServer(), f.colony.id())
                .orElseThrow();
            data.inventory().chunk(f.level, f.at.getX() >> 4, f.at.getZ() >> 4, false);
            data.inventory().chunk(f.level, cabinet.getX() >> 4, cabinet.getZ() >> 4, false);
            DISPLAYS.remove(table);
            f.level.setBlockAndUpdate(cabinet, Blocks.AIR.defaultBlockState());
            f.dining.refresh(f.level.getServer());
            var snapshot = f.colony.inventory().snapshot();
            helper.assertTrue(snapshot.store(table).isPresent() && snapshot.store(counter).isPresent(),
                "unavailable furniture remains in membership during rediscovery");
            helper.assertFalse(snapshot.coverage().complete(), "unloaded furniture leaves coverage incomplete");
            helper.assertTrue(f.colony.storesIn(f.level.dimension()).contains(counter),
                "the unloaded cabinet remains offered");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unexploredDiningCellsKeepMembershipIncomplete(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.zone(Items.BREAD);
            BlockPos remote = new BlockPos(22_000_000, 80, 22_000_000);
            helper.assertFalse(f.level.isLoaded(remote), "the remote dining cell starts unloaded");
            f.rezone(java.util.Set.of(f.at, f.chair, remote));
            f.publish();
            helper.assertFalse(f.colony.inventory().snapshot().coverage().complete(),
                "unexplored dining cells cannot establish complete membership");
            helper.assertFalse(f.level.isLoaded(remote), "discovery did not load the remote chunk");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void residentMealUsesPackObservation(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.person.joinColony(f.colony.id());
            f.person.pack().setItem(0, new ItemStack(Items.BREAD));
            f.publish();
            f.worker.packReads = 0;
            for (int i = 0; i < 4; i++) {
                helper.assertTrue(f.carriesMeal(), "the observed bread is a meal");
            }
            helper.assertValueEqual(f.worker.packReads, 0, "meal queries do not open the worker pack");
            f.colony.inventoryFeedback().changedResident(f.person.getUUID());
            helper.assertFalse(f.carriesMeal(), "a dirty pack cannot establish a meal");
            f.person.pack().setItem(0, ItemStack.EMPTY);
            f.publish();
            helper.assertFalse(f.carriesMeal(), "the observed empty pack has no meal");
            f.person.pack().setItem(0, new ItemStack(Items.BREAD));
            f.colony.inventoryFeedback().changedResident(f.person.getUUID());
            f.publish();
            helper.assertTrue(f.carriesMeal(), "a notified addition becomes visible");
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
            colony.keep(LivingContent.ID, Writer.of(new LivingPresence(colony).save()).blob("pets",
                    Writer.of().longValue("next_attempt", -1L).uuids("pets", List.of()).tag()).children("hunger", List.of(person.getUUID()), id ->
                Writer.of().uuid("resident", id).integer("FoodLevel", 4).decimal("Saturation", 0).decimal("Exhaustion", 0).tag()).tag());
            living = new LivingPresence(colony);
            dining = new Dining(colony, living, new java.util.Random(0xD1A1A6L));
            worker = new TestWorker(person);
        }

        void zone(Item food, Item... additional) {
            ColonySettings settings = ColonySettings.empty().with(LivingContent.DINING_SERVINGS.key(), new ColonySettings.Value.Count(2));
            // No food is a list cleared to nothing; a setting left out takes the schema's default foods.
            var foods = new java.util.ArrayList<Item>();
            if (food != null) {
                foods.add(food);
                foods.addAll(List.of(additional));
            }
            settings = settings.with(LivingContent.DINING_FOOD.key(), new ColonySettings.Value.Items(foods.stream()
                .map(item -> ItemFilter.item(BuiltInRegistries.ITEM.getKey(item))).toList()));
            rezone(java.util.Set.of(at, chair), settings);
        }

        void drinks(Item drink) {
            rezone(java.util.Set.of(at, chair), drinkMenu(drink));
        }

        // Redraws the dining area over these cells, keeping its settings unless given others.
        void rezone(java.util.Set<BlockPos> cells) {
            rezone(cells, front.zone(zone).orElseThrow().settings());
        }

        void rezone(java.util.Set<BlockPos> cells, ColonySettings settings) {
            if (zone != null) { front.erase(zone); }
            zone = front.addZone(level, LivingContent.DINING, cells, settings).orElseThrow().id();
            dining.refresh(level.getServer());
        }

        private ColonySettings drinkMenu(Item drink) {
            return front.zone(zone).orElseThrow().settings().with(LivingContent.DINING_DRINK.key(),
                new ColonySettings.Value.Items(List.of(ItemFilter.item(BuiltInRegistries.ITEM.getKey(drink)))));
        }

        // A barrel west of the table stands in for a native cabinet: one drink, other slots blocked.
        BlockPos cabinet(Item drink) {
            BlockPos cabinet = chair.west();
            level.setBlockAndUpdate(cabinet, Blocks.BARREL.defaultBlockState());
            Container stock = stock(cabinet);
            for (int slot = 1; slot < stock.getContainerSize(); slot++) { stock.setItem(slot, new ItemStack(Items.STONE, 64)); }
            stock.setItem(0, new ItemStack(drink)); stock.setChanged();
            rezone(java.util.Set.of(at, chair, cabinet), drinkMenu(drink));
            return cabinet;
        }

        // Without a dining area, the only meal a hungry resident can be offered is one it carries.
        boolean carriesMeal() {
            return living.urges(worker, colony.view(level)).stream().anyMatch(urge -> urge.id().equals(LivingPresence.EAT));
        }

        void publish() {
            var data = io.github.izakyl.folkways.core.colony.internal.ColonyData.find(level.getServer(), colony.id())
                .orElseThrow();
            // Connected native displays each require sliced admission sampling.
            InventoryObservations.publish(data, level, Map.of(person.getUUID(), person), 10000);
        }

        Container stock(BlockPos cabinet) { return (Container) level.getBlockEntity(cabinet); }

        void observeFixtureWrites() {
            colony.inventoryFeedback().changed(
                io.github.izakyl.folkways.core.inventory.api.Stash.at(WorldPos.of(level, at)));
            for (var store : colony.inventory().snapshot().stores().keySet()) {
                colony.inventoryFeedback().changed(store);
            }
            publish();
        }

        // Refills at one piece of furniture, as the last observation saw it.
        List<Node> observed(BlockPos focus) {
            return dining.goals(colony.view(level)).stream().flatMap(grown -> grown.nodes().stream())
                .filter(node -> node.spec().focus().filter(focus::equals).isPresent()).toList();
        }

        List<Node> goals() {
            observeFixtureWrites();
            return dining.goals(colony.view(level)).stream().flatMap(grown -> grown.nodes().stream()).toList();
        }

        java.util.Optional<Urge> urge() {
            observeFixtureWrites();
            return dining.urge(worker, colony.view(level), Urge.SPARE, false);
        }

        java.util.Optional<Urge> drinking() {
            observeFixtureWrites();
            return dining.drinking(worker, colony.view(level));
        }
        public void close() { dining.clear(); person.discard(); DISPLAYS.remove(WorldPos.of(level, at)); colony.raze(); }
    }

    static final class TestWorker implements Worker {
        final ResidentEntity person;
        List<ItemStack> supplied = List.of();
        int packReads;
        TestWorker(ResidentEntity person) { this.person = person; }
        public Resident resident() { return person.resident(); }
        public Mob body() { return person; }
        public Container pack() { packReads++; return person.pack(); }
        public List<ItemStack> supplied() { return supplied; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new AssertionError("unexpected spill: " + stack); }
        public Effects effects() { return Effects.NONE; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
    }
}
