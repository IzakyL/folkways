package io.github.izakyl.folkways.cookery.tools;

import net.minecraft.world.entity.EquipmentSlot;
import io.github.izakyl.folkways.core.api.resident.body.PersonalItems;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.effect.Stats;
import io.github.izakyl.folkways.effect.perk.Perks;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.Facings;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.orders.OrderReturns;
import io.github.izakyl.folkways.plugins.orders.Orders;
import io.github.izakyl.folkways.plugins.orders.OrdersContent;
import net.minecraft.core.registries.BuiltInRegistries;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Delivery;
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
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import io.github.izakyl.folkways.core.engine.colony.ColonyWorks;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.board.ChoppingBoardWorkshops;
import io.github.izakyl.folkways.cookery.racks.KitchenRacks;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TaskToolsGameTests {
    private static final ToolNeed KNIFE = new ToolNeed.Tagged(TagMod.KITCHEN_KNIFE);
    private static final ItemSpec KNIVES = ItemSpec.of(TagMod.KITCHEN_KNIFE);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TaskToolsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void markedKitchenToolsCannotBeBorrowedFromStorageOrTakenFromTheirOwner(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            PersonalItems.mark(rig.body, rig.rack.getItem(0), "mainhand");
            var stored = rig.tools.stock.read(rig.level, KNIVES);
            helper.assertTrue(stored.sources().isEmpty() && stored.inventory().tools().isEmpty(),
                "personal tools in a shared rack are never offered to the kitchen borrowing pool");
            ItemStack actual = rig.rack.removeItemNoUpdate(0);
            rig.body.pack().setItem(0, actual);
            var carried = rig.tools.stock.read(rig.level, KNIVES);
            helper.assertTrue(carried.inventory().tools().isEmpty(), "a resident's marked physical tool is outside the shared pool too");
            var lease = new KitchenTools.Lease(UUID.randomUUID(),
                new KitchenTools.Request(rig.colony.colonyId(), UUID.randomUUID(), KNIVES), UUID.randomUUID(), rig.body.id(), KitchenTools.Phase.BORROWED);
            helper.assertValueEqual(rig.tools.carriedSlot(rig.body.pack(), lease, actual.copy()), -1,
                "even stale component-identical loan metadata cannot take or return a personal tool");
            PersonalItems.clear(actual);
            helper.assertTrue(rig.tools.stock.read(rig.level, KNIVES).inventory().tools().size() == 1,
                "explicitly releasing ownership restores ordinary physical tool observation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void temporaryClothingHonorsAnEmptyLockedSlotUntilItIsUnlocked(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            ItemStack helmet = new ItemStack(Items.LEATHER_HELMET);
            helmet.setDamageValue(17);
            helmet.set(DataComponents.CUSTOM_NAME, Component.literal("the physical loan"));
            ItemStack expected = helmet.copy();
            rig.body.pack().setItem(0, helmet);
            var family = Goods.specOf(helmet);
            var lease = new KitchenTools.Lease(UUID.randomUUID(),
                new KitchenTools.Request(rig.colony.colonyId(), UUID.randomUUID(), family), UUID.randomUUID(), rig.body.id(), KitchenTools.Phase.BORROWED);
            rig.tools.record(lease, helmet);
            Equipment.lock(rig.body, EquipmentSlot.HEAD, true);
            helper.assertTrue(!LoanClothing.equip(rig.tools, rig.worker, Map.of(EquipmentSlot.HEAD, lease)),
                "the kitchen cannot put temporary clothes into a deliberately locked empty slot");
            helper.assertTrue(rig.body.mob().getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                && ItemStack.matches(rig.body.pack().getItem(0), expected), "rejected equipping preserves the single physical loan");
            Equipment.lock(rig.body, EquipmentSlot.HEAD, false);
            helper.assertTrue(LoanClothing.equip(rig.tools, rig.worker, Map.of(EquipmentSlot.HEAD, lease)),
                "unlocking permits the existing temporary clothing operation");
            helper.assertTrue(rig.body.pack().isEmpty() && ItemStack.matches(rig.body.mob().getItemBySlot(EquipmentSlot.HEAD), expected),
                "the actual native armor slot owns the unchanged loan without a pack copy");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedSickleDefaultsAndBorrowingBelongToFarming(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var farming = io.github.izakyl.folkways.plugins.farming.FarmingContent.trade();
            var crafting = io.github.izakyl.folkways.plugins.wares.WaresContent.trade();
            var sickle = ModItems.SICKLE.get();
            helper.assertTrue(io.github.izakyl.folkways.core.api.vocation.KitRules.allows(rig.colony.works(), farming, sickle),
                "the harvesting vocation includes the shared sickle by default");
            helper.assertFalse(io.github.izakyl.folkways.core.api.vocation.KitRules.allows(rig.colony.works(), crafting, sickle),
                "crafting does not inherit farming's shared sickle");
            helper.assertTrue(io.github.izakyl.folkways.core.api.vocation.KitRules.allows(rig.colony.works(), crafting,
                ModItems.IRON_KITCHEN_KNIFE.get()), "kitchen defaults belong to crafting");
            helper.assertFalse(io.github.izakyl.folkways.core.api.vocation.KitRules.allows(rig.colony.works(), farming,
                ModItems.IRON_KITCHEN_KNIFE.get()), "farming does not inherit kitchen knives");
            rig.rack.setItem(0, new ItemStack(sickle));
            var executed = new AtomicInteger();
            Node work = rig.simple(executed, false, farming,
                io.github.izakyl.folkways.cookery.farming.CookerySickle.SICKLE);
            var front = ColonyFront.of(rig.colony.works());
            front.setSetting(crafting.id(), "tool", new ColonySettings.Value.Items(List.of(
                ItemFilter.item(BuiltInRegistries.ITEM.getKey(sickle)))));
            front.setSetting(farming.id(), "tool", new ColonySettings.Value.Items(List.of()));
            helper.assertTrue(work.planned(rig.level).isPresent() && !work.ready(rig.level),
                "crafting's allowed sickle cannot be borrowed for a harvesting node");
            front.setSetting(farming.id(), "tool", new ColonySettings.Value.Items(List.of(
                ItemFilter.item(BuiltInRegistries.ITEM.getKey(sickle)))));
            helper.assertTrue(work.planned(rig.level).isEmpty() && work.ready(rig.level), "farming's own list allows the harvest");
            rig.finish(work);
            helper.assertValueEqual(executed.get(), 1, "one farming action used the borrowed sickle");
            helper.assertValueEqual(Goods.countIn(rig.rack, Goods.specOf(sickle.getDefaultInstance())), 1L,
                "the same physical sickle returns to shared storage");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedBorrowUsesTheWorkVocationAndHeldLoansStillWorkAfterDisabling(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            var executions = new AtomicInteger();
            Node work = rig.simple(executions, false);
            var crafting = io.github.izakyl.folkways.plugins.wares.WaresContent.CRAFTING;
            var farming = io.github.izakyl.folkways.plugins.farming.FarmingContent.VOCATION;
            var front = ColonyFront.of(rig.colony.works());
            var knife = new ColonySettings.Value.Items(List.of(ItemFilter.item(
                BuiltInRegistries.ITEM.getKey(ModItems.IRON_KITCHEN_KNIFE.get()))));
            front.setSetting(farming, "tool", knife);
            front.setSetting(crafting, "tool", new ColonySettings.Value.Items(List.of()));
            helper.assertTrue(work.planned(rig.level).isPresent(), "farming's allowed knife cannot qualify crafting work");
            helper.assertFalse(work.ready(rig.level), "no shared knife can be reserved for the forbidden vocation");
            helper.assertTrue(((ToolWork) work).families().isEmpty(), "a disabled vocation requests no shared-tool replenishment");
            helper.assertTrue(rig.tools.pool.leases(rig.colony.colonyId()).isEmpty(), "refusal creates no physical lease");
            var spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(rig.boardAt),
                Stances.WHEREVER, Workload.Once.of(1)).vocation(WaresContent.trade()).tools(new ToolUse(KNIFE, 0))
                .needs(new io.github.izakyl.folkways.core.api.work.Need(KNIVES, 1, true)).done();
            var rules = io.github.izakyl.folkways.core.api.vocation.KitRules.snapshot(rig.colony.works());
            helper.assertFalse(Tools.canPrepare(rig.body.kind().id(), spec, List.of(), rules),
                "planning cannot fetch a missing shared tool forbidden for the step's vocation");
            helper.assertTrue(Tools.canPrepare(rig.body.kind().id(), spec, List.of(rig.namedKnife()), rules),
                "an already held shared tool satisfies planning regardless of the step's acquisition list");
            front.setSetting(crafting, "tool", knife);
            helper.assertTrue(Tools.canPrepare(rig.body.kind().id(), spec, List.of(),
                io.github.izakyl.folkways.core.api.vocation.KitRules.snapshot(rig.colony.works())),
                "planning may fetch a missing shared tool allowed for the step's vocation");
            helper.assertTrue(work.planned(rig.level).isEmpty() && work.ready(rig.level), "crafting's own list permits borrowing");
            rig.step(work);
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 1L, "one physical knife was borrowed");
            front.setSetting(crafting, "tool", new ColonySettings.Value.Items(List.of()));
            helper.assertTrue(work.ready(rig.level), "the already borrowed knife remains ready after disabling acquisition");
            rig.finish(work);
            helper.assertValueEqual(executions.get(), 1, "the native action uses its held loan after disabling acquisition");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "the original knife is back in shared storage");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 0L, "the pack retains no duplicate");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disablingWhileTravellingPreventsThePhysicalBorrow(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            Node work = rig.simple(new AtomicInteger(), false);
            helper.assertTrue(work.ready(rig.level), "the permitted knife can be reserved");
            var within = (Workload.Within) work.spec().workload();
            Node borrow = ((Workload.Within.Turn.Piece) within.next(rig.level, rig.worker)).node();
            ColonyFront.of(rig.colony.works()).setSetting(io.github.izakyl.folkways.plugins.wares.WaresContent.CRAFTING,
                "tool", new ColonySettings.Value.Items(List.of()));
            rig.stand(borrow);
            helper.assertTrue(Commit.run(rig.level, borrow, rig.worker) instanceof Outcome.Failed,
                "the transfer rechecks the work vocation after travelling");
            borrow.ended(rig.level, Ending.REVOKED);
            within.ended(rig.level, borrow, Ending.REVOKED);
            work.ended(rig.level, Ending.REVOKED);
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "the disallowed tool never leaves storage");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 0L, "no forbidden borrow enters the pack");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingAllowedKnivesWhileTravellingRetriesWithTheNewSubset(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var iron = ModItems.IRON_KITCHEN_KNIFE.get();
            var other = Goods.members(KNIVES).stream().filter(item -> item != iron).findFirst().orElseThrow();
            rig.rack.setItem(0, rig.namedKnife());
            rig.rack.setItem(1, new ItemStack(other));
            var front = ColonyFront.of(rig.colony.works());
            front.setSetting(WaresContent.CRAFTING, "tool", new ColonySettings.Value.Items(List.of(
                ItemFilter.item(BuiltInRegistries.ITEM.getKey(iron)))));
            var executions = new AtomicInteger();
            Node work = rig.simple(executions, false);
            helper.assertTrue(work.ready(rig.level), "the original subset permits borrowing");
            var within = (Workload.Within) work.spec().workload();
            Node borrow = ((Workload.Within.Turn.Piece) within.next(rig.level, rig.worker)).node();
            front.setSetting(WaresContent.CRAFTING, "tool", new ColonySettings.Value.Items(List.of(
                ItemFilter.item(BuiltInRegistries.ITEM.getKey(other)))));
            rig.stand(borrow);
            helper.assertTrue(Commit.run(rig.level, borrow, rig.worker) instanceof Outcome.Failed,
                "the old knife is forbidden after travelling");
            within.ended(rig.level, borrow, Ending.REVOKED);
            helper.assertTrue(rig.tools.pool.leases(rig.colony.colonyId()).isEmpty(), "the rejected lease is removed");
            helper.assertFalse(rig.step(work), "the next turn borrows from the new subset without another readiness check");
            helper.assertTrue(rig.rack.getItem(0).getItem() == iron && rig.rack.getItem(1).isEmpty(),
                "only the newly allowed knife leaves storage");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(other.getDefaultInstance())), 1L,
                "the new knife is physically borrowed");
            rig.finish(work);
            helper.assertValueEqual(executions.get(), 1, "the native action completes once with the new knife");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 2L, "both physical knives end in storage");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 0L, "no borrowed knife remains in the pack");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedToolStockRespectsIndependentTakeAndStoreAccess(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            rig.colony.works().rule(CookeryContent.ID, rig.level.dimension(), List.of(
                new StoreRule.Access(rig.rackAt, false, true)));
            var blocked = rig.tools.stock.read(rig.level, KNIVES);
            helper.assertTrue(blocked.sources().isEmpty(), "a rack with take disabled offers no physical borrowing source");
            helper.assertTrue(blocked.inventory().tools().stream().allMatch(tool ->
                tool.whereabouts() == KitchenTools.Whereabouts.UNREACHABLE), "the real inaccessible tool remains observed");
            helper.assertTrue(rig.tools.pool.supply(rig.colony.colonyId(), KNIVES, blocked.inventory()).replenishment().isEmpty(),
                "a real tool behind disabled take access is not duplicated by automatic replenishment");
            rig.colony.works().rule(CookeryContent.ID, rig.level.dimension(), List.of(
                new StoreRule.Access(rig.rackAt, true, false), new StoreRule.Access(rig.chestAt, false, false)));
            helper.assertValueEqual(rig.tools.stock.read(rig.level, KNIVES).sources().size(), 1,
                "store access does not prevent borrowing when take access remains enabled");
            helper.assertTrue(rig.tools.stock.destinations(rig.level, rig.namedKnife()).isEmpty(),
                "a destination with store disabled cannot receive returns or automatic replenishment");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void borrowingRechecksTakeAccessAfterTheTripWasPlanned(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            Node work = rig.simple(new AtomicInteger(), false);
            var within = (Workload.Within) work.spec().workload();
            Node borrow = ((Workload.Within.Turn.Piece) within.next(rig.level, rig.worker)).node();
            rig.stand(borrow);
            rig.colony.works().rule(CookeryContent.ID, rig.level.dimension(), List.of(
                new StoreRule.Access(rig.rackAt, false, true)));
            helper.assertTrue(Commit.run(rig.level, borrow, rig.worker) instanceof Outcome.Failed,
                "take access disabled during the trip is checked again before removing the tool");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "the player's restricted knife remains on the rack");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 0L, "no borrowed copy appears in the resident's pack");
            within.ended(rig.level, borrow, new Ending.Failed(borrow.id(),
                io.github.izakyl.folkways.cookery.CookeryRefusal.STATION_CHANGED));
            work.ended(rig.level, Ending.REVOKED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPendingToolReturnWaitsForStoreAccessWithoutRepeatingWork(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            var executions = new AtomicInteger();
            Node work = rig.simple(executions, false);
            helper.assertFalse(rig.step(work), "the real knife is borrowed first");
            helper.assertFalse(rig.step(work), "the actual food action runs once before the return");
            var within = (Workload.Within) work.spec().workload();
            Node returning = ((Workload.Within.Turn.Piece) within.next(rig.level, rig.worker)).node();
            rig.stand(returning);
            rig.colony.works().rule(CookeryContent.ID, rig.level.dimension(), List.of(
                new StoreRule.Access(rig.rackAt, true, false), new StoreRule.Access(rig.chestAt, true, false)));
            helper.assertTrue(Commit.run(rig.level, returning, rig.worker) instanceof Outcome.Failed,
                "store access disabled during the return trip is checked again before insertion");
            within.ended(rig.level, returning, new Ending.Failed(returning.id(),
                io.github.izakyl.folkways.cookery.CookeryRefusal.STATION_CHANGED));
            helper.assertTrue(within.next(rig.level, rig.worker) instanceof Workload.Within.Turn.Wait,
                "a completed task waits while every destination refuses storage");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 1L,
                "the physical loan stays in the pack while return storage is disabled");
            rig.colony.works().rule(CookeryContent.ID, rig.level.dimension(), List.of());
            rig.finish(work);
            helper.assertValueEqual(executions.get(), 1, "reopening storage finishes the return without replaying the action");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "the real named knife returns to its preferred rack");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 0L, "the returned knife leaves no carried duplicate");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void completedNativeIgnitionCanResumeReturnsWhenTheStoveGetsWetOrTheLastChargeWasSpent(GameTestHelper helper) {
        for (var item : List.of(Items.FLINT_AND_STEEL, Items.FIRE_CHARGE)) {
            try (Rig rig = new Rig(helper)) {
                rig.level.setBlockAndUpdate(rig.boardCell,
                    com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks.STOVE.get().defaultBlockState());
                rig.level.setBlockAndUpdate(rig.boardCell.above(), Blocks.AIR.defaultBlockState());
                rig.bookings.admit(rig.boardAt);
                rig.chest.setItem(0, new ItemStack(item));
                var station = new io.github.izakyl.folkways.cookery.stove.StoveStation();
                Grown ignition = station.recovery(rig.colony.colonyId(), rig.level, rig.boardAt,
                    Stances.at(rig.level, rig.boardCell.south()), rig.bookings, () -> { }).getFirst();
                Node work = rig.tools.decorate(ignition).nodes().getFirst();
                helper.assertTrue(work.planned(rig.level).isEmpty() && work.ready(rig.level), "a real stove borrows its sole actual ignition tool");
                helper.assertFalse(rig.step(work), "the first physical step borrows the ignition item from storage");
                helper.assertFalse(rig.step(work), "the next physical step performs native stove ignition once");
                helper.assertTrue(rig.level.getBlockState(rig.boardCell).getValue(
                    com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock.LIT), "the actual stove was lit before the interruption");
                work.released(rig.level, rig.worker, Ending.REVOKED);
                rig.level.setBlockAndUpdate(rig.boardCell, rig.level.getBlockState(rig.boardCell).setValue(
                    com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock.LIT, false));
                rig.level.setBlockAndUpdate(rig.boardCell.above(), Blocks.WATER.defaultBlockState());
                helper.assertFalse(ignition.nodes().getFirst().ready(rig.level), "the cold wet native stove cannot be ignited again yet");
                helper.assertTrue(work.ready(rig.level), "pending completed work may resume physical returns even when original ignition is no longer ready");
                rig.finish(work);
                helper.assertFalse(rig.level.getBlockState(rig.boardCell).getValue(
                    com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.StoveBlock.LIT), "finishing returns never replays native ignition");
                long left = Goods.countIn(rig.chest, Goods.specOf(item.getDefaultInstance()))
                    + Goods.countIn(rig.rack, Goods.specOf(item.getDefaultInstance()))
                    + Goods.countIn(rig.body.pack(), Goods.specOf(item.getDefaultInstance()));
                helper.assertValueEqual(left, item == Items.FIRE_CHARGE ? 0L : 1L,
                    "the consumed last charge is not requested again and the reusable flint returns exactly once");
                if (item == Items.FLINT_AND_STEEL) helper.assertValueEqual(
                    java.util.stream.Stream.of(rig.chest, rig.rack, rig.body.pack()).flatMap(container ->
                        java.util.stream.IntStream.range(0, container.getContainerSize()).mapToObj(container::getItem))
                        .filter(stack -> stack.is(Items.FLINT_AND_STEEL)).mapToInt(ItemStack::getDamageValue).sum(), 1,
                    "the returned real flint retains exactly one native use");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void borrowedWorkerKeepsTheRealCraftingPerksGrantedSpeed(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var perks = Perks.fixed(Map.of(WaresContent.QUICK_HANDS, 2));
            Worker skilled = new Worker() {
                public Resident resident() { return rig.worker.resident(); }
                public Mob body() { return rig.worker.body(); }
                public Container pack() { return rig.worker.pack(); }
                public List<Store> within() { return rig.worker.within(); }
                public ItemStack held(ToolNeed need) { return rig.worker.held(need); }
                public void spill(ItemStack stack) { rig.worker.spill(stack); }
                public Effects effects() { return perks.effects(); }
                public List<Node> route() { return rig.worker.route(); }
                public Placement placement() { return rig.worker.placement(); }
            };
            Worker borrowing = new LoanWorker(skilled, rig.tools, Map.of());
            var craft = Workload.Once.of(100, Workload.Haste.of(WaresContent.CRAFTING));
            helper.assertTrue(skilled.effects().value(Stats.WORK_SPEED, WaresContent.CRAFTING) > 1.0,
                "the registered crafting perk actually grants its scoped work-speed modifier");
            helper.assertValueEqual(craft.ticksFor(borrowing), craft.ticksFor(skilled),
                "borrowing a shared tool preserves the original resident's actual granted crafting speed");
            helper.assertTrue(craft.ticksFor(borrowing) < 100,
                "the loan worker must not replace real effects with an empty effect set");
            helper.assertValueEqual(Workload.Once.of(100, Workload.Haste.of(Vocations.HAULING)).ticksFor(borrowing), 100,
                "the crafting grant remains scoped and does not speed up unrelated hauling");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void decoratingAWorkshopPreservesItsUpstreamOfferSemantics(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            Workshop original = new Workshop() {
                public ResourceLocation id() { return CookeryContent.ID; }
                public UUID key() { return rig.colony.colonyId(); }
                public WorkSite site() { return new WorkSite.AtBlock(rig.boardAt); }
                public boolean alike() { return true; }
                public boolean carries() { return true; }
            };
            Workshop wrapped = rig.tools.decorate(original);
            helper.assertTrue(wrapped.alike() && wrapped.carries(),
                "a shared-tool decorator preserves how the upstream planner selects and sources the original offers");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrappedNativeBoardKeepsOnlyItsUnpromisedBoneAndOneLoanCell(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            rig.body.pack().setItem(0, new ItemStack(Items.CHICKEN));
            Node work = rig.board();
            var meat = Goods.specOf(ModItems.RAW_CUT_SMALL_MEATS.get().getDefaultInstance());
            var bone = Goods.specOf(Items.BONE.getDefaultInstance());
            var extras = work.planningLeaves(List.of(new Amount(meat, 3)));
            helper.assertValueEqual(extras.stream().filter(amount -> amount.spec().equals(meat)).mapToLong(Amount::count).sum(),
                0L, "the decorator forwards the native node's unpromised-output contract");
            helper.assertValueEqual(extras.stream().filter(amount -> amount.spec().equals(bone)).mapToLong(Amount::count).sum(),
                1L, "the real secondary bone still needs its own slot");
            helper.assertValueEqual(extras.stream().filter(amount -> amount.spec().equals(Leaves.UNSEEN)).mapToLong(Amount::count).sum(),
                1L, "borrowing retains one temporary knife cell even when all requested meat is promised");
            rig.finish(work);
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), bone), 1L, "budget changes preserve actual native byproducts");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "the physical knife still returns to its rack");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wrappedAdditiveRemainderKeepsItsOriginalSameItemBudget(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var paper = Goods.specOf(Items.PAPER.getDefaultInstance());
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(rig.boardAt),
                Stances.at(rig.level, rig.boardCell.south()), Workload.Once.of(1)).tools(new ToolUse(KNIFE, 0))
                .gives(new Amount(paper, 1)).leaves(List.of(new Amount(paper, 1))).done();
            Node original = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { throw new AssertionError("budgeting executes no work"); }
            };
            Node decorated = new ToolWork(rig.tools, original);
            var extras = decorated.planningLeaves(List.of(new Amount(paper, 1)));
            helper.assertValueEqual(extras.stream().filter(amount -> amount.spec().equals(paper)).mapToLong(Amount::count).sum(),
                1L, "an ordinary node's same-item remainder is additive, not a duplicate native total");
            helper.assertValueEqual(extras.stream().filter(amount -> amount.spec().equals(Leaves.UNSEEN)).mapToLong(Amount::count).sum(),
                1L, "the shared-tool wrapper also keeps its own loan-space reservation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void twoRealBoardOperationsBorrowOneNamedKnifeAndReturnItToTheRack(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            ItemStack knife = rig.namedKnife();
            rig.rack.setItem(0, knife.copy());
            rig.body.pack().setItem(0, new ItemStack(Items.CHICKEN, 2));
            Node first = rig.board();
            Node second = rig.board();
            helper.assertTrue(first.spec().tools().isEmpty() && second.spec().tools().isEmpty(),
                "food parents can be assigned before borrowing, without giving every crafting worker a knife");
            helper.assertTrue(first.spec().needs().stream().noneMatch(need -> Goods.overlap(need.spec(), KNIVES)),
                "the reusable knife is never a consumed food ingredient");
            rig.finish(first);
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 0L, "the first task physically returns its knife");
            helper.assertTrue(rig.rack.getItem(0).getHoverName().getString().equals("Only shared knife"),
                "the real rack receives the original named knife");
            helper.assertTrue(second.ready(rig.level), "the second task can now borrow the same physical knife");
            rig.finish(second);
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES) + Goods.countIn(rig.chest, KNIVES)
                + Goods.countIn(rig.body.pack(), KNIVES), 1L, "two operations conserve exactly one physical knife");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(ModItems.RAW_CUT_SMALL_MEATS.get().getDefaultInstance())),
                6L, "two real native board batches produce six meat pieces");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE.getDefaultInstance())),
                2L, "both native secondary products are delivered once");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.CHICKEN.getDefaultInstance())),
                0L, "both real chicken inputs are spent once");
            helper.assertTrue(rig.rack.getItem(0).getDamageValue() >= knife.getDamageValue(),
                "native wear is retained by the returned tool");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWholeTwoOperationFoodPlanDoesNotBudgetTheOnlyKnifeTwice(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            var fixture = new PlanningFixture(helper, 36);
            var source = new Stash(fixture.source);
            var stock = new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.CHICKEN, 2)),
                new Stock.Holding(source, rig.namedKnife())), Map.of(source, 27));
            var crafting = rig.body.licences().of(WaresContent.trade()).orElseThrow();
            var hauling = rig.body.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow();
            var hand = new Crew.Hand(rig.body.resident(), fixture.source, 36, 36, 1,
                List.of(crafting, hauling), List.of(), Optional.empty());
            Known known = new Known(fixture.situation(stock, List.of(rig.tools.decorate(rig.workshop()))), List.of(hand));
            ItemSpec meat = Goods.specOf(ModItems.RAW_CUT_SMALL_MEATS.get().getDefaultInstance());
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(fixture.target),
                Stances.at(fixture.target), Workload.Once.of(1)).needs(new Need(meat, 6)).done();
            Node consumer = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            };
            Solving solver = new Solving();
            try {
                var result = solver.handle(known, List.of(new Message.Submitted(CookeryContent.ID, Grown.of(consumer))), 0);
                helper.assertTrue(result.diagnosis().shortfalls().isEmpty() && result.diagnosis().unassigned().isEmpty()
                    && result.diagnosis().refused().isEmpty(), "the real complete food plan is feasible: " + result.diagnosis());
                List<Node> tasks = result.woven().delta().put().values().stream().map(vertex -> vertex.node())
                    .filter(node -> node instanceof ToolWork).toList();
                helper.assertValueEqual(tasks.size(), 2, "the real planner schedules two physical board batches");
                helper.assertTrue(tasks.stream().flatMap(node -> node.spec().needs().stream())
                    .noneMatch(need -> Goods.overlap(need.spec(), KNIVES)), "neither batch consumes a fictional copy of the knife");
            } finally {
                solver.closed();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void missingToolsKeepTheOnlyCraftingWorkerFreeAndMergeOneBootstrapOrder(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            List<Node> submitted = new ArrayList<>();
            rig.colony.works().listen(rig.level.dimension(), new ColonyWorks.Listening() {
                public void submitted(ResourceLocation owner, Grown work) { submitted.addAll(work.nodes()); }
                public void withdrawn(ResourceLocation owner, UUID node) { }
                public void offered() { }
            });
            Node first = rig.simple(new AtomicInteger(), false);
            Node second = rig.simple(new AtomicInteger(), false);
            helper.assertTrue(!first.ready(rig.level) && !second.ready(rig.level),
                "a tool-less food task never occupies the sole crafting worker inside Borrow");
            for (int tick = 0; tick < 60; tick++) {
                rig.tools.tick(rig.level.getServer());
            }
            List<Delivery> orders = submitted.stream().filter(node -> node instanceof Delivery)
                .map(node -> (Delivery) node).toList();
            helper.assertValueEqual(orders.size(), 1, "two waiting tasks produce one stable real bootstrap demand");
            helper.assertValueEqual(orders.getFirst().goods().count(), 1L, "the merged demand asks for one knife only");
            helper.assertTrue(Goods.overlap(orders.getFirst().goods().spec(), KNIVES), "the bootstrap asks for a permitted knife");
            helper.assertTrue(orders.getFirst().spec().tools().isEmpty(), "the knife's delivery does not itself require a knife");
            helper.assertTrue(rig.body.pack().isEmpty() && rig.rack.isEmpty() && rig.chest.isEmpty(),
                "planning a missing tool creates no physical inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancellationAndReloadRecoverOnlyTheActualBorrowedTool(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            AtomicInteger executions = new AtomicInteger();
            Node work = rig.simple(executions, false);
            rig.step(work);
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 1L, "the borrow really moved the rack's knife");
            helper.assertTrue(io.github.izakyl.folkways.core.api.resident.body.TaskTools.holds(rig.body.id(), rig.body.pack().getItem(0)),
                "the actual borrowed stack is protected from ordinary cargo unloading");
            work.ended(rig.level, Ending.REVOKED);
            rig.tools.closed(Closing.SHUTDOWN);
            TaskTools loaded = new TaskTools(rig.colony.works(), rig.level.getServer());
            try {
                helper.assertTrue(io.github.izakyl.folkways.core.api.resident.body.TaskTools.holds(rig.body.id(), rig.body.pack().getItem(0)),
                    "reload protects the saved physical return before the next inventory survey");
                List<Grown> recovery = loaded.goals(rig.colony.works().view(rig.level));
                helper.assertValueEqual(recovery.size(), 1, "one saved loan has one return task");
                rig.finish(recovery.getFirst().nodes().getFirst());
                helper.assertValueEqual(executions.get(), 0, "a cancelled food operation is never replayed by tool recovery");
                helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "the same named knife returns to the native rack");
                helper.assertTrue(rig.body.pack().isEmpty(), "the recovery leaves no duplicated knife in the pack");
                helper.assertTrue(loaded.goals(rig.colony.works().view(rig.level)).isEmpty(), "a completed return does not replay after another scan");
            } finally {
                loaded.closed(Closing.SHUTDOWN);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aBusyToolWaitsAndAFullRackReturnsToOrdinaryStorage(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            Node first = rig.simple(new AtomicInteger(), false);
            Node second = rig.simple(new AtomicInteger(), false);
            rig.step(first);
            helper.assertTrue(!second.ready(rig.level), "a borrowed knife cannot be borrowed by another task");
            var state = rig.tools.pool.request(rig.colony.colonyId(), second.id(), ((ToolWork) second).families().getFirst(),
                rig.tools.stock.read(rig.level, KNIVES).inventory());
            helper.assertTrue(state.replenishment().isEmpty(), "a busy real knife never triggers a second manufacture");
            rig.rack.setItem(0, new ItemStack(ModItems.KITCHEN_SHOVEL.get()));
            rig.rack.setItem(1, new ItemStack(ModItems.SICKLE.get()));
            rig.finish(first);
            helper.assertValueEqual(Goods.countIn(rig.chest, KNIVES), 1L, "a full native rack returns the original knife to an ordinary store");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 0L, "fallback really removes the borrowed pack stack");
            helper.assertTrue(second.ready(rig.level), "the returned fallback knife becomes borrowable again");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayerChangingTheReservedToolDoesNotMoveOrDuplicateEitherStack(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            Node work = rig.simple(new AtomicInteger(), false);
            var turn = ((Workload.Within) work.spec().workload()).next(rig.level, rig.worker);
            Node borrow = ((Workload.Within.Turn.Piece) turn).node();
            rig.rack.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("Player changed this knife"));
            rig.stand(borrow);
            Outcome result = Commit.run(rig.level, borrow, rig.worker);
            helper.assertTrue(result instanceof Outcome.Failed, "the borrowed source is rechecked including components at commit");
            ((Workload.Within) work.spec().workload()).ended(rig.level, borrow, Ending.DROPPED);
            helper.assertTrue(rig.body.pack().isEmpty(), "a stale reservation does not inject a knife into the worker");
            helper.assertTrue(rig.rack.getItem(0).getHoverName().getString().equals("Player changed this knife"),
                "the player's real changed tool stays in its native rack");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "there is still exactly one real tool");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDroppedBorrowedToolRemainsPhysicalStockAndDoesNotTriggerManufacture(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            Node work = rig.simple(new AtomicInteger(), false);
            rig.step(work);
            ItemStack taken = Containers.extract(rig.body.pack(), stack -> stack.is(TagMod.KITCHEN_KNIFE), 1);
            var drop = new net.minecraft.world.entity.item.ItemEntity(rig.level, rig.body.mob().getX(),
                rig.body.mob().getY(), rig.body.mob().getZ(), taken);
            rig.level.addFreshEntity(drop);
            work.ended(rig.level, Ending.REVOKED);
            Node recovery = rig.tools.goals(rig.colony.works().view(rig.level)).getFirst().nodes().getFirst();
            rig.finish(recovery);
            Node next = rig.simple(new AtomicInteger(), false);
            var state = rig.tools.pool.request(rig.colony.colonyId(), next.id(), ((ToolWork) next).families().getFirst(),
                rig.tools.stock.read(rig.level, KNIVES).inventory());
            helper.assertTrue(state.status() == KitchenTools.Status.BUSY && state.replenishment().isEmpty(),
                "the observed physical drop is unavailable stock, never proof that the tool was destroyed");
            helper.assertValueEqual(drop.getItem().getCount(), 1, "waiting does not consume or copy the real dropped knife");
            drop.discard();
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void cancellingAWrappedOnceReleasesTheOriginalExecutionOnlyOnce(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            AtomicInteger released = new AtomicInteger();
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(rig.boardAt),
                Stances.at(rig.level, rig.boardCell.south()), Workload.Once.of(20)).tools(new ToolUse(KNIFE, 0)).done();
            Node original = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { throw new AssertionError("cancelled action must not commit"); }
                public void released(ServerLevel level, Worker who, Ending how) { released.incrementAndGet(); }
            };
            Node work = rig.tools.decorate(Grown.of(original)).nodes().getFirst();
            helper.assertTrue(work.planned(rig.level).isEmpty(), "the original operation is adopted");
            rig.step(work);
            Workload.Within within = (Workload.Within) work.spec().workload();
            Node action = ((Workload.Within.Turn.Piece) within.next(rig.level, rig.worker)).node();
            action.released(rig.level, rig.worker, Ending.DROPPED);
            within.ended(rig.level, action, Ending.DROPPED);
            work.released(rig.level, rig.worker, Ending.REVOKED);
            work.ended(rig.level, Ending.REVOKED);
            helper.assertValueEqual(released.get(), 1, "the original Once execution has only one cancellation callback");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), KNIVES), 1L, "cancellation leaves exactly the real borrowed knife for return recovery");
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void adoptingTheConsumerBeforeItsProducerCannotTakeTheProducersBorrowingTurn(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.rack.setItem(0, rig.namedKnife());
            // WeaveDelta.put is Map.copyOf: adoption order is not dependency order. The actual core only
            // asks ready after checking graph.before, so the consumer is adopted but not yet polled ready.
            AtomicInteger downstreamDone = new AtomicInteger();
            AtomicInteger upstreamDone = new AtomicInteger();
            Node downstream = rig.simple(downstreamDone, false);
            Node upstream = rig.simple(upstreamDone, false);
            rig.tools.tick(rig.level.getServer());
            helper.assertTrue(upstream.ready(rig.level),
                "an adopted downstream task and the supply scan do not reserve a queue position before food prerequisites finish");
            rig.finish(upstream);
            helper.assertValueEqual(upstreamDone.get(), 1, "the actual upstream tool action completes first");
            helper.assertTrue(downstream.ready(rig.level), "after the producer returns its real knife, its consumer can borrow it");
            rig.finish(downstream);
            helper.assertValueEqual(downstreamDone.get(), 1, "the actual dependent tool action then completes once");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "reverse adoption still uses exactly one returned native knife");
            helper.assertTrue(rig.body.pack().contents().stream().noneMatch(stack -> stack.is(TagMod.KITCHEN_KNIFE)),
                "both actual tasks leave the reusable knife in the rack");
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void nativeBreakageReleasesTheLoanAndAllowsOneReplacement(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            ItemStack lastUse = rig.namedKnife();
            lastUse.setDamageValue(lastUse.getMaxDamage() - 1);
            rig.rack.setItem(0, lastUse);
            AtomicInteger executions = new AtomicInteger();
            Node work = rig.simple(executions, true);
            rig.finish(work);
            helper.assertValueEqual(executions.get(), 1, "the last real tool use is executed exactly once");
            helper.assertTrue(rig.tools.pool.leases(rig.colony.colonyId()).isEmpty(), "confirmed native breakage releases the physical loan");
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES) + Goods.countIn(rig.chest, KNIVES)
                + Goods.countIn(rig.body.pack(), KNIVES), 0L, "native destruction does not return a fresh replacement");
            Node next = rig.simple(new AtomicInteger(), false);
            helper.assertTrue(!next.ready(rig.level), "the next task waits outside the worker until replacement exists");
            var state = rig.tools.pool.request(rig.colony.colonyId(), next.id(), ((ToolWork) next).families().getFirst(),
                rig.tools.stock.read(rig.level, KNIVES).inventory());
            helper.assertValueEqual(state.replenishment().orElseThrow().count(), 1L, "only a confirmed lost tool permits one replacement");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void twoActuallyBorrowedRackKnivesDoNotRefillTheirStandingTarget(GameTestHelper helper) throws Exception {
        try (var loans = new LoanRig(helper)) {
            Rig rig = loans.rig;
            loans.stock(rig.rack);
            loans.file(rig.rackAt, 2, true);
            loans.borrow();
            helper.assertTrue(rig.rack.isEmpty(), "two different residents actually emptied the real rack by borrowing");
            helper.assertValueEqual(OrderReturns.count(rig.colony.works(), rig.rackAt, KNIVES), 2L,
                "two physical tools are promised back to this specific rack");
            loans.refresh();
            helper.assertTrue(Orders.commitments(rig.colony.works(), KNIVES).isEmpty(),
                "standing maintain two never starts another Filling while its two real tools are in use");
            loans.returnOne(rig.tools, loans.other);
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "one actual tool has returned");
            helper.assertValueEqual(OrderReturns.count(rig.colony.works(), rig.rackAt, KNIVES), 1L,
                "that completed physical return stops being counted as a promise");
            loans.refresh();
            helper.assertTrue(Orders.commitments(rig.colony.works(), KNIVES).isEmpty(), "one actual plus one returning still satisfies exactly two");
            loans.returnOne(rig.tools, rig.body);
            helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 2L, "both original physical knives are back");
            helper.assertValueEqual(OrderReturns.count(rig.colony.works(), rig.rackAt, KNIVES), 0L, "returned inventory is never counted twice");
            loans.refresh();
            helper.assertTrue(Orders.commitments(rig.colony.works(), KNIVES).isEmpty(), "ordinary real stock still satisfies the same target");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStandingThreeToolWarehouseOnlyOrdersItsOneRealGap(GameTestHelper helper) throws Exception {
        try (var loans = new LoanRig(helper)) {
            Rig rig = loans.rig;
            loans.stock(rig.chest);
            // Three is a legitimate warehouse target; the native two-slot rack cannot physically hold three.
            loans.file(rig.chestAt, 3, true);
            loans.borrow();
            helper.assertTrue(rig.chest.isEmpty(), "the same two real warehouse tools were borrowed");
            helper.assertValueEqual(OrderReturns.count(rig.colony.works(), rig.chestAt, KNIVES), 2L,
                "a standing stock warehouse retains the return destination of its borrowed inventory");
            helper.assertValueEqual(OrderReturns.count(rig.colony.works(), rig.rackAt, KNIVES), 0L,
                "the same two tools cannot be promised to another target as well");
            loans.refresh();
            helper.assertValueEqual(Orders.commitments(rig.colony.works(), KNIVES).values().stream().mapToLong(Long::longValue).sum(), 1L,
                "maintain three with two actual returning tools asks for precisely one more");
            loans.file(rig.chestAt, 3, false);
            loans.refresh();
            helper.assertValueEqual(Orders.commitments(rig.colony.works(), KNIVES).values().stream().mapToLong(Long::longValue).sum(), 3L,
                "a one-shot delivery retains its original actual-target-stock meaning");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancelAndReloadRetainReturnsButAStolenPhysicalKnifeDoesNot(GameTestHelper helper) throws Exception {
        try (var loans = new LoanRig(helper)) {
            Rig rig = loans.rig;
            loans.stock(rig.rack);
            loans.file(rig.rackAt, 2, true);
            loans.borrow();
            rig.tools.closed(Closing.SHUTDOWN);
            TaskTools restored = new TaskTools(rig.colony.works(), rig.level.getServer());
            try {
                helper.assertTrue(restored.pool.leases(rig.colony.colonyId()).stream().allMatch(lease -> lease.phase() == KitchenTools.Phase.RETURNING),
                    "reload restores only physical returns, never running food actions");
                helper.assertValueEqual(OrderReturns.count(rig.colony.works(), rig.rackAt, KNIVES), 2L,
                    "the two actual borrower packs and their saved return target survive reload");
                loans.refresh();
                helper.assertTrue(Orders.commitments(rig.colony.works(), KNIVES).isEmpty(), "reload cannot create a duplicate two-knife refill");
                int slot = IntStream.range(0, loans.other.pack().getContainerSize()).filter(index ->
                    Goods.matches(loans.other.pack().getItem(index), KNIVES)).findFirst().orElseThrow();
                ItemStack stolen = loans.other.pack().removeItem(slot, 1);
                helper.assertTrue(!stolen.isEmpty(), "the player really takes an existing borrowed tool");
                helper.assertValueEqual(OrderReturns.count(rig.colony.works(), rig.rackAt, KNIVES), 1L,
                    "an item absent from its loaded physical borrower cannot still promise a return");
                loans.refresh();
                helper.assertValueEqual(Orders.commitments(rig.colony.works(), KNIVES).values().stream().mapToLong(Long::longValue).sum(), 1L,
                    "the one genuine missing tool creates one standing refill");
                loans.returnOne(restored, rig.body);
                helper.assertValueEqual(OrderReturns.count(rig.colony.works(), rig.rackAt, KNIVES), 0L,
                    "returning the surviving tool settles only its own promise");
                helper.assertValueEqual(Goods.countIn(rig.rack, KNIVES), 1L, "exactly that original surviving knife is really in the rack");
                helper.assertTrue(Containers.insert(rig.chest, stolen).isEmpty(), "the stolen physical item remains accounted for outside the target");
            } finally { restored.closed(Closing.SHUTDOWN); }
        }
        helper.succeed();
    }

    private static final class LoanRig implements AutoCloseable {
        final Rig rig;
        final Body other;
        final Worker second;
        LoanRig(GameTestHelper helper) {
            rig = new Rig(helper);
            other = PersonBody.RESIDENT.get().create(rig.level);
            other.mob().setNoAi(true);
            other.mob().moveTo(rig.boardCell.getCenter()); rig.level.addFreshEntity(other.mob());
            rig.colony.works().hold(other.kind().id(), Held.Entity.of(other.mob())).orElseThrow();
            second = new Worker() {
                public Resident resident() { return other.resident(); }
                public Mob body() { return other.mob(); }
                public Container pack() { return other.pack(); }
                public List<Store> within() { return rig.worker.within(); }
                public ItemStack held(ToolNeed need) { return Tools.from(other.pack().contents(), need).orElse(ItemStack.EMPTY); }
                public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new AssertionError("unexpected spill: " + stack); }
                public Effects effects() { return other.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }
        void stock(Container into) {
            into.setItem(0, rig.namedKnife());
            ItemStack another = rig.namedKnife(); another.setDamageValue(37);
            another.set(DataComponents.CUSTOM_NAME, Component.literal("Second shared knife"));
            into.setItem(1, another);
        }
        void file(WorldPos at, int count, boolean standing) {
            var front = ColonyFront.of(rig.colony.works());
            front.setSetting(OrdersContent.ID, "item", new ColonySettings.Value.Items(List.of(
                ItemFilter.item(BuiltInRegistries.ITEM.getKey(ModItems.IRON_KITCHEN_KNIFE.get())))));
            // Set the player low-water threshold to the target so any genuine gap triggers a batch.
            int low = standing ? count : 1;
            front.setSetting(OrdersContent.ID, "low", new ColonySettings.Value.Count(low));
            front.setSetting(OrdersContent.ID, "count", new ColonySettings.Value.Count(count));
            // setSetting reports a change, so an already selected value legitimately returns false.
            var settings = front.settings(OrdersContent.ID);
            rig.helper.assertTrue(settings.items("item").filter(goods -> Goods.members(goods).equals(
                java.util.Set.of(ModItems.IRON_KITCHEN_KNIFE.get()))).isPresent(), "the real player item setting selects only an iron knife");
            rig.helper.assertValueEqual(settings.count("low"), low, "the real low-water setting preserves the player trigger");
            rig.helper.assertValueEqual(settings.count("count"), count, "the real high-water target is filed");
            rig.helper.assertTrue(!Facings.of(rig.colony.works(), OrdersContent.ID).orElseThrow().act(OrdersContent.ID,
                standing ? "maintain" : "deliver", Optional.of(at.block(rig.level)), rig.colony.works().view(rig.level)).refused(),
                "the actual front action creates the player order");
        }
        void refresh() throws Exception {
            Object orders = rig.colony.works().service(OrdersContent.ID, Object.class).orElseThrow();
            var refresh = orders.getClass().getDeclaredMethod("refresh", net.minecraft.server.MinecraftServer.class);
            refresh.setAccessible(true); refresh.invoke(orders, rig.level.getServer());
        }
        void borrow() {
            Node first = rig.simple(new AtomicInteger(), false);
            Node next = rig.simple(new AtomicInteger(), false);
            borrow(first, rig.worker); borrow(next, second);
        }
        void borrow(Node work, Worker who) {
            rig.helper.assertTrue(work.ready(rig.level), "an actual additional knife permits the next borrowing turn");
            var turn = ((Workload.Within)work.spec().workload()).next(rig.level, who);
            rig.helper.assertTrue(turn instanceof Workload.Within.Turn.Piece piece && piece.node() instanceof ToolAction.Borrow,
                "the first real preparation is a physical borrow");
            Node piece = ((Workload.Within.Turn.Piece)turn).node();
            stand(piece, who.body());
            rig.helper.assertTrue(Commit.run(rig.level, piece, who) instanceof Outcome.Done, "the real source transfers one knife into this borrower's pack");
            ((Workload.Within)work.spec().workload()).ended(rig.level, piece, Ending.DONE);
        }
        void returnOne(TaskTools service, Body borrower) {
            var lease = service.pool.leases(rig.colony.colonyId()).stream().filter(item -> item.resident().equals(borrower.id())).findFirst().orElseThrow();
            var target = service.destination(rig.level, lease, service.tool(lease).orElseThrow()).orElseThrow();
            var returning = new ToolAction.Return(service, lease, target);
            Worker who = borrower.id().equals(rig.body.id()) ? rig.worker : second;
            stand(returning, who.body());
            rig.helper.assertTrue(Commit.run(rig.level, returning, who) instanceof Outcome.Done, "the original physical tool is really returned to its promised target");
        }
        static void stand(Node work, Mob body) {
            var at = work.spec().stances().cells().stream().findFirst().orElseThrow().cell();
            body.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        }
        public void close() { other.mob().discard(); rig.close(); }
    }
    private static final class Rig implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos boardCell;
        final WorldPos boardAt;
        final WorldPos rackAt;
        final WorldPos chestAt;
        final Container rack;
        final Container chest;
        final Body body;
        final ColonyData colony;
        final TaskTools tools;
        final KitchenBookings bookings = new KitchenBookings();
        final Map<UUID, List<Hold>> holds = new java.util.LinkedHashMap<>();
        final Worker worker;

        Rig(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            boardCell = helper.absolutePos(new BlockPos(3, 1, 3));
            for (BlockPos floor : BlockPos.betweenClosed(boardCell.offset(-4, -1, -4), boardCell.offset(4, -1, 4))) {
                level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                for (int up = 1; up <= 3; up++) {
                    level.setBlockAndUpdate(floor.above(up), Blocks.AIR.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(boardCell, ModBlocks.CHOPPING_BOARD.get().defaultBlockState());
            BlockPos rackCell = boardCell.west(3);
            BlockPos chestCell = boardCell.east(3);
            level.setBlockAndUpdate(rackCell, ModBlocks.KITCHENWARE_RACKS.get().defaultBlockState());
            level.setBlockAndUpdate(chestCell, Blocks.CHEST.defaultBlockState());
            KitchenRacks.register();
            TaskTools.register();
            rack = Containers.at(level, rackCell).orElseThrow();
            chest = Containers.at(level, chestCell).orElseThrow();
            boardAt = WorldPos.of(level, boardCell);
            rackAt = WorldPos.of(level, rackCell);
            chestAt = WorldPos.of(level, chestCell);
            body = PersonBody.RESIDENT.get().create(level);
            body.mob().setNoAi(true);
            body.mob().moveTo(boardCell.getX() + 0.5, boardCell.getY(), boardCell.getZ() + 1.5);
            level.addFreshEntity(body.mob());
            colony = ColonyRegistry.get(level.getServer()).mint(level.getServer());
            colony.works().hold(body.kind().id(), Held.Entity.of(body.mob())).orElseThrow();
            colony.works().stores(CookeryContent.ID, level.dimension(), List.of(chestAt, rackAt));
            tools = new TaskTools(colony.works(), level.getServer());
            worker = new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return Rig.this.body.mob(); }
                public Container pack() { return Rig.this.body.pack(); }
                public List<Store> within() { return List.of(new Store(rackAt, rack), new Store(chestAt, chest)); }
                public ItemStack held(ToolNeed need) { return Tools.from(Rig.this.body.pack().contents(), need).orElse(ItemStack.EMPTY); }
                public void spill(ItemStack stack) { if (!stack.isEmpty()) { throw new AssertionError("unexpected spill: " + stack); } }
                public Effects effects() { return Rig.this.body.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }

        ItemStack namedKnife() {
            ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
            knife.setDamageValue(23);
            knife.set(DataComponents.CUSTOM_NAME, Component.literal("Only shared knife"));
            return knife;
        }

        Workshop workshop() {
            return ChoppingBoardWorkshops.freeze(UUID.randomUUID(), colony.colonyId(), level, boardAt,
                Stances.at(level, boardCell.south()), bookings, () -> { }).orElseThrow();
        }

        Node board() {
            Produce intent = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(boardAt),
                Goods.specOf(ModItems.RAW_CUT_SMALL_MEATS.get().getDefaultInstance()), 1, Produce.UNBOUNDED);
            Refinement.Change change = tools.decorate(workshop()).options(intent, Grown.of(intent)).stream()
                .filter(option -> option.replacement().nodes().getFirst().spec().needs().stream()
                    .allMatch(need -> Goods.countIn(body.pack(), need.spec()) >= need.count()))
                .findFirst().orElseThrow();
            for (Hold hold : change.holds()) {
                helper.assertTrue(hold.take().isEmpty(), "the actual board turn is booked");

            }
            Node work = change.replacement().nodes().getFirst();
            holds.put(work.id(), change.holds());
            helper.assertTrue(work.planned(level).isEmpty(), "the actual shared-tool task is adopted");
            return work;
        }

        Node simple(AtomicInteger executions, boolean breaks) {
            return simple(executions, breaks, io.github.izakyl.folkways.plugins.wares.WaresContent.trade(), KNIFE);
        }

        Node simple(AtomicInteger executions, boolean breaks,
                io.github.izakyl.folkways.core.api.vocation.Vocation vocation, ToolNeed need) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(boardAt),
                Stances.at(level, boardCell.south()), Workload.Once.of(1))
                .vocation(vocation).tools(new ToolUse(need, 0))
                .gives(new Amount(Goods.specOf(Items.PAPER.getDefaultInstance()), 1)).done();
            Node action = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) {
                    ItemStack tool = who.held(need);
                    helper.assertTrue(!tool.isEmpty(), "the original action sees a physical held shared knife");
                    if (breaks) {
                        tool.hurtAndBreak(1, level, who.body(), ignored -> { });
                    }
                    executions.incrementAndGet();
                    return new Outcome.Done(List.of(), List.of(new ItemStack(Items.PAPER)), Optional.empty());
                }
            };
            Node wrapped = tools.decorate(Grown.of(action)).nodes().getFirst();
            helper.assertTrue(wrapped.planned(level).isEmpty(), "the tool-requiring action is adopted");
            return wrapped;
        }

        void stand(Node piece) {
            piece.spec().stances().cells().stream().findFirst().ifPresent(at ->
                body.mob().moveTo(at.cell().getX() + 0.5, at.cell().getY(), at.cell().getZ() + 0.5));
        }

        boolean step(Node work) {
            Workload.Within within = (Workload.Within) work.spec().workload();
            var turn = within.next(level, worker);
            if (turn instanceof Workload.Within.Turn.Leave) {
                helper.assertTrue(work.commit(level, worker) instanceof Outcome.Done, "the completed parent settles without replaying its action");
                work.released(level, worker, Ending.DONE);
                work.ended(level, Ending.DONE);
                for (Hold hold : holds.getOrDefault(work.id(), List.of())) {
                    hold.release(Ending.DONE);
                }
                return true;
            }
            helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "the real shared-tool task does not stall: " + turn);
            Node piece = ((Workload.Within.Turn.Piece) turn).node();
            stand(piece);
            helper.assertTrue(piece.planned(level).isEmpty(), "the real preparation/action piece can start");
            Outcome outcome = Commit.run(level, piece, worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "the actual physical commit succeeds: " + outcome);
            for (ItemStack product : ((Outcome.Done) outcome).made()) {
                helper.assertTrue(Containers.insert(body.pack(), product).isEmpty(), "all native food products fit in reserved pack space");
            }
            piece.released(level, worker, Ending.DONE);
            piece.ended(level, Ending.DONE);
            within.ended(level, piece, Ending.DONE);
            return false;
        }

        void finish(Node work) {
            for (int step = 0; step < 32; step++) {
                if (step(work)) {
                    return;
                }
            }
            throw new AssertionError("the shared-tool task never completed");
        }

        @Override
        public void close() {
            tools.closed(Closing.SHUTDOWN);
            holds.values().stream().flatMap(List::stream).forEach(hold -> hold.release(Ending.REVOKED));
            ColonyRegistry.get(level.getServer()).raze(level.getServer(), colony.colonyId());
            body.mob().discard();
        }
    }
}
