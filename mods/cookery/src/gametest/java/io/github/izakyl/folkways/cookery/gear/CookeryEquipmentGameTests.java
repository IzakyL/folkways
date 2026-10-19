package io.github.izakyl.folkways.cookery.gear;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.CookeryPresence;
import io.github.izakyl.folkways.cookery.seeds.GrassSources;
import io.github.izakyl.folkways.cookery.tools.TaskTools;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import io.github.izakyl.folkways.core.engine.colony.ColonyWorks;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
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
public final class CookeryEquipmentGameTests {
    private static final ItemSpec HATS = ItemSpec.of(TagMod.STRAW_HAT);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryEquipmentGameTests.class); }

    @GameTest(template = "empty")
    public static void idleAndVanillaFarmWorkNeverRequestOrEquipCookeryClothing(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.stockHat();
            rig.body.pack().setItem(0, new ItemStack(ModItems.STRAW_HAT.get()));
            helper.assertTrue(Equipment.wanted(rig.colony.works(), rig.body).isEmpty(), "licensed idle farmers have no standing clothing kit");
            Equipment.reconcile(rig.colony.works(), rig.body);
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "an idle real hat is not automatically worn");
            Node ordinary = rig.plain(FarmingContent.ID, rig.grass, Stances.at(rig.level, rig.grass.south().above()));
            helper.assertTrue(rig.tools.decorate(Grown.of(ordinary)).nodes().getFirst() == ordinary,
                "ordinary dry vanilla farming is not wrapped in a clothing loan");
            Node kitchen = rig.plain(CookeryContent.ID, rig.grass, Stances.at(rig.level, rig.grass.south().above()));
            helper.assertTrue(rig.tools.decorate(Grown.of(kitchen)).nodes().getFirst() == kitchen,
                "ordinary kitchen tasks have no hat benefit and request no outfit");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void twoRealFarmersBorrowOneNamedHatInTurnAndReturnItsOriginalComponents(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            ItemStack expected = rig.stockHat();
            ResidentEntity other = rig.addBody();
            Node first = rig.gather(rig.body);
            Node second = rig.gather(other);
            helper.assertTrue(first.ready(rig.level), "the first task can borrow the one real store hat");
            Node action = rig.prepare(first, rig.body);
            helper.assertTrue(ItemStack.matches(expected, rig.body.getItemBySlot(EquipmentSlot.HEAD)), "the named physical hat occupies the first native HEAD");
            helper.assertTrue(rig.body.pack().isEmpty(), "wearing creates no pack copy");
            helper.assertFalse(second.ready(rig.level), "a second farmer cannot reserve the already borrowed real hat");
            rig.execute(first, action, rig.body);
            rig.finish(first, rig.body);
            helper.assertTrue(rig.level.getBlockState(rig.grass.above()).isAir(), "the original native grass task actually ran");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the first task returns its hat rather than keeping a permanent kit");
            rig.level.setBlockAndUpdate(rig.grass.above(), Blocks.SHORT_GRASS.defaultBlockState());
            helper.assertTrue(second.ready(rig.level), "the same one returned hat becomes available to the other farmer");
            rig.finish(second, other);
            helper.assertTrue(other.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "the second task also leaves its resident bare-headed");
            helper.assertValueEqual(rig.hats(), 1L, "two residents and two native tasks conserve one physical hat");
            helper.assertTrue(ItemStack.matches(expected, rig.store.getItem(0)), "return preserves the original name, damage and components");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aMissingFirstHatCreatesOneSharedDeliveryWhileBothResidentsStayFree(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            var other = rig.addBody();
            List<Node> submitted = new ArrayList<>();
            rig.colony.works().listen(rig.level.dimension(), new ColonyWorks.Listening() {
                public void submitted(net.minecraft.resources.ResourceLocation owner, Grown work) { submitted.addAll(work.nodes()); }
                public void withdrawn(net.minecraft.resources.ResourceLocation owner, UUID node) { }
                public void offered() { }
            });
            Node first = rig.gather(rig.body);
            Node second = rig.gather(other);
            helper.assertFalse(first.ready(rig.level), "missing clothing leaves the first resident outside the borrowing task");
            helper.assertFalse(second.ready(rig.level), "the second resident stays free to craft the missing shared hat too");
            for (int tick = 0; tick < 60; tick++) rig.tools.tick(rig.level.getServer());
            var deliveries = submitted.stream().filter(node -> node instanceof Delivery).map(node -> (Delivery) node).toList();
            helper.assertValueEqual(deliveries.size(), 1, "two adopted native grass tasks merge exactly one bootstrap delivery");
            helper.assertValueEqual(deliveries.getFirst().goods().count(), 1L, "bootstrap is one shared item, not the resident count");
            helper.assertTrue(Goods.overlap(deliveries.getFirst().goods().spec(), HATS), "the real delivery asks for an allowed hat");
            helper.assertTrue(deliveries.getFirst().spec().site() instanceof WorkSite.AtBlock, "the first hat belongs to shared storage, not a permanent personal kit");
            helper.assertValueEqual(rig.hats(), 0L, "planning never fabricates an item or native HEAD equipment");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void cancellationUnwearsOnlyItsLoanAndRealRecoveryReturnsItWithoutHarvesting(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.stockHat();
            Node task = rig.gather(rig.body);
            rig.prepare(task, rig.body);
            task.ended(rig.level, Ending.REVOKED);
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "cancelling immediately unwears only the actual loan");
            var returns = rig.tools.goals(rig.colony.works().view(rig.level));
            helper.assertValueEqual(returns.size(), 1, "one cancelled physical hat has one ordinary storage return");
            rig.finish(returns.getFirst().nodes().getFirst(), rig.body);
            helper.assertTrue(rig.level.getBlockState(rig.grass.above()).is(Blocks.SHORT_GRASS), "recovery never replays cancelled seed gathering");
            helper.assertValueEqual(Goods.countIn(rig.store, HATS), 1L, "the one original hat returns to actual storage");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disablingBorrowingOrTheFarmLicenceRefusesThePreparedActionAndReturnsTheHat(GameTestHelper helper) {
        for (boolean licence : List.of(false, true)) {
            try (var rig = new Rig(helper)) {
                rig.stockHat();
                Node task = rig.gather(rig.body);
                Node action = rig.prepare(task, rig.body);
                if (licence) rig.body.licences().allow(rig.body.licences().trade(FarmingContent.trade()).orElseThrow(), false);
                else rig.flag("wear_hat", false);
                helper.assertTrue(task.refusal(rig.level).isPresent(), "a prepared task rechecks its real clothing permission");
                helper.assertTrue(Commit.run(rig.level, action, rig.worker(rig.body)) instanceof Outcome.Failed,
                    "already issued native work cannot execute after borrowing permission is removed");
                task.ended(rig.level, Ending.REVOKED);
                var returns = rig.tools.goals(rig.colony.works().view(rig.level));
                helper.assertValueEqual(returns.size(), 1, "permission removal preserves the physical return");
                rig.finish(returns.getFirst().nodes().getFirst(), rig.body);
                helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "disabled work leaves no task clothing on the body");
                helper.assertValueEqual(Goods.countIn(rig.store, HATS), 1L, "the same one real hat comes back");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayersOwnSuitableHatIsUsedWithoutBecomingAColonyLoan(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            ItemStack playerHat = new ItemStack(ModItems.STRAW_HAT_FLOWER.get());
            playerHat.set(DataComponents.CUSTOM_NAME, Component.literal("player property"));
            rig.body.setItemSlot(EquipmentSlot.HEAD, playerHat);
            rig.finish(rig.gather(rig.body), rig.body);
            helper.assertTrue(ItemStack.matches(playerHat, rig.body.getItemBySlot(EquipmentSlot.HEAD)), "native task may use the player's suitable hat but never unwears it");
            helper.assertTrue(rig.tools.goals(rig.colony.works().view(rig.level)).isEmpty(), "player equipment does not become shared return inventory");
            helper.assertValueEqual(Goods.countIn(rig.store, HATS), 0L, "the task did not move player property into a colony store");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), HATS), 0L, "there was no shadow player hat in cargo");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anUnrelatedPlayerHelmetIsNeverOverwrittenBySeedOrPaddyClothing(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.stockHat();
            rig.body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
            Node seed = rig.decorate(rig.seed(rig.body));
            helper.assertTrue(seed.planned(rig.level).isPresent(), "native hat loot cannot be assigned over a player's unrelated helmet");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET), "the unrelated helmet remains physical native equipment");
            helper.assertValueEqual(Goods.countIn(rig.store, HATS), 1L, "no unnecessary shared hat is borrowed");
            helper.assertFalse(Equipment.managed(rig.body, EquipmentSlot.HEAD), "player gear is not reclassified as a managed permanent kit");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPartialFarmerSetCannotMoveOrHoldHalfAnOutfit(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.flag("wear_farmer_armor", true);
            rig.stockHat();
            rig.store.setItem(1, new ItemStack(ModItems.FARMER_CHEST_PLATE.get()));
            rig.store.setItem(2, new ItemStack(ModItems.FARMER_LEGGINGS.get()));
            Node task = rig.paddy();
            helper.assertFalse(task.ready(rig.level), "a set missing boots cannot occupy a resident");
            var turn = ((Workload.Within) task.spec().workload()).next(rig.level, rig.worker(rig.body));
            helper.assertTrue(turn instanceof Workload.Within.Turn.Wait, "even a start race waits before moving any partial clothing");
            helper.assertTrue(rig.body.pack().isEmpty(), "no half-set is removed from shared storage");
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                helper.assertTrue(rig.body.getItemBySlot(slot).isEmpty(), "no individual set piece is prematurely worn");
            }
            helper.assertTrue(rig.colony.works().kept(TaskTools.ID).getList("loans", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty(),
                "failed group reservation leaves no physical lease blocking other work");
            task.ended(rig.level, Ending.REVOKED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 90)
    public static void aBorrowedWholeFarmerSetReceivesTheNativeWaterEffectAndThenReturns(GameTestHelper helper) {
        var rig = new Rig(helper);
        rig.flag("wear_farmer_armor", true);
        rig.stockHat();
        rig.store.setItem(1, new ItemStack(ModItems.FARMER_CHEST_PLATE.get()));
        rig.store.setItem(2, new ItemStack(ModItems.FARMER_LEGGINGS.get()));
        rig.store.setItem(3, new ItemStack(ModItems.FARMER_BOOTS.get()));
        Node task = rig.paddy();
        Node action = rig.prepare(task, rig.body);
        helper.runAtTickTime(30, () -> {
            try (rig) {
                helper.assertTrue(rig.body.isInWater(), "the actual borrower is submerged at its real wet work stance");
                helper.assertTrue(rig.body.hasEffect(MobEffects.DOLPHINS_GRACE), "Cookery's native armor event sees all four borrowed real slots");
                rig.execute(task, action, rig.body);
                rig.finish(task, rig.body);
                for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                    helper.assertTrue(rig.body.getItemBySlot(slot).isEmpty(), "completed wet work returns each borrowed outfit slot");
                }
                helper.assertValueEqual(rig.store.countItem(ModItems.STRAW_HAT.get()), 1, "the one shared hat returns");
                helper.assertValueEqual(rig.store.countItem(ModItems.FARMER_CHEST_PLATE.get()), 1, "the one shared chest piece returns");
                helper.assertValueEqual(rig.store.countItem(ModItems.FARMER_LEGGINGS.get()), 1, "the one shared leggings piece returns");
                helper.assertValueEqual(rig.store.countItem(ModItems.FARMER_BOOTS.get()), 1, "the one shared boots pair returns");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void nativeBodyAndLoanReloadRecoverTheWornHatWithoutResumingItsTask(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            ItemStack expected = rig.stockHat();
            Node task = rig.gather(rig.body);
            rig.prepare(task, rig.body);
            CompoundTag saved = new CompoundTag(); rig.body.saveWithoutId(saved);
            rig.body.discard();
            rig.tools.closed(Closing.SHUTDOWN);
            ResidentEntity restoredBody = PersonBody.RESIDENT.get().create(rig.level);
            restoredBody.load(saved); rig.level.addFreshEntity(restoredBody); rig.bodies.add(restoredBody);
            TaskTools restored = new TaskTools(rig.colony.works(), rig.level.getServer());
            try {
                helper.assertTrue(ItemStack.matches(expected, restoredBody.getItemBySlot(EquipmentSlot.HEAD)), "native NBT retained the physical worn loan and its components");
                var returns = restored.goals(rig.colony.works().view(rig.level));
                helper.assertValueEqual(returns.size(), 1, "reload has one return and no resumed seed task");
                rig.finish(returns.getFirst().nodes().getFirst(), restoredBody);
                helper.assertTrue(restoredBody.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "reload recovery unwears its own saved native slot");
                helper.assertTrue(ItemStack.matches(expected, rig.store.getItem(0)), "the one saved loan physically returns unchanged");
                helper.assertTrue(rig.level.getBlockState(rig.grass.above()).is(Blocks.SHORT_GRASS), "reloading never harvests an abandoned task");
            } finally { restored.closed(Closing.SHUTDOWN); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayerReplacementFromTheSameHatFamilyNeverBecomesTheOldLoan(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            ItemStack original = rig.stockHat();
            Node task = rig.gather(rig.body);
            Node action = rig.prepare(task, rig.body);
            var playerInventory = new net.minecraft.world.SimpleContainer(1);
            playerInventory.setItem(0, rig.body.getItemBySlot(EquipmentSlot.HEAD));
            ItemStack replacement = new ItemStack(ModItems.STRAW_HAT.get());
            replacement.set(DataComponents.CUSTOM_NAME, Component.literal("player replacement, never borrowed"));
            rig.body.setItemSlot(EquipmentSlot.HEAD, replacement);
            helper.assertTrue(Commit.run(rig.level, action, rig.worker(rig.body)) instanceof Outcome.Failed,
                "an already prepared action rejects a replaced loan even from the same native hat family");
            task.ended(rig.level, Ending.REVOKED);
            helper.assertTrue(ItemStack.matches(replacement, rig.body.getItemBySlot(EquipmentSlot.HEAD)),
                "cancelling clears only stale ownership and never unwears player replacement B");
            for (Grown recovery : rig.tools.goals(rig.colony.works().view(rig.level))) rig.finish(recovery.nodes().getFirst(), rig.body);
            helper.assertTrue(ItemStack.matches(replacement, rig.body.getItemBySlot(EquipmentSlot.HEAD)), "return recovery still preserves B in its original native slot");
            helper.assertTrue(ItemStack.matches(original, playerInventory.getItem(0)), "the original loan A remains exactly once in the real external inventory");
            helper.assertValueEqual(Goods.countIn(rig.store, HATS), 0L, "neither a replacement nor a synthetic copy is returned into shared stock");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), HATS), 0L, "the player replacement is never silently reclassified as cargo");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void suitablePlayerEquipmentReservesNoFictionalBorrowingCargoCell(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.body.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.STRAW_HAT_FLOWER.get()));
            Node task = rig.gather(rig.body);
            helper.assertTrue(task.spec().leaves().isEmpty() && task.planningLeaves(List.of()).isEmpty(),
                "a task using a pre-existing player hat adds zero borrowed-item cargo cells before and after adoption");
            helper.assertTrue(task.ready(rig.level), "the task can start without a shared-store hat or phantom pack room");
            rig.finish(task, rig.body);
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.STRAW_HAT_FLOWER.get()), "zero-budget use never takes ownership of the player hat");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFullCargoPackKeepsTheOwnedLoanSafeUntilRealReturnRoomExists(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            ItemStack expected = rig.stockHat();
            Node task = rig.gather(rig.body); rig.prepare(task, rig.body);
            for (int slot = 0; slot < rig.body.pack().getContainerSize(); slot++) rig.body.pack().setItem(slot, new ItemStack(Items.DIRT, 64));
            task.ended(rig.level, Ending.REVOKED);
            helper.assertTrue(ItemStack.matches(expected, rig.body.getItemBySlot(EquipmentSlot.HEAD)), "full cargo does not delete or drop the owned garment");
            var returns = rig.tools.goals(rig.colony.works().view(rig.level));
            helper.assertValueEqual(returns.size(), 1, "the real full-pack garment remains an outstanding physical return");
            Node returning = returns.getFirst().nodes().getFirst();
            helper.assertTrue(((Workload.Within) returning.spec().workload()).next(rig.level, rig.worker(rig.body)) instanceof Workload.Within.Turn.Wait,
                "physical unwear waits for one actual cargo slot");
            rig.body.pack().setItem(0, ItemStack.EMPTY);
            rig.finish(returning, rig.body);
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "after room is freed the actual loan is unwearable and returnable");
            helper.assertTrue(ItemStack.matches(expected, rig.store.getItem(0)), "the exact garment reaches real shared storage once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingTheClothingSelectionCancelsItsActiveLoanButStillReturnsTheOldPhysicalHat(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            ItemStack expected = rig.stockHat(); Node task = rig.gather(rig.body); Node action = rig.prepare(task, rig.body);
            ColonyFront.of(rig.colony.works()).setSetting(CookeryContent.ID, "clothing", new ColonySettings.Value.Items(List.of(
                io.github.izakyl.folkways.core.api.terms.ItemFilter.item(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(ModItems.STRAW_HAT_FLOWER.get())))));
            helper.assertTrue(task.refusal(rig.level).isPresent(), "narrowing the real clothing selector invalidates the old active request");
            helper.assertTrue(Commit.run(rig.level, action, rig.worker(rig.body)) instanceof Outcome.Failed,
                "already issued work cannot use the now-disallowed original hat");
            task.ended(rig.level, Ending.REVOKED);
            for (Grown returning : rig.tools.goals(rig.colony.works().view(rig.level))) rig.finish(returning.nodes().getFirst(), rig.body);
            helper.assertTrue(ItemStack.matches(expected, rig.store.getItem(0)), "a disallowed old hat is returned unchanged, never destroyed or replaced");
            Node next = rig.gather(rig.body);
            helper.assertFalse(next.ready(rig.level), "new work does not borrow the old store hat outside the new clothing selector");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "no unselected physical hat is worn");
            next.ended(rig.level, Ending.REVOKED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void razingWithFullCargoDropsTheRealBorrowedHatBeforeItsResidentLeaves(GameTestHelper helper) {
        try (var rig = new Rig(helper, true)) {
            ItemStack expected = rig.stockHat();
            Node task = rig.gather(rig.body); rig.prepare(task, rig.body);
            for (int slot = 0; slot < rig.body.pack().getContainerSize(); slot++) rig.body.pack().setItem(slot, new ItemStack(Items.DIRT, 64));
            helper.assertTrue(ItemStack.matches(expected, rig.body.getItemBySlot(EquipmentSlot.HEAD)), "the real borrowed hat starts in its native slot");
            ColonyRegistry.get(rig.level.getServer()).raze(rig.level.getServer(), rig.colony.colonyId());
            // Exercise ResidentEntity's real razed-colony tick, including pack drops and its own discard.
            helper.assertTrue(rig.body.colonyId().filter(rig.colony.colonyId()::equals).isPresent()
                && ColonyRegistry.get(rig.level.getServer()).isRazed(rig.colony.colonyId()), "the native leave tick sees its real razed colony binding");
            rig.body.tickCount = 20;
            rig.body.tick();
            helper.assertTrue(rig.body.isRemoved() && rig.body.colonyId().isEmpty(), "the real razed resident leaves through its native lifecycle");
            helper.assertTrue(rig.body.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "teardown releases its loan before the resident is discarded");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), HATS), 0L, "no borrowed copy remains in the removed body's cargo");
            helper.assertValueEqual(Goods.countIn(rig.store, HATS), 0L, "the full-pack loan was not also copied into storage");
            long dropped = rig.level.getEntitiesOfClass(ItemEntity.class, rig.body.getBoundingBox().inflate(3)).stream()
                .filter(entity -> ItemStack.isSameItemSameComponents(entity.getItem(), expected))
                .mapToLong(entity -> entity.getItem().getCount()).sum();
            helper.assertValueEqual(dropped, 1L, "the one named physical loan survives exactly once as a world drop");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anUnloadedBorrowerReloadsAfterRazingAndPreservesItsHatThroughTheRealLeaveTick(GameTestHelper helper) {
        try (var rig = new Rig(helper, true)) {
            ItemStack expected = rig.stockHat();
            Node task = rig.gather(rig.body); rig.prepare(task, rig.body);
            for (int slot = 0; slot < rig.body.pack().getContainerSize(); slot++) rig.body.pack().setItem(slot, new ItemStack(Items.DIRT, 64));
            ResidentEntity restored = rig.reloadAfterRazing();
            restored.tickCount = 20;
            restored.tick();
            helper.assertTrue(restored.isRemoved() && restored.colonyId().isEmpty(), "the reloaded borrower leaves through its real razed-colony tick");
            helper.assertTrue(restored.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "reload releases only the saved native loan before discard");
            helper.assertValueEqual(Goods.countIn(restored.pack(), HATS) + Goods.countIn(rig.store, HATS), 0L, "the loan has no cargo or store duplicate");
            long dropped = rig.level.getEntitiesOfClass(ItemEntity.class, restored.getBoundingBox().inflate(3)).stream()
                .filter(entity -> ItemStack.isSameItemSameComponents(entity.getItem(), expected))
                .mapToLong(entity -> entity.getItem().getCount()).sum();
            helper.assertValueEqual(dropped, 1L, "the unloaded borrower's one original hat survives exactly once in the world");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aRazedReloadNeverReleasesAPlayersSameFamilyReplacementFromItsNativeSlot(GameTestHelper helper) {
        try (var rig = new Rig(helper, true)) {
            ItemStack expected = rig.stockHat();
            Node task = rig.gather(rig.body); rig.prepare(task, rig.body);
            var playerInventory = new net.minecraft.world.SimpleContainer(1);
            playerInventory.setItem(0, rig.body.getItemBySlot(EquipmentSlot.HEAD));
            ItemStack replacement = new ItemStack(ModItems.STRAW_HAT.get());
            replacement.set(DataComponents.CUSTOM_NAME, Component.literal("player replacement after borrowing"));
            rig.body.setItemSlot(EquipmentSlot.HEAD, replacement);
            ResidentEntity restored = rig.reloadAfterRazing();
            helper.assertTrue(ItemStack.matches(replacement, restored.getItemBySlot(EquipmentSlot.HEAD)), "the actual reload event never takes ownership of the player's replacement");
            helper.assertTrue(ItemStack.matches(expected, playerInventory.getItem(0)), "the original loan stays exactly once in the real external inventory");
            helper.assertValueEqual(Goods.countIn(restored.pack(), HATS) + Goods.countIn(rig.store, HATS), 0L, "reload does not reclassify player equipment as shared cargo");
            helper.assertTrue(rig.level.getEntitiesOfClass(ItemEntity.class, restored.getBoundingBox().inflate(3),
                entity -> entity.getItem().is(TagMod.STRAW_HAT)).isEmpty(), "neither the replacement nor a synthetic loan appears as a world drop");
        }
        helper.succeed();
    }

    private static final class Rig implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final ColonyData colony;
        final TaskTools tools;
        final Container store;
        final WorldPos storeAt;
        final BlockPos grass;
        final BlockPos paddy;
        final ResidentEntity body;
        final List<ResidentEntity> bodies = new ArrayList<>();
        Rig(GameTestHelper helper) { this(helper, false); }
        Rig(GameTestHelper helper, boolean nativeService) {
            this.helper = helper; level = helper.getLevel();
            BlockPos base = helper.absolutePos(new BlockPos(0, 1, 0));
            for (BlockPos cell : BlockPos.betweenClosed(base.offset(-1, -1, -1), base.offset(8, -1, 7))) {
                level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
                for (int up = 1; up <= 4; up++) level.setBlockAndUpdate(cell.above(up), Blocks.AIR.defaultBlockState());
            }
            BlockPos chest = base.offset(1, 0, 2);
            level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
            store = Containers.at(level, chest).orElseThrow(); storeAt = WorldPos.of(level, chest);
            grass = base.offset(4, -1, 2);
            level.setBlockAndUpdate(grass, Blocks.GRASS_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(grass.above(), Blocks.SHORT_GRASS.defaultBlockState());
            paddy = base.offset(6, -1, 4);
            level.setBlockAndUpdate(paddy, Blocks.FARMLAND.defaultBlockState());
            level.setBlockAndUpdate(paddy.above(), Blocks.WATER.defaultBlockState());
            colony = ColonyRegistry.get(level.getServer()).mint(level.getServer());
            colony.works().stores(CookeryContent.ID, level.dimension(), List.of(storeAt));
            body = addBody();
            flag("wear_hat", true);
            tools = nativeService ? colony.works().service(CookeryContent.ID, CookeryPresence.class).orElseThrow().tools()
                : new TaskTools(colony.works(), level.getServer());
        }
        ResidentEntity addBody() {
            var actor = PersonBody.RESIDENT.get().create(level); actor.setNoAi(true);
            actor.moveTo(grass.getX() + 0.5, grass.getY() + 1, grass.getZ() + 1.5);
            level.addFreshEntity(actor); bodies.add(actor);
            colony.works().hold(PersonBody.ID, Held.Entity.of(actor)).orElseThrow();
            return actor;
        }
        ResidentEntity reloadAfterRazing() {
            CompoundTag saved = new CompoundTag(); body.saveWithoutId(saved);
            body.remove(net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK);
            helper.assertTrue(body.isRemoved() && colony.works().residents().stream().anyMatch(resident -> resident.id().equals(body.id())),
                "native chunk unloading removes the loaded body while retaining its real registry membership");
            helper.assertTrue(level.getEntity(body.id()) == null || level.getEntity(body.id()).isRemoved(), "no loaded borrower is available to the closing service");
            ColonyRegistry.get(level.getServer()).raze(level.getServer(), colony.colonyId());
            ResidentEntity restored = PersonBody.RESIDENT.get().create(level);
            restored.load(saved);
            helper.assertTrue(level.addFreshEntity(restored), "the native saved body rejoins the real level after its colony was razed");
            bodies.add(restored);
            return restored;
        }
        void flag(String key, boolean value) { ColonyFront.of(colony.works()).setSetting(CookeryContent.ID, key, new ColonySettings.Value.Flag(value)); }
        ItemStack stockHat() {
            ItemStack hat = new ItemStack(ModItems.STRAW_HAT.get());
            hat.set(DataComponents.CUSTOM_NAME, Component.literal("one shared field hat"));
            store.setItem(0, hat.copy()); return hat;
        }
        Node plain(net.minecraft.resources.ResourceLocation owner, BlockPos ground, Stances stances) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner, WorkSite.at(level, ground), stances, Workload.Once.of(1))
                .vocation(FarmingContent.trade()).done();
            return new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            };
        }
        CookeryGear.SeedGathering seed(Body actor) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), io.github.izakyl.folkways.cookery.seeds.SeedPresence.ID,
                WorkSite.at(level, grass), Stances.at(level, grass.south().above()), Workload.Once.of(1))
                .vocation(FarmingContent.trade()).done();
            return new CookeryGear.SeedGathering() {
                public boolean gathersGrass() { return true; }
                public NodeSpec spec() { return spec; }
                public Optional<UUID> worker() { return Optional.of(actor.id()); }
                public Outcome commit(ServerLevel level, Worker who) { return GrassSources.gather(level, who, grass.above(), Set.of(grass)); }
            };
        }
        Node decorate(Node node) { return tools.decorate(Grown.of(node)).nodes().getFirst(); }
        Node gather(Body actor) {
            Node node = decorate(seed(actor));
            helper.assertTrue(node.spec().workload() instanceof Workload.Within, "real Cookery task is wrapped in task-scoped borrowing");
            var refused = node.planned(level);
            helper.assertTrue(refused.isEmpty(), "the real task adopts its shared equipment demand: " + refused
                + "; residents=" + colony.works().residents() + "; licence=" + actor.licences().of(FarmingContent.trade()));
            return node;
        }
        Node paddy() {
            Node node = decorate(plain(FarmingContent.ID, paddy, Stances.at(level, paddy.above())));
            helper.assertTrue(node.spec().workload() instanceof Workload.Within, "wet paddy work requests a native full-set loan");
            var refused = node.planned(level);
            helper.assertTrue(refused.isEmpty(), "the real paddy work is adopted: " + refused);
            return node;
        }
        Worker worker(Body actor) {
            return new Worker() {
                public Resident resident() { return actor.resident(); }
                public Mob body() { return actor.mob(); }
                public Container pack() { return actor.pack(); }
                public List<Store> within() { return List.of(new Store(storeAt, store)); }
                public ItemStack held(ToolNeed need) { return Tools.from(actor.pack().contents(), need).orElse(ItemStack.EMPTY); }
                public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new AssertionError("unexpected spill " + stack); }
                public Effects effects() { return actor.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }
        void stand(Node piece, Body actor) {
            piece.spec().stances().cells().stream().findFirst().ifPresent(at ->
                actor.mob().moveTo(at.block(level).getX() + 0.5, at.block(level).getY(), at.block(level).getZ() + 0.5));
        }
        Node prepare(Node task, Body actor) {
            for (int step = 0; step < 8; step++) {
                var turn = ((Workload.Within) task.spec().workload()).next(level, worker(actor));
                helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "actual storage preparation has a physical piece: " + turn);
                Node piece = ((Workload.Within.Turn.Piece) turn).node(); stand(piece, actor);
                if (actor.mob().getItemBySlot(EquipmentSlot.HEAD).is(TagMod.STRAW_HAT)) return piece;
                execute(task, piece, actor);
            }
            throw new AssertionError("no actual garment reached its native slot");
        }
        void execute(Node parent, Node piece, Body actor) {
            stand(piece, actor);
            helper.assertTrue(piece.planned(level).isEmpty(), "the physical preparation piece can start");
            Outcome outcome = Commit.run(level, piece, worker(actor));
            helper.assertTrue(outcome instanceof Outcome.Done, "the actual native/container operation succeeds: " + outcome);
            for (ItemStack made : ((Outcome.Done) outcome).made()) helper.assertTrue(Containers.insert(actor.pack(), made).isEmpty(), "native output fits real cargo");
            piece.released(level, worker(actor), Ending.DONE); piece.ended(level, Ending.DONE);
            ((Workload.Within) parent.spec().workload()).ended(level, piece, Ending.DONE);
        }
        void finish(Node task, Body actor) {
            for (int step = 0; step < 24; step++) {
                var turn = ((Workload.Within) task.spec().workload()).next(level, worker(actor));
                if (turn instanceof Workload.Within.Turn.Leave) {
                    helper.assertTrue(Commit.run(level, task, worker(actor)) instanceof Outcome.Done, "completed borrowing settles after physical returns");
                    task.released(level, worker(actor), Ending.DONE); task.ended(level, Ending.DONE); return;
                }
                helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "an available physical loan makes progress: " + turn);
                execute(task, ((Workload.Within.Turn.Piece) turn).node(), actor);
            }
            throw new AssertionError("physical clothing task never finished");
        }
        long hats() {
            return Goods.countIn(store, HATS) + bodies.stream().filter(actor -> !actor.isRemoved()).mapToLong(actor ->
                Goods.countIn(actor.pack(), HATS) + Equipment.worn(actor, HATS)).sum();
        }
        public void close() {
            tools.closed(Closing.SHUTDOWN);
            ColonyRegistry.get(level.getServer()).raze(level.getServer(), colony.colonyId());
            bodies.forEach(ResidentEntity::discard);
        }
    }
}
