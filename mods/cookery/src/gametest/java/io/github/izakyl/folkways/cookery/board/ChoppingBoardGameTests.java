package io.github.izakyl.folkways.cookery.board;

import io.github.izakyl.folkways.effect.Effects;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.ChoppingBoardRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChoppingBoardGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ChoppingBoardGameTests.class);
    }

    @GameTest(template = "empty")
    public static void nonStrictBoardRecipesAcceptNamedComponentIngredients(GameTestHelper helper) {
        var ingredient = DataComponentIngredient.of(false,
            DataComponents.POTION_CONTENTS,
            new PotionContents(Potions.HEALING), Items.POTION);
        var recipe = new ChoppingBoardRecipe(ingredient,
            List.of(new ItemStack(Items.PAPER)), 1, ResourceLocation.withDefaultNamespace("paper"));
        var holder = new RecipeHolder<>(
            ResourceLocation.fromNamespaceAndPath("folkways", "test/component_board"), recipe);
        var job = ChoppingBoardWorkshops.Job.of(holder).orElseThrow();
        ItemStack named = ingredient.getItems()[0].copy();
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Medicine"));
        helper.assertTrue(recipe.matches(new SingleRecipeInput(named), helper.getLevel())
            && Goods.matches(named, job.input()), "the board's planning input accepts every extra component allowed by the recipe");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void plannedMeatDoesNotDoubleCountButTheUnrequestedBoneStillNeedsRoom(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            var meat = Goods.specOf(ModItems.RAW_CUT_SMALL_MEATS.get().getDefaultInstance());
            var bone = Goods.specOf(Items.BONE.getDefaultInstance());
            var extras = work.planningLeaves(List.of(new Amount(meat, 3)));
            helper.assertTrue(extras.equals(List.of(new Amount(bone, 1))),
                "the promised three meat pieces count once, and the unrequested bone still occupies a real slot");
            var partial = work.planningLeaves(List.of(new Amount(meat, 1)));
            helper.assertValueEqual(partial.stream().filter(amount -> amount.spec().equals(meat)).mapToLong(Amount::count).sum(),
                2L, "the other two real meat pieces are retained even when only one is promised");
            helper.assertValueEqual(partial.stream().filter(amount -> amount.spec().equals(bone)).mapToLong(Amount::count).sum(),
                1L, "a partial meat promise does not erase its native secondary product");
            helper.assertTrue(work.planningLeaves(List.of()).equals(Leaves.merged(work.spec().leaves())),
                "an autonomous take with no downstream promise reserves every native output");
            fixture.finish(work); fixture.assertProducts(3, 1);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualBoardCutsOneIngredientAndDeliversEveryProduct(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.finish(work);
            fixture.assertProducts(3, 1);
            helper.assertTrue(fixture.board().getCurrentCutStack().isEmpty(), "the real board is empty after take-out");
            helper.assertTrue(fixture.level.getEntitiesOfClass(ItemEntity.class, new AABB(fixture.pos).inflate(0.5)).isEmpty(),
                "the produced meat and bone enter the worker's logistics instead of remaining on the floor");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void secondaryProductCanBeRequestedByProduction(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(Items.BONE));
            helper.assertTrue(work.spec().gives().stream().anyMatch(amount -> amount.spec().equals(Goods.specOf(new ItemStack(Items.BONE)))),
                "the provider indexes secondary products as well as the first result");
            fixture.finish(work);
            fixture.assertProducts(3, 1);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playerIngredientsAndProductsAreNeverAdopted(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            ItemStack chicken = new ItemStack(Items.CHICKEN);
            fixture.board().onPutItem(fixture.level, null, chicken);
            helper.assertTrue(ChoppingBoardWorkshops.freeze(UUID.randomUUID(), fixture.colony, fixture.level,
                fixture.at, fixture.stances, fixture.bookings, () -> {}).isEmpty(), "a player's occupied board is not a workshop");
            helper.assertTrue(fixture.recovery().isEmpty(), "a player's board is not recovered");
            helper.assertValueEqual(fixture.board().getCurrentCutCount(), 0, "the player's ingredient is untouched");
            for (int cut = 0; cut < fixture.board().getMaxCutCount(); cut++) {
                fixture.board().onCutItem(fixture.level, null, fixture.knife());
            }
            helper.assertTrue(fixture.recovery().isEmpty(), "finished player products are not adopted either");
            helper.assertTrue(!fixture.board().getCurrentCutStack().isEmpty(), "the player's finished products await the player");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void losingTheKnifeBeforeStartingLeavesTheIngredientInThePack(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.pack.setItem(1, ItemStack.EMPTY);
            Outcome outcome = fixture.step(work);
            helper.assertTrue(outcome instanceof Outcome.Failed, "a missing knife rejects the operation before loading");
            helper.assertValueEqual(fixture.pack.getItem(0).getCount(), 1, "the ingredient is returned by the core");
            helper.assertTrue(fixture.board().getCurrentCutStack().isEmpty(), "the board remains empty");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void workAtOneBoardRunsInTheBookedOrder(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            fixture.pack.getItem(0).setCount(2);
            BoardWork first = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            BoardWork second = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            helper.assertTrue(first.ready(fixture.level) && !second.ready(fixture.level), "only the first booking can enter");
            fixture.finish(first);
            helper.assertTrue(!second.ready(fixture.level), "the first booking holds the station until its plan ends");
            fixture.holds.getFirst().release(Ending.DONE);
            helper.assertTrue(second.ready(fixture.level), "the second booking enters after the first releases");
            fixture.finish(second);
            fixture.assertProducts(6, 2);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void interruptedCuttingResumesWithoutDrawingAnotherIngredient(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork first = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.step(first);
            fixture.step(first);
            fixture.step(first);
            helper.assertValueEqual(fixture.board().getCurrentCutCount(), 2, "two real cuts are persisted");
            fixture.holds.getFirst().release(Ending.DROPPED);
            first.ended(fixture.level, Ending.DROPPED);
            List<Grown> recovery = fixture.recovery();
            BoardWork resumed = (BoardWork) recovery.getFirst().nodes().getFirst();
            helper.assertValueEqual(fixture.recovery().getFirst().nodes().getFirst().id(), resumed.id(), "recovery has a stable task id");
            helper.assertTrue(resumed.spec().needs().isEmpty(), "the input already in the board is not requested again");
            helper.assertTrue(resumed.planned(fixture.level).isEmpty(), "recovery books the idle board");
            fixture.finish(resumed);
            resumed.ended(fixture.level, Ending.DONE);
            fixture.assertProducts(3, 1);
            helper.assertTrue(fixture.recovery().isEmpty(), "completed products cannot be recovered twice");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void boardSaveAndLoadRetainsTheOwnedBatchAndAllInputComponents(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            Component name = Component.literal("Reserved chicken");
            fixture.pack.getItem(0).set(DataComponents.CUSTOM_NAME, name);
            BoardWork first = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.step(first);
            fixture.step(first);
            CompoundTag saved = fixture.board().saveWithoutMetadata(fixture.level.registryAccess());
            ChoppingBoardBlockEntity loaded = new ChoppingBoardBlockEntity(fixture.pos, fixture.level.getBlockState(fixture.pos));
            loaded.loadWithComponents(saved, fixture.level.registryAccess());
            fixture.level.setBlockEntity(loaded);
            helper.assertValueEqual(loaded.getCurrentCutStack().get(DataComponents.CUSTOM_NAME), name,
                "the real block entity retains the full input stack");
            fixture.holds.getFirst().release(Ending.DROPPED);
            BoardWork resumed = (BoardWork) fixture.recovery().getFirst().nodes().getFirst();
            helper.assertTrue(resumed.planned(fixture.level).isEmpty(), "a saved batch can be resumed by its colony");
            fixture.finish(resumed);
            resumed.ended(fixture.level, Ending.DONE);
            fixture.assertProducts(3, 1);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playerChangesInvalidateOwnershipWithoutClearingTheBoard(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.step(work);
            fixture.board().onCutItem(fixture.level, null, fixture.knife());
            fixture.holds.getFirst().release(Ending.DROPPED);
            helper.assertTrue(fixture.recovery().isEmpty(), "player-made progress is not adopted as colony work");
            helper.assertTrue(work.refusal(fixture.level).isPresent(), "the old task notices the changed fingerprint");
            helper.assertValueEqual(fixture.board().getCurrentCutCount(), 1, "the player-modified board is left intact");
            helper.assertTrue(!fixture.board().getCurrentCutStack().isEmpty(), "the input is not refunded or erased");
            ChoppingBoardWorkshops.release(UUID.randomUUID(), fixture.level, fixture.at);
            helper.assertTrue(BoardOwnership.ours(fixture.board(), fixture.colony), "another colony cannot clear the owner");
            ChoppingBoardWorkshops.release(fixture.colony, fixture.level, fixture.at);
            helper.assertTrue(!BoardOwnership.ours(fixture.board(), fixture.colony),
                "unmarking can clear the original owner even after a player changed the fingerprint");
            helper.assertValueEqual(fixture.board().getCurrentCutCount(), 1, "releasing ownership preserves the player's cuts");
            helper.assertTrue(!fixture.board().getCurrentCutStack().isEmpty(), "releasing ownership preserves the player's input");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void newDeliveryNeverCollectsUnrelatedItemsAlreadyBesideTheBoard(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            ItemEntity playerDrop = new ItemEntity(fixture.level, fixture.pos.getX() + 0.5,
                fixture.pos.getY() + 0.25, fixture.pos.getZ() + 0.5, new ItemStack(Items.DIAMOND, 7));
            fixture.level.addFreshEntity(playerDrop);
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.finish(work);
            fixture.assertProducts(3, 1);
            helper.assertTrue(playerDrop.isAlive() && playerDrop.getItem().getCount() == 7,
                "only products created inside the operation are delivered");
            playerDrop.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anotherColonyCannotRecoverAnOwnedBatch(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.step(work);
            fixture.holds.getFirst().release(Ending.DROPPED);
            helper.assertTrue(ChoppingBoardWorkshops.recovery(UUID.randomUUID(), fixture.level, fixture.at,
                fixture.stances, fixture.bookings, () -> {}).isEmpty(), "recovery is restricted to the original colony");
            helper.assertTrue(!fixture.recovery().isEmpty(), "the original colony can still recover the input");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anInteractionThrowingAfterInsertionNeverRefundsTheInsertedIngredient(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            ChoppingBoardBlockEntity throwing = new ChoppingBoardBlockEntity(fixture.pos,
                    fixture.level.getBlockState(fixture.pos)) {
                @Override
                public boolean onPutItem(Level level, LivingEntity user, ItemStack input) {
                    super.onPutItem(level, user, input);
                    throw new IllegalStateException("injected failure after the board accepted the input");
                }
            };
            fixture.level.setBlockEntity(throwing);
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            helper.assertTrue(fixture.step(work) instanceof Outcome.Done, "the committed insertion is settled as spent");
            helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(new ItemStack(Items.CHICKEN))), 0L,
                "the core does not refund an ingredient already in the real board");
            helper.assertTrue(!throwing.getCurrentCutStack().isEmpty(), "the accepted ingredient remains recoverable");
            fixture.holds.getFirst().release(Ending.DROPPED);
            BoardWork resumed = (BoardWork) fixture.recovery().getFirst().nodes().getFirst();
            helper.assertTrue(resumed.planned(fixture.level).isEmpty(), "recovery retains the accepted batch");
            fixture.finish(resumed);
            resumed.ended(fixture.level, Ending.DONE);
            fixture.assertProducts(3, 1);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removingTheBoardFailsItsTaskWithoutRefundingAlreadySpentInput(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.step(work);
            fixture.level.destroyBlock(fixture.pos, false);
            helper.assertTrue(work.refusal(fixture.level).isPresent(), "a missing board refuses further work");
            helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(new ItemStack(Items.CHICKEN))), 0L,
                "the task never invents a refund for an ingredient it already spent");
            fixture.holds.getFirst().release(Ending.DROPPED);
            helper.assertTrue(fixture.recovery().isEmpty(), "a destroyed board cannot be resumed");
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void anExceptionAfterTheBoardEmptiesStillDeliversAllCapturedProductsOnce(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            ChoppingBoardBlockEntity throwing = new ChoppingBoardBlockEntity(fixture.pos,
                    fixture.level.getBlockState(fixture.pos)) {
                private boolean throwOnce = true;

                @Override
                public boolean onCutItem(Level level, LivingEntity user, ItemStack knife) {
                    boolean result = super.onCutItem(level, user, knife);
                    if (throwOnce && getCurrentCutStack().isEmpty()) {
                        throwOnce = false;
                        throw new IllegalStateException("injected failure after the products were emitted and board emptied");
                    }
                    return result;
                }
            };
            fixture.level.setBlockEntity(throwing);
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.finish(work);
            fixture.assertProducts(3, 1);
            fixture.holds.getFirst().release(Ending.DONE);
            helper.assertTrue(fixture.recovery().isEmpty(), "an already emptied batch is never replayed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anExceptionBetweenTwoProductsRollsBackOnlyTheUncompletedTakeOut(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            ChoppingBoardBlockEntity throwing = new ChoppingBoardBlockEntity(fixture.pos,
                    fixture.level.getBlockState(fixture.pos)) {
                private boolean throwOnce = true;

                @Override
                public boolean onCutItem(Level level, LivingEntity user, ItemStack knife) {
                    if (throwOnce && getCurrentCutCount() >= getMaxCutCount()) {
                        throwOnce = false;
                        // Inject the same failure boundary as an exception in the real loop after its first popResource.
                        popResource(level, fixture.pos, BoardOwnership.results(this, fixture.level).getFirst().copy());
                        throw new IllegalStateException("injected failure between the meat and bone popResource calls");
                    }
                    return super.onCutItem(level, user, knife);
                }
            };
            fixture.level.setBlockEntity(throwing);
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.step(work);
            while (throwing.getCurrentCutCount() < throwing.getMaxCutCount()) {
                helper.assertTrue(fixture.step(work) instanceof Outcome.Done, "the ordinary cuts complete");
            }
            helper.assertTrue(fixture.step(work) instanceof Outcome.Failed, "an incomplete take-out is retried later");
            helper.assertValueEqual(Goods.countIn(fixture.pack,
                Goods.specOf(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()))), 0L,
                "the partially captured meat is not delivered before the call is rolled back");
            fixture.holds.getFirst().release(Ending.DROPPED);
            BoardWork resumed = (BoardWork) fixture.recovery().getFirst().nodes().getFirst();
            helper.assertTrue(resumed.planned(fixture.level).isEmpty(), "the pre-call owned board remains recoverable");
            fixture.finish(resumed);
            resumed.ended(fixture.level, Ending.DONE);
            fixture.assertProducts(3, 1);
            helper.assertTrue(fixture.level.getEntitiesOfClass(ItemEntity.class, new AABB(fixture.pos).inflate(0.5)).isEmpty(),
                "the canceled partial product was never also spawned on the floor");
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void aPlayerFillingTheIdleBoardRejectsOnlyTheCurrentTurn(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork first = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            BoardWork second = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.board().onPutItem(fixture.level, null, new ItemStack(Items.CHICKEN));
            helper.assertTrue(first.refusal(fixture.level).isPresent(), "the current task notices player-owned input");
            helper.assertTrue(second.refusal(fixture.level).isEmpty(), "a queued task does not reject another turn's contents");
            fixture.holds.getFirst().release(Ending.DROPPED);
            helper.assertTrue(second.refusal(fixture.level).isPresent(), "the next turn also protects the player's input");
            fixture.holds.get(1).release(Ending.DROPPED);
            helper.assertTrue(fixture.bookings.free(fixture.at), "refused turns can release the board");
            helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(new ItemStack(Items.CHICKEN))), 1L,
                "neither task spends its own ingredient");
            helper.assertTrue(!fixture.board().getCurrentCutStack().isEmpty(), "the player's ingredient stays in place");
        }
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    @GameTest(template = "empty")
    public static void aRecipeDisabledAfterPlanningCannotConsumeItsIngredient(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            ModConfigSpec.ConfigValue<List<? extends String>> refused =
                FolkwaysVanillaConfig.SPEC.getValues().get("crafting.refusedRecipes");
            List<? extends String> original = refused.get();
            try {
                java.util.ArrayList<String> changed = new java.util.ArrayList<>(original);
                changed.add("kaleidoscope_cookery:chopping_board/raw_cut_small_meats_from_chicken");
                refused.set(changed);
                helper.assertTrue(fixture.step(work) instanceof Outcome.Failed, "the execution rechecks disabled recipes");
                helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(new ItemStack(Items.CHICKEN))), 1L,
                    "the rejected operation returns its ingredient exactly once");
                helper.assertTrue(fixture.board().getCurrentCutStack().isEmpty(), "a disabled recipe never enters the board");
            } finally {
                refused.set(original);
            }
        }
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void choppingUsesStorageFootingsOnARotatedMovingFrame(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            UUID frameId = UUID.randomUUID();
            Vec3[] origin = {Vec3.atLowerCornerOf(fixture.pos.offset(128, 4, 96))};
            Runnable remove = installFrame(fixture, frameId, origin);
            try {
                WorldPos board = new WorldPos(new Realm.Frame(frameId), BlockPos.ZERO);
                WorldPos feet = board.at(new BlockPos(1, 0, 0));
                fixture.body.mob().setPos(WorldSpaces.world(fixture.level, feet).orElseThrow());
                BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()), board,
                    Stances.at(feet));
                helper.assertTrue(fixture.body.mob().blockPosition().distManhattan(fixture.pos) > 100,
                    "the frame's world location is distinct from its block storage");
                helper.assertTrue(fixture.step(work) instanceof Outcome.Done, "the resident loads the real stored board");
                origin[0] = origin[0].add(7, 0, -5);
                fixture.body.mob().setPos(WorldSpaces.world(fixture.level, feet).orElseThrow());
                fixture.finish(work);
                fixture.assertProducts(3, 1);
            } finally {
                remove.run();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aMovingFrameCannotLeaveItsOldResidentOperatingRemotely(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            UUID frameId = UUID.randomUUID();
            Vec3[] origin = {Vec3.atLowerCornerOf(fixture.pos.offset(128, 4, 96))};
            Runnable remove = installFrame(fixture, frameId, origin);
            try {
                WorldPos board = new WorldPos(new Realm.Frame(frameId), BlockPos.ZERO);
                WorldPos feet = board.at(new BlockPos(1, 0, 0));
                fixture.body.mob().setPos(WorldSpaces.world(fixture.level, feet).orElseThrow());
                BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()), board,
                    Stances.at(feet));
                origin[0] = origin[0].add(12, 0, 0);
                helper.assertTrue(fixture.step(work) instanceof Outcome.Failed,
                    "an old world position is no longer a valid footing on the moved frame");
                helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(new ItemStack(Items.CHICKEN))), 1L,
                    "the core returns the ingredient when the frame moved out from under the resident");
                helper.assertTrue(fixture.board().getCurrentCutStack().isEmpty(), "the remote board stays untouched");
            } finally {
                remove.run();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withdrawingAPlannedBoardStopsItsAlreadyPreparedPutWithoutSpendingInput(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            Node put = ((Workload.Within.Turn.Piece) work.next(fixture.level, fixture)).node();
            fixture.bookings.withdraw(fixture.at);
            helper.assertTrue(work.refusal(fixture.level).isPresent() && put.refusal(fixture.level).isPresent(),
                "both the outer plan and its already prepared operation report cancellation");
            helper.assertTrue(work.next(fixture.level, fixture) instanceof Workload.Within.Turn.Leave,
                "canceled work leaves instead of waiting forever for a missing first turn");
            helper.assertTrue(Commit.run(fixture.level, put, fixture) instanceof Outcome.Failed,
                "even a put already in its wind-up cannot operate an unmarked kitchen");
            helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(new ItemStack(Items.CHICKEN))), 1L,
                "the core returns the old operation's ingredient exactly once");
            helper.assertTrue(fixture.board().getCurrentCutStack().isEmpty(), "the unmarked board remains empty");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void readmittingTheSameBoardAndTurnIdCannotReviveAnOldPut(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork old = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            Node put = ((Workload.Within.Turn.Piece) old.next(fixture.level, fixture)).node();
            fixture.bookings.withdraw(fixture.at);
            fixture.bookings.admit(fixture.at);
            Hold replacement = fixture.bookings.hold(fixture.at, old.id());
            helper.assertTrue(replacement.take().isEmpty(), "a new generation can book the same stable id");
            fixture.holds.add(replacement);
            helper.assertTrue(old.refusal(fixture.level).isPresent(), "the old node cannot borrow the new same-id booking");
            helper.assertTrue(Commit.run(fixture.level, put, fixture) instanceof Outcome.Failed,
                "the old prepared operation retains its original generation");
            helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(new ItemStack(Items.CHICKEN))), 1L,
                "re-marking cannot spend the old operation's input");
            helper.assertTrue(fixture.board().getCurrentCutStack().isEmpty(), "the old operation never loads the re-marked board");
        }
        helper.succeed();
    }
    private static Runnable installFrame(Fixture fixture, UUID id, Vec3[] origin) {
        WorldSpaces.Frame frame = new WorldSpaces.Frame() {
            public UUID id() { return id; }
            public Level level() { return fixture.level; }
            public BlockPos storageOrigin() { return fixture.pos; }
            public Vec3 toWorld(Vec3 local) { return origin[0].add(-local.z, local.y, local.x); }
            public Vec3 toLocal(Vec3 world) {
                Vec3 delta = world.subtract(origin[0]);
                return new Vec3(delta.z, delta.y, -delta.x);
            }
        };
        return WorldSpaces.install(new WorldSpaces.Source() {
            public Optional<WorldSpaces.Frame> frame(Level host, UUID key) {
                return host == fixture.level && key.equals(id) ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> containing(Level host, BlockPos cell) {
                return host == fixture.level && (cell.equals(fixture.pos) || cell.equals(fixture.pos.east()))
                    ? Optional.of(frame) : Optional.empty();
            }
            public Optional<WorldSpaces.Frame> aboard(Entity entity) {
                return entity == fixture.body.mob() ? Optional.of(frame) : Optional.empty();
            }
        });
    }
    @GameTest(template = "empty")
    public static void nativeKnifeChanceWearsOneRealPointOrNoneWithoutGenericDoubleWear(GameTestHelper helper) {
        for (boolean wears : List.of(false, true)) {
            try (Fixture fixture = new Fixture(helper, wears ? new BlockPos(4, 1, 4) : new BlockPos(1, 1, 1))) {
                BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
                fixture.step(work);
                helper.assertValueEqual(fixture.knife().getDamageValue(), 0, "putting food on the board does not use a knife");
                fixture.level.random.setSeed(cutWearSeed(wears));
                helper.assertTrue(fixture.step(work) instanceof Outcome.Done, "the real native first knife hit succeeds");
                helper.assertValueEqual(fixture.knife().getDamageValue(), wears ? 1 : 0,
                    "the actual native one-in-four interaction costs at most one physical durability point");
                helper.assertTrue(work.spec().tools().stream().allMatch(tool -> tool.baseWear() == 0),
                    "the enclosing work cannot additionally apply generic Wear to the same use");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedKnifeHitDoesNotWearAndLastDurabilityDoesNotRefundItsIngredient(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.step(work);
            ItemStack knife = fixture.knife();
            knife.setDamageValue(knife.getMaxDamage() - 1);
            fixture.level.random.setSeed(cutWearSeed(true));
            helper.assertTrue(fixture.step(work) instanceof Outcome.Done, "the final real knife use still cuts");
            helper.assertTrue(fixture.knife().isEmpty(), "the actual physical knife breaks without a spare copy");
            helper.assertTrue(fixture.board().getCurrentCutStack().is(Items.CHICKEN), "the invested food remains on the board");
            helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(Items.CHICKEN.getDefaultInstance())), 0L,
                "a broken tool does not refund or duplicate the invested chicken");
        }
        try (Fixture fixture = new Fixture(helper, new BlockPos(4, 1, 4))) {
            BoardWork work = fixture.plan(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()));
            fixture.step(work);
            fixture.body.mob().setPos(fixture.pos.getX() + 20.0, fixture.pos.getY(), fixture.pos.getZ());
            helper.assertTrue(fixture.step(work) instanceof Outcome.Failed, "an out-of-reach hit is refused before native use");
            helper.assertValueEqual(fixture.knife().getDamageValue(), 0, "refusal never wears the actual tool");
            helper.assertTrue(fixture.board().getCurrentCutStack().is(Items.CHICKEN), "refused work preserves its real food");
        }
        helper.succeed();
    }

    private static long cutWearSeed(boolean wears) {
        // The pinned native first hit rolls two particle positions and one pitch before its wear check.
        for (long seed = 0; seed < 10000; seed++) {
            var random = net.minecraft.util.RandomSource.create(seed);
            random.nextDouble();
            random.nextDouble();
            random.nextFloat();
            if ((random.nextDouble() < 0.25) == wears) return seed;
        }
        throw new AssertionError("a deterministic native knife roll exists");
    }
    private static final class Fixture implements Worker, AutoCloseable {

        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos pos;
        final WorldPos at;
        final Stances stances;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(12);
        final java.util.ArrayList<Hold> holds = new java.util.ArrayList<>();
        final Body body;

        Fixture(GameTestHelper helper) {
            this(helper, new BlockPos(1, 1, 1));
        }

        Fixture(GameTestHelper helper, BlockPos relative) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.pos = helper.absolutePos(relative);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos.east().below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, ModBlocks.CHOPPING_BOARD.get().defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.EAST), 3);
            this.at = WorldPos.of(level, pos);
            this.body = PersonBody.RESIDENT.get().create(level);
            body.mob().moveTo(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
            body.mob().setNoAi(true);
            level.addFreshEntity(body.mob());
            this.stances = Stances.at(level, body.mob().blockPosition());
            pack.setItem(0, new ItemStack(Items.CHICKEN));
            pack.setItem(1, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        }

        ChoppingBoardBlockEntity board() {
            return (ChoppingBoardBlockEntity) level.getBlockEntity(pos);
        }

        BoardWork plan(ItemStack product) {
            return plan(product, at, stances);
        }

        BoardWork plan(ItemStack product, WorldPos board, Stances footings) {
            var workshop = ChoppingBoardWorkshops.freeze(UUID.randomUUID(), colony, level, board, footings, bookings,
                () -> {}).orElseThrow();
            Produce produce = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(board), Goods.specOf(product),
                1, Produce.UNBOUNDED);
            Refinement.Change change = workshop.options(produce, Grown.of(produce)).stream()
                .filter(option -> option.replacement().nodes().getFirst().spec().needs().stream()
                    .allMatch(need -> Goods.countIn(pack, need.spec()) >= need.count()))
                .findFirst().orElseThrow();
            Hold hold = change.holds().getFirst();
            helper.assertTrue(hold.take().isEmpty(), "the batch can book its turn");
            holds.add(hold);
            return (BoardWork) change.replacement().nodes().getFirst();
        }

        List<Grown> recovery() {
            return ChoppingBoardWorkshops.recovery(colony, level, at, stances, bookings, () -> {});
        }

        Outcome step(BoardWork work) {
            Workload.Within.Turn turn = work.next(level, this);
            if (!(turn instanceof Workload.Within.Turn.Piece piece)) {
                throw new IllegalStateException("expected another board operation, got " + turn);
            }
            Outcome outcome = Commit.run(level, piece.node(), this);
            if (outcome instanceof Outcome.Done done) {
                done.made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(),
                    "every real product fits in the test pack"));
                work.ended(level, piece.node(), Ending.DONE);
            } else if (outcome instanceof Outcome.Failed failed) {
                work.ended(level, piece.node(), new Ending.Failed(piece.node().id(), failed.why()));
            }
            return outcome;
        }

        void finish(BoardWork work) {
            for (int turn = 0; turn < 20; turn++) {
                if (work.next(level, this) instanceof Workload.Within.Turn.Leave) {
                    helper.assertTrue(work.commit(level, this) instanceof Outcome.Done, "the whole batch completes");
                    return;
                }
                helper.assertTrue(step(work) instanceof Outcome.Done, "each actual board operation succeeds");
            }
            throw new IllegalStateException("board did not complete within twenty operations");
        }

        void assertProducts(long meats, long bones) {
            helper.assertValueEqual(Goods.countIn(pack, Goods.specOf(new ItemStack(ModItems.RAW_CUT_SMALL_MEATS.get()))),
                meats, "all meat portions are delivered");
            helper.assertValueEqual(Goods.countIn(pack, Goods.specOf(new ItemStack(Items.BONE))), bones,
                "all bone by-products are delivered");
            helper.assertValueEqual(Goods.countIn(pack, Goods.specOf(new ItemStack(Items.CHICKEN))), 0L,
                "each source ingredient is consumed exactly once");
        }

        ItemStack knife() {
            return pack.getItem(1);
        }

        @Override
        public Resident resident() { return body.resident(); }
        @Override
        public Mob body() { return body.mob(); }
        @Override
        public Container pack() { return pack; }
        @Override
        public List<Store> within() { return List.of(); }
        @Override
        public ItemStack held(ToolNeed need) {
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                if (Tools.answers(pack.getItem(slot), need)) {
                    return pack.getItem(slot);
                }
            }
            return ItemStack.EMPTY;
        }
        @Override
        public void spill(ItemStack stack) { helper.assertTrue(stack.isEmpty(), "nothing is spilled by the fixture"); }
        @Override
        public Effects effects() { return body.effects(); }
        @Override
        public List<Node> route() { return List.of(); }
        @Override
        public Placement placement() { return Placement.NOWHERE; }

        @Override
        public void close() {
            holds.forEach(hold -> hold.release(Ending.DROPPED));
            body.mob().discard();
        }
    }
}
