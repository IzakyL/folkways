package io.github.izakyl.folkways.cookery.steamer;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.SteamerBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.SteamerBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
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
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.testing.RecoveryAsking;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SteamerGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SteamerGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void aHalfSteamerAcceptsFourRealInputsAndDoesNotDismantleWhenServed(GameTestHelper helper) {
        Rig rig = new Rig(helper, true, false, 6);
        SteamerWork work = rig.load(4);
        afterCooking(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the native takeFood serves the actual cooked slots");
            helper.assertValueEqual(ready.count(ModItems.MANTOU.get()), 4L, "a half block serves four real buns");
            helper.assertValueEqual(ready.count(ModItems.RAW_DOUGH.get()), 2L, "capacity limits preserve the two unused inputs");
            helper.assertTrue(ready.steamer().getItems().stream().allMatch(ItemStack::isEmpty), "actual slots clear once");
            helper.assertTrue(ready.steamer().getBlockState().getValue(SteamerBlock.HALF), "taking food never removes the half steamer");
            helper.assertTrue(!ready.steamer().getBlockState().getValue(SteamerBlock.HAS_LID), "the originally open lid is restored");
            helper.assertValueEqual(ready.count(ModItems.STEAMER.get()), 0L, "the station itself is never promised or delivered as food");
            helper.assertTrue(ready.drops().isEmpty(), "all synchronous food outputs enter logistics only once");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void aFullSteamerAcceptsEightRealInputsAndRestoresThePreviousHand(GameTestHelper helper) {
        Rig rig = new Rig(helper, false, false, 10);
        SteamerWork work = rig.load(8);
        afterCooking(rig, ready -> {
            ItemStack old = new ItemStack(Items.APPLE, 7);
            ready.actor.setItemInHand(InteractionHand.MAIN_HAND, old);
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "all eight native slots are collected");
            helper.assertValueEqual(ready.count(ModItems.MANTOU.get()), 8L, "one full block has eight actual portions");
            helper.assertValueEqual(ready.count(ModItems.RAW_DOUGH.get()), 2L, "extra dough remains real stock");
            helper.assertTrue(ready.actor.getMainHandItem() == old, "temporary empty-hand extraction restores the exact prior hand");
            helper.assertTrue(!ready.steamer().getBlockState().getValue(SteamerBlock.HALF), "the full block remains full");
            helper.assertTrue(work.commit(ready.level, ready) instanceof Outcome.Done, "the outer batch completes after actual extraction");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void anUpperLayerCooksWithoutTakingPlayerFoodInTheLowerBlock(GameTestHelper helper) {
        Rig rig = new Rig(helper, false, true, 2);
        ItemStack playerFood = new ItemStack(ModItems.STUFFED_DOUGH_FOOD.get());
        helper.assertTrue(rig.lower().placeFood(rig.level, rig.actor, playerFood), "the player loads the real lower block");
        SteamerWork work = rig.load(2);
        helper.assertTrue(rig.lower().getItems().getFirst().is(ModItems.STUFFED_DOUGH_FOOD.get()), "the task does not move the player's lower ingredient");
        afterCooking(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the upper native block is collected independently");
            helper.assertValueEqual(ready.count(ModItems.MANTOU.get()), 2L, "only owned upper portions enter the pack");
            helper.assertTrue(ready.lower().getItems().getFirst().is(ModItems.BAOZI.get()), "the player's lower meal remains in its actual slot");
            helper.assertValueEqual(new SteamerStation().anchor(ready.level, ready.cell.above()), ready.cell, "all layers normalize to one booked station");
            helper.assertTrue(!ready.lower().getBlockState().getValue(SteamerBlock.HAS_LID), "the native lower layer is never given an impossible lid");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void removingRealHeatRunsNativeCooldownAndRestoringItResumesCooking(GameTestHelper helper) {
        Rig rig = new Rig(helper, true, false, 2);
        SteamerWork work = rig.load(2);
        helper.runAfterDelay(15, () -> {
            int progress = rig.steamer().getCookingProgress()[0];
            helper.assertTrue(progress > 0, "the real native ticker has begun steaming");
            rig.level.setBlockAndUpdate(rig.cell.below(), Blocks.STONE.defaultBlockState());
            helper.runAfterDelay(20, () -> {
                helper.assertValueEqual(rig.steamer().getCookingProgress()[0], 0, "the native cooldown removes progress after the actual heat source disappears");
                helper.assertTrue(work.next(rig.level, rig) instanceof Workload.Within.Turn.Wait, "cold raw food is not considered a completed product");
                rig.level.setBlockAndUpdate(rig.cell.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
                helper.assertTrue(rig.steamer().getBlockState().getValue(SteamerBlock.HAS_BASE), "restoring magma runs the native support/base update");
                helper.assertTrue(work.refusal(rig.level).isEmpty(), "a native cosmetic base update does not invalidate accepted food");
            });
        });
        afterCooking(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "real heat restarts the original recipe");
            helper.assertValueEqual(ready.count(ModItems.MANTOU.get()), 2L, "cooling and reheating neither creates nor loses portions");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void aReloadedBatchIsAdoptedWithoutAnotherIngredientBudget(GameTestHelper helper) {
        Rig rig = new Rig(helper, true, false, 3);
        rig.load(3);
        rig.hold.release(Ending.REVOKED);
        var saved = rig.steamer().saveWithoutMetadata(rig.level.registryAccess());
        rig.level.removeBlockEntity(rig.part.block(rig.level));
        SteamerBlockEntity loaded = new SteamerBlockEntity(rig.part.block(rig.level), rig.level.getBlockState(rig.part.block(rig.level)));
        loaded.loadWithComponents(saved, rig.level.registryAccess());
        rig.level.setBlockEntity(loaded);
        var recovery = new SteamerStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {});
        helper.assertValueEqual(recovery.size(), 1, "the saved physical batch is recoverable");
        SteamerWork work = (SteamerWork) recovery.getFirst().nodes().getFirst();
        helper.assertTrue(work.spec().needs().isEmpty() && work.planned(rig.level).isEmpty(), "recovery binds no second input budget");
        afterCooking(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "reloaded native slots finish and are collected");
            helper.assertValueEqual(ready.count(ModItems.RAW_DOUGH.get()), 0L, "accepted ingredients are never refunded");
            helper.assertValueEqual(ready.count(ModItems.MANTOU.get()), 3L, "exactly the saved three portions are served");
            work.ended(ready.level, Ending.DONE);
        });
    }

    @GameTest(template = "empty")
    public static void withdrawalRejectsAPlannedInteractionBeforeItConsumesDough(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, true, false, 2)) {
            SteamerWork work = rig.plan();
            Node load = ((Workload.Within.Turn.Piece) work.next(rig.level, rig)).node();
            rig.bookings.withdraw(rig.at);
            helper.assertTrue(Commit.run(rig.level, load, rig) instanceof Outcome.Failed, "an old generation cannot load after unmarking");
            helper.assertValueEqual(rig.count(ModItems.RAW_DOUGH.get()), 2L, "the real dough is returned by the core");
            helper.assertTrue(rig.steamer().getItems().stream().allMatch(ItemStack::isEmpty), "the withdrawn native slots stay empty");
            helper.assertTrue(!rig.steamer().getBlockState().getValue(SteamerBlock.HAS_LID), "a rejected load does not alter the lid");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void addingPlayerFoodInvalidatesTheClaimWithoutClearingAnyFood(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, true, false, 2)) {
            SteamerWork work = rig.load(2);
            helper.assertTrue(rig.steamer().placeFood(rig.level, rig.actor, new ItemStack(ModItems.STUFFED_DOUGH_FOOD.get())), "a player can add a real different ingredient");
            helper.assertTrue(work.refusal(rig.level).isPresent(), "the changed full-component slot fingerprint refuses ownership");
            helper.assertTrue(new SteamerStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).isEmpty(), "changed player contents are not adopted");
            helper.assertValueEqual(rig.steamer().getItems().stream().filter(stack -> !stack.isEmpty()).count(), 3L, "neither owned nor player food is erased");
            helper.assertValueEqual(rig.count(ModItems.RAW_DOUGH.get()), 0L, "ownership invalidation does not refund accepted inputs");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void takingFoodOutsideTheTaskCannotCauseAQueuedTakeToDismantleTheEmptyMachine(GameTestHelper helper) {
        Rig rig = new Rig(helper, true, false, 2);
        SteamerWork work = rig.load(2);
        afterCooking(rig, ready -> {
            Node take = ((Workload.Within.Turn.Piece) work.next(ready.level, ready)).node();
            helper.assertTrue(ready.steamer().takeFood(ready.level, ready.actor), "the player-like native interaction empties the real slots");
            helper.assertTrue(Commit.run(ready.level, take, ready) instanceof Outcome.Failed, "the queued extraction refuses the emptied claim");
            helper.assertTrue(ready.level.getBlockState(ready.cell).is(ModBlocks.STEAMER.get()), "the stale action never invokes empty-machine dismantling");
            helper.assertValueEqual(ready.count(ModItems.STEAMER.get()), 0L, "no machine part is delivered as a meal");
            helper.assertValueEqual(ready.count(ModItems.RAW_DOUGH.get()), 0L, "externally collected products do not refund inputs");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void aNativeTakeHookThrowingAfterClearKeepsBothHandAndDroppedPortions(GameTestHelper helper) {
        Rig rig = new Rig(helper, true, false, 3);
        SteamerWork work = rig.load(3);
        afterCooking(rig, ready -> {
            BlockPos at = ready.part.block(ready.level);
            var saved = ready.steamer().saveWithoutMetadata(ready.level.registryAccess());
            ready.level.removeBlockEntity(at);
            SteamerBlockEntity throwing = new SteamerBlockEntity(at, ready.level.getBlockState(at)) {
                @Override public boolean takeFood(Level level, LivingEntity user) {
                    super.takeFood(level, user);
                    throw new IllegalStateException("fixture: another native take-out hook failed after physical clear");
                }
            };
            throwing.loadWithComponents(saved, ready.level.registryAccess());
            ready.level.setBlockEntity(throwing);
            ItemStack old = new ItemStack(Items.APPLE, 5);
            ready.actor.setItemInHand(InteractionHand.MAIN_HAND, old);
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "a cleared native batch is settled rather than replayed");
            helper.assertValueEqual(ready.count(ModItems.MANTOU.get()), 3L, "the first native hand item and the later native drops all survive");
            helper.assertTrue(ready.actor.getMainHandItem() == old && ready.drops().isEmpty(), "prior hand restoration and captured deliveries leave no duplicate");
            helper.assertTrue(ready.steamer().getItems().stream().allMatch(ItemStack::isEmpty), "the physical batch is cleared exactly once");
        });
    }

    @GameTest(template = "empty")
    public static void releasingTheStationRestoresOnlyItsOwnLidAndKeepsAcceptedFood(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, true, false, 2)) {
            rig.load(2);
            helper.assertTrue(rig.steamer().getBlockState().getValue(SteamerBlock.HAS_LID), "the task closes the real top lid");
            new SteamerStation().release(UUID.randomUUID(), rig.level, rig.at);
            helper.assertTrue(SteamerBatch.read(rig.steamer(), rig.level).isPresent(), "another colony cannot clear the claim");
            new SteamerStation().release(rig.colony, rig.level, rig.at);
            helper.assertTrue(!rig.steamer().getBlockState().getValue(SteamerBlock.HAS_LID), "unmarking restores the task's originally open lid");
            helper.assertTrue(!SteamerBatch.marked(rig.steamer()), "only the owner's metadata is removed");
            helper.assertValueEqual(rig.steamer().getItems().stream().filter(stack -> !stack.isEmpty()).count(), 2L, "accepted food remains physically in the steamer");
        }
        helper.succeed();
    }

    @net.minecraft.gametest.framework.GameTestGenerator
    public static java.util.Collection<net.minecraft.gametest.framework.TestFunction> nativeSteamerRecipes() {
        return List.of("mantou", "baozi", "qingtuan", "bamboo_tube_rice").stream().map(name -> new net.minecraft.gametest.framework.TestFunction(
            "cookery_steamer_matrix", "folkways:steamer_matrix_" + name, "folkways:empty", 2000, 0L, true, helper -> {
                var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "steamer/" + name);
                var recipe = helper.getLevel().getRecipeManager().getAllRecipesFor(com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes.STEAMER_RECIPE)
                    .stream().filter(holder -> holder.id().equals(id)).findFirst().orElseThrow();
                ItemStack input = recipe.value().getIngredient().getItems()[0];
                ItemStack output = recipe.value().getResultItem(helper.getLevel().registryAccess());
                Rig rig = new Rig(helper, true, false, 2, input, output); SteamerWork work = rig.load(2); long started = rig.level.getGameTime();
                afterCooking(rig, ready -> {
                    helper.assertTrue(ready.level.getGameTime() - started >= recipe.value().getCookTick(), id + ": the actual native steaming time elapsed");
                    helper.assertTrue(ready.step(work) instanceof Outcome.Done, id + ": native steamed food is actually taken");
                    helper.assertValueEqual(ready.count(output.getItem()), (long) output.getCount() * 2, id + ": every actual native portion enters the pack");
                    helper.assertValueEqual(ready.count(input.getItem()), 0L, id + ": both actual ingredients were spent once");
                    helper.assertTrue(ready.steamer().getItems().stream().allMatch(ItemStack::isEmpty) && ready.drops().isEmpty(), id + ": no slot or loose duplicate remains");
                    helper.assertTrue(ready.level.getBlockState(ready.cell).is(ModBlocks.STEAMER.get()), id + ": native serving never dismantles the station");
                });
            })).toList();
    }

    private static void afterCooking(Rig rig, Consumer<Rig> check) {
        rig.helper.startSequence().thenWaitUntil(() -> rig.helper.assertTrue(SteamerStation.ripe(rig.steamer()),
            "the real heated native ticker steams the actual ingredients")).thenExecute(() -> {
                try { check.accept(rig); } finally { rig.close(); }
            }).thenSucceed();
    }
    @GameTest(template = "empty")
    public static void aFourLayerSteamerCannotPlanWhenOnlyItsBottomFoodSlotIsReachable(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, false, false, 2)) {
            BlockPos edge = fourLayerBoundary(rig);
            rig.actor.setPos(Vec3.atBottomCenterOf(edge));
            Stances offered = Stances.at(rig.level, edge);
            helper.assertTrue(Reach.standingIn(rig.actor, offered) && Reach.withinReach(edge, rig.cell)
                && Reach.inSight(rig.level, edge, rig.cell), "the lower food slot has a genuine supported core stance");
            helper.assertTrue(!Reach.withinReach(edge, rig.cell.above(3)), "the actual top lid remains beyond native reach from the bottom-only footing");
            helper.assertTrue(new SteamerStation().freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at, offered,
                rig.bookings, () -> {}).isEmpty(), "a native batch cannot be planned without a stance reaching both food slot and top lid");
            helper.assertTrue(rig.steamer().getItems().stream().allMatch(ItemStack::isEmpty)
                && rig.count(ModItems.RAW_DOUGH.get()) == 2, "all original raw dough remains untouched when the lid cannot be reached");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1500)
    public static void fourLayerSteamerPublishesAndRecoversOnlyStancesThatReachFoodAndTopLid(GameTestHelper helper) {
        Rig rig = new Rig(helper, false, false, 2); BlockPos edge = fourLayerBoundary(rig);
        BlockPos near = rig.cell.south(2); var provider = new SteamerStation();
        var published = provider.stances(rig.level, rig.at).orElseThrow();
        helper.assertTrue(published.cells().contains(WorldPos.of(rig.level, edge))
            && published.cells().contains(WorldPos.of(rig.level, near)), "the provider's real column approaches include the bottom edge and the common food-and-lid footing");
        rig.stances = Stances.at(WorldPos.of(rig.level, edge), WorldPos.of(rig.level, near));
        rig.actor.setPos(Vec3.atBottomCenterOf(near));
        helper.assertTrue(Reach.withinReach(near, rig.cell.above(3))
            && Reach.inSight(rig.level, near, rig.cell.above(3)), "the common candidate sees and reaches the actual four-layer top lid");
        SteamerWork original = rig.plan();
        helper.assertTrue(original.spec().stances().cells().equals(java.util.Set.of(WorldPos.of(rig.level, near))),
            "the actual native batch retains only the stance that performs every required physical operation");
        helper.assertTrue(rig.step(original) instanceof Outcome.Done, "the planned stance actually loads native lower food and closes the real top lid");
        helper.assertTrue(rig.level.getBlockState(rig.cell.above(3)).getValue(SteamerBlock.HAS_LID), "the real top lid is closed without moving the resident or changing the native clock");
        rig.hold.release(Ending.DROPPED); rig.hold = null;
        SteamerWork resumed = (SteamerWork) provider.recovery(rig.colony, rig.level, rig.at, rig.stances,
            rig.bookings, () -> {}).getFirst().nodes().getFirst();
        helper.assertTrue(resumed.spec().needs().isEmpty()
            && resumed.spec().stances().cells().equals(java.util.Set.of(WorldPos.of(rig.level, near)))
            && resumed.planned(rig.level).isEmpty(), "recovery keeps the same native-owned batch and common operation stance without another dough budget");
        afterCooking(rig, ready -> {
            try {
                helper.assertTrue(ready.step(resumed) instanceof Outcome.Done, "the safe published approach also performs actual native takeFood and lid restoration");
                helper.assertValueEqual(ready.count(ModItems.MANTOU.get()), 2L, "both original real buns are collected once");
                helper.assertValueEqual(ready.count(ModItems.RAW_DOUGH.get()), 0L, "the accepted native dough is never recharged or refunded");
                helper.assertTrue(!ready.level.getBlockState(ready.cell.above(3)).getValue(SteamerBlock.HAS_LID), "the real top lid returns to its original open state");
            } finally { resumed.ended(ready.level, Ending.DONE); }
        });
    }

    private static BlockPos fourLayerBoundary(Rig rig) {
        for (int dy = 1; dy <= 3; dy++) {
            rig.level.setBlockAndUpdate(rig.cell.above(dy), ModBlocks.STEAMER.get().defaultBlockState().setValue(SteamerBlock.HALF, false));
        }
        for (int x = 0; x <= 4; x++) { for (int z = 0; z <= 2; z++) {
            if (x == 0 && z == 0) { continue; }
            BlockPos floor = rig.cell.offset(x, 0, z);
            rig.level.setBlockAndUpdate(floor.below(), Blocks.STONE.defaultBlockState());
            rig.level.setBlockAndUpdate(floor, Blocks.AIR.defaultBlockState());
            rig.level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
        } }
        return rig.cell.offset(4, 0, 2);
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void aBookedRecoveryStaysWantedAcrossAskingSweepsAndTheNativeSettlementWindow(GameTestHelper helper) {
        Rig rig = new Rig(helper, true, false, 2); rig.load(2); rig.level.setBlockAndUpdate(rig.cell.below(), Blocks.STONE.defaultBlockState());
        rig.hold.release(Ending.DROPPED); rig.hold = null;
        var provider = new SteamerStation();
        Grown original = provider.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).getFirst();
        SteamerWork work = (SteamerWork) original.nodes().getFirst();
        var asking = new RecoveryAsking(helper); asking.offer(List.of(original));
        helper.assertTrue(work.planned(rig.level).isEmpty(), "the native recovery really takes its exclusive booking");
        asking.assertKept(original, provider.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
        helper.startSequence().thenIdle(40).thenExecute(() -> {
            asking.assertKept(original, provider.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
            helper.assertTrue(rig.steamer().getItems().stream().filter(stack -> !stack.isEmpty()).count() == 2, "accepted native ingredients remain physically stored while actual heat or power is absent");
        }).thenIdle(40).thenExecute(() -> {
            asking.assertKept(original, provider.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
            helper.assertTrue(provider.activeRecovery(rig.colony, rig.level, rig.at, rig.bookings).getFirst() == original, "blocked-approach queries expose only this already planned original root");
            rig.level.setBlockAndUpdate(rig.cell.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
        }).thenWaitUntil(() -> helper.assertTrue(SteamerStation.ripe(rig.steamer()), "the same real native ticker finishes after physical heat or power returns"))
            .thenExecute(() -> {
                try {
                    helper.assertTrue(rig.step(work) instanceof Outcome.Done, "the actual recovered native output is collected");
                    asking.assertKept(original, provider.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}));
                    helper.assertTrue(provider.activeRecovery(rig.colony, rig.level, rig.at, rig.bookings).getFirst() == original, "clearing real native slots does not withdraw the root before outer settlement");
                    helper.assertTrue(work.commit(rig.level, rig) instanceof Outcome.Done, "the same original outer recovery settles successfully");
                    work.ended(rig.level, Ending.DONE); asking.finished(original, Ending.DONE);
                    helper.assertTrue(provider.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).isEmpty()
                        && provider.activeRecovery(rig.colony, rig.level, rig.at, rig.bookings).isEmpty(), "completion releases both the physical booking and the exact cached root");
                    helper.assertValueEqual(rig.count(ModItems.MANTOU.get()), 2L, "all original real portions are delivered once");
                    helper.assertValueEqual(rig.count(ModItems.RAW_DOUGH.get()), 0L, "accepted ingredients are not recreated or charged again");
                    helper.assertTrue(rig.drops().isEmpty(), "no duplicate native result is left on the ground");
                } finally { work.ended(rig.level, Ending.DONE); rig.close(); }
            }).thenSucceed();
    }

    private static final class Rig implements Worker, AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        final WorldPos part;
        Stances stances;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(12);
        final ResidentEntity actor;
        final int requested;
        final ItemStack input;
        final ItemStack output;
        Hold hold;
        Rig(GameTestHelper helper, boolean half, boolean stack, int requested) {
            this(helper, half, stack, requested, new ItemStack(ModItems.RAW_DOUGH.get()), new ItemStack(ModItems.MANTOU.get()));
        }
        Rig(GameTestHelper helper, boolean half, boolean stack, int requested, ItemStack input, ItemStack output) {
            this.input = input.copyWithCount(1); this.output = output.copy();
            this.helper = helper; level = helper.getLevel(); this.requested = requested;
            cell = helper.absolutePos(new BlockPos(2, 1, 2));
            for (int x = -1; x <= 1; x++) { for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            } }
            level.setBlockAndUpdate(cell.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(cell, ModBlocks.STEAMER.get().defaultBlockState().setValue(SteamerBlock.HALF, half));
            if (stack) { level.setBlockAndUpdate(cell.above(), ModBlocks.STEAMER.get().defaultBlockState()); }
            at = WorldPos.of(level, cell); part = stack ? at.above() : at;
            actor = PersonBody.RESIDENT.get().create(level);
            actor.setPos(Vec3.atBottomCenterOf(cell.south()));
            actor.setNoAi(true);
            level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition());
            pack.setItem(0, input.copyWithCount(requested));
        }
        SteamerBlockEntity lower() { return (SteamerBlockEntity) level.getBlockEntity(cell); }
        SteamerBlockEntity steamer() { return (SteamerBlockEntity) level.getBlockEntity(part.block(level)); }
        SteamerWork plan() {
            var bench = new SteamerStation().freeze(UUID.randomUUID(), colony, level, at, stances, bookings, () -> {}).orElseThrow();
            Produce wanted = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(output), (long) requested * output.getCount(), Produce.UNBOUNDED);
            var option = bench.options(wanted, Grown.of(wanted)).getFirst();
            hold = option.holds().getFirst();
            helper.assertTrue(hold.take().isEmpty(), "the whole steamer column is booked");
            return (SteamerWork) option.replacement().nodes().getFirst();
        }
        SteamerWork load(int accepted) {
            SteamerWork work = plan();
            Outcome outcome = step(work);
            helper.assertTrue(outcome instanceof Outcome.Done, "native steamer loading: " + outcome);
            helper.assertValueEqual(steamer().getItems().stream().filter(stack -> !stack.isEmpty()).count(), (long) accepted, "the actual slots enforce their half/full capacity");
            return work;
        }
        Outcome step(SteamerWork work) {
            var turn = work.next(level, this);
            if (!(turn instanceof Workload.Within.Turn.Piece)) {
                helper.fail("expected a native steamer action, got " + turn + "; refusal=" + work.refusal(level)
                    + "; state=" + steamer().getBlockState() + "; slots=" + steamer().getItems()
                    + "; time=" + java.util.Arrays.toString(steamer().getCookingTime())
                    + "; progress=" + java.util.Arrays.toString(steamer().getCookingProgress())
                    + "; owner=" + SteamerBatch.read(steamer(), level));
                return Outcome.failed(io.github.izakyl.folkways.cookery.CookeryRefusal.STATION_CHANGED);
            }
            Node piece = ((Workload.Within.Turn.Piece) turn).node();
            Outcome result = Commit.run(level, piece, this);
            if (result instanceof Outcome.Done done) {
                done.made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "the native food fits"));
                work.ended(level, piece, Ending.DONE);
            }
            return result;
        }
        List<ItemEntity> drops() { return level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(2)); }
        long count(Item item) { return Goods.countIn(pack, Goods.specOf(new ItemStack(item))); }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new IllegalStateException("unexpected spill"); }
        public Effects effects() { return Effects.NONE; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() {
            if (hold != null) { hold.release(Ending.DROPPED); }
            actor.discard();
            drops().forEach(ItemEntity::discard);
        }
    }
}
