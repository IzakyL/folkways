package io.github.izakyl.folkways.cookery.tray;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.BambooTrayRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.testing.KitchenRecoveryWanted;
import io.github.izakyl.folkways.cookery.tray.mixin.BambooTrayAccess;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.runtime.HoldingFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.Leaves;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
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
public final class TrayGameTests {
    private static final String LEAVES = "drying/fresh_tea_leaves_to_dried_tea_leaves";
    private static final String MUD = "wetting/clay_to_mud";
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(TrayGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void hardUrgesPreserveTheAcceptedDryingBatch(GameTestHelper helper) {
        Rig rig = new Rig(helper, LEAVES);
        TrayWork work = rig.plan();
        HoldingFixture holding = rig.holding(work).prepare().hardUrge();
        helper.assertTrue(rig.step(work) instanceof Outcome.Done, "the native tray accepts its original input");
        holding.hardUrge();
        helper.runAfterDelay(40, holding::hardUrge);
        afterProcessing(
            rig,
            ready -> {
            holding.prepare().hardUrge();
            helper.assertTrue(
                ready.step(work) instanceof Outcome.Done,
                "the original dried batch is taken once");
            holding.hardUrge();
            helper.assertValueEqual(
                Goods.countIn(ready.pack, Goods.specOf(ready.output)),
                (long) ready.output.getCount(),
                "suspension preserves the exact native output");
            helper.assertTrue(
                ready.tray().getItem(0).isEmpty() && ready.drops().isEmpty(),
                "no duplicate remains");
            }); }
    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void drying_fresh_tea_leaves_to_dried_tea_leaves(GameTestHelper helper) { runRecipe(helper, "drying/fresh_tea_leaves_to_dried_tea_leaves"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void drying_kelp_to_dried_kelp(GameTestHelper helper) { runRecipe(helper, "drying/kelp_to_dried_kelp"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void drying_mud_to_clay(GameTestHelper helper) { runRecipe(helper, "drying/mud_to_clay"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void drying_rotten_flesh_to_leather(GameTestHelper helper) { runRecipe(helper, "drying/rotten_flesh_to_leather"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void drying_wet_sponge_to_sponge(GameTestHelper helper) { runRecipe(helper, "drying/wet_sponge_to_sponge"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_black_concrete_powder_to_black_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/black_concrete_powder_to_black_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_blue_concrete_powder_to_blue_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/blue_concrete_powder_to_blue_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_brown_concrete_powder_to_brown_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/brown_concrete_powder_to_brown_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_clay_to_mud(GameTestHelper helper) { runRecipe(helper, "wetting/clay_to_mud"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_copper_block_to_oxidized_copper(GameTestHelper helper) { runRecipe(helper, "wetting/copper_block_to_oxidized_copper"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_cyan_concrete_powder_to_cyan_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/cyan_concrete_powder_to_cyan_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_dirt_to_mud(GameTestHelper helper) { runRecipe(helper, "wetting/dirt_to_mud"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_dried_kelp_to_kelp(GameTestHelper helper) { runRecipe(helper, "wetting/dried_kelp_to_kelp"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_dried_tea_leaves_to_fresh_tea_leaves(GameTestHelper helper) { runRecipe(helper, "wetting/dried_tea_leaves_to_fresh_tea_leaves"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_gray_concrete_powder_to_gray_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/gray_concrete_powder_to_gray_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_green_concrete_powder_to_green_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/green_concrete_powder_to_green_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_leather_to_rotten_flesh(GameTestHelper helper) { runRecipe(helper, "wetting/leather_to_rotten_flesh"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_light_blue_concrete_powder_to_light_blue_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/light_blue_concrete_powder_to_light_blue_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_light_gray_concrete_powder_to_light_gray_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/light_gray_concrete_powder_to_light_gray_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_lime_concrete_powder_to_lime_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/lime_concrete_powder_to_lime_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_magenta_concrete_powder_to_magenta_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/magenta_concrete_powder_to_magenta_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_orange_concrete_powder_to_orange_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/orange_concrete_powder_to_orange_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_pink_concrete_powder_to_pink_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/pink_concrete_powder_to_pink_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_purple_concrete_powder_to_purple_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/purple_concrete_powder_to_purple_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_red_concrete_powder_to_red_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/red_concrete_powder_to_red_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_sponge_to_wet_sponge(GameTestHelper helper) { runRecipe(helper, "wetting/sponge_to_wet_sponge"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_white_concrete_powder_to_white_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/white_concrete_powder_to_white_concrete"); }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void wetting_yellow_concrete_powder_to_yellow_concrete(GameTestHelper helper) { runRecipe(helper, "wetting/yellow_concrete_powder_to_yellow_concrete"); }

    private static void runRecipe(GameTestHelper helper, String recipe) {
        Rig rig = new Rig(helper, recipe);
        TrayWork work = rig.load();
        helper.assertTrue(work.planningLeaves(work.spec().gives()).isEmpty(),
            "each native tray result is budgeted once when fully promised");
        helper.assertTrue(work.planningLeaves(List.of()).equals(Leaves.merged(work.spec().leaves())),
            "an autonomous tray take still reserves every unpromised result");
        long started = rig.level.getGameTime();
        afterProcessing(rig, ready -> {
            helper.assertTrue(ready.level.getGameTime() - started >= ready.recipe.value().getDuration() - 19,
                "the production waited for the real native 19-tick processing clock");
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the native onTakeOut collects the completed real slot");
            helper.assertValueEqual(Goods.countIn(ready.pack, Goods.specOf(ready.output)), (long) ready.output.getCount(),
                "the actual native recipe result enters the pack once");
            helper.assertTrue(ready.tray().getItem(0).isEmpty() && ready.drops().isEmpty(), "no product remains both in the slot and as a loose duplicate");
            helper.assertTrue(work.commit(ready.level, ready.worker) instanceof Outcome.Done, "the production offer settles after real extraction");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void anotherPlayersSlotIsNeverIncludedInTheOwnedTake(GameTestHelper helper) {
        Rig rig = new Rig(helper, LEAVES);
        rig.tray().onPutItem(rig.level, rig.actor, new ItemStack(Items.APPLE, 3), 1);
        TrayWork work = rig.load();
        afterProcessing(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "one owned slot is collected");
            helper.assertTrue(ready.tray().getItem(1).is(Items.APPLE) && ready.tray().getItem(1).getCount() == 3,
                "the player's different physical slot is preserved");
            helper.assertValueEqual(Goods.countIn(ready.pack, Goods.specOf(new ItemStack(Items.APPLE))), 0L, "no player food enters colony logistics");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2300)
    public static void anOpaqueRoofPausesDryingAndRemovingItResumesTheNativeRecipe(GameTestHelper helper) {
        Rig rig = new Rig(helper, LEAVES);
        TrayWork[] recovered = new TrayWork[1];
        boolean[] closed = {false};
        Consumer<Ending> close = how -> {
            if (closed[0]) {
                return;
            }
            closed[0] = true;
            try {
                if (recovered[0] != null) {
                    recovered[0].ended(rig.level, how);
                }
            } finally {
                rig.close();
            }
        };
        Cleanup.on(helper, () -> close.accept(Ending.DONE), () -> close.accept(Ending.DROPPED));
        rig.level.setBlockAndUpdate(rig.cell.above(2), Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(100, () -> helper.assertTrue(!rig.tray().getItem(0).isEmpty(),
            "the native roof exposure settles within 100 ticks before loading real input"));
        helper.startSequence()
            // Native exposure first reads asynchronous sky light; do not load during that update window.
            .thenWaitUntil(() -> helper.assertTrue(!rig.level.canSeeSky(rig.cell.above())
                && !BambooTrayAccess.folkways$exposure(rig.level, rig.cell), "the actual roof blocks native sky exposure"))
            .thenExecute(() -> {
                // Automatic work correctly waits for exposure; native player insertion also works under shelter.
                ItemStack input = rig.pack.removeItem(0, 2);
                helper.assertTrue(rig.tray().onPutItem(rig.level, rig.actor, input, 0) && input.isEmpty(),
                    "the native slot consumes the two real raw leaves from the pack");
                TrayBatch.remember(rig.tray(), rig.level, new TrayBatch.Batch(rig.colony, UUID.randomUUID(), rig.recipe.id(), 0,
                    rig.recipe.value().getSubtype(), rig.recipe.value().getDuration(), rig.input.copyWithCount(2), rig.output.copy()));
                var recovery = rig.recovery();
                helper.assertValueEqual(recovery.size(), 1, "the actual owned native slot is recovered");
                recovered[0] = (TrayWork) recovery.getFirst().nodes().getFirst();
                helper.assertTrue(recovered[0].planned(rig.level).isEmpty(), "the real recovery books its normal turn");
            })
            .thenExecuteAfter(60, () -> {
                helper.assertValueEqual(rig.progress(), 0, "the real native exposure check does not dry through an opaque roof");
                helper.assertTrue(ItemStack.matches(rig.tray().getItem(0), rig.input.copyWithCount(2)),
                    "sheltered raw leaves remain unchanged in the actual slot");
                helper.assertTrue(
                    rig.holding(recovered[0]).running(),
                    "the actual recovery workload waits for its sheltered native"
                    + " batch");
                rig.level.setBlockAndUpdate(rig.cell.above(2), Blocks.AIR.defaultBlockState());
            })
            .thenWaitUntil(() -> helper.assertTrue(TrayBatch.read(rig.tray(), rig.level)
                .filter(batch -> TrayBatch.ready(rig.tray(), rig.level, batch)).isPresent(),
                "opening real sky exposure resumes and completes the native drying recipe"))
            .thenExecute(() -> {
                helper.assertTrue(rig.step(recovered[0]) instanceof Outcome.Done, "the actual recovery collects the finished native slot");
                helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(rig.output)), (long) rig.output.getCount(),
                    "the real recipe output enters the pack exactly once");
                helper.assertTrue(rig.tray().getItem(0).isEmpty() && rig.drops().isEmpty(), "the real harvest leaves no duplicate output");
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1300)
    public static void removingActualDripstoneWaterPausesWettingWithoutChangingItsAcceptedInput(GameTestHelper helper) {
        Rig rig = new Rig(helper, MUD);
        TrayWork work = rig.load();
        rig.level.setBlockAndUpdate(rig.cell.above(5), Blocks.AIR.defaultBlockState());
        helper.runAfterDelay(60, () -> {
            helper.assertValueEqual(rig.progress(), 0, "a dry stalactite cannot wet a native tray");
            helper.assertTrue(ItemStack.matches(rig.tray().getItem(0), rig.input.copyWithCount(2)), "waiting preserves the actual clay input");
            rig.level.setBlockAndUpdate(rig.cell.above(5), Blocks.WATER.defaultBlockState());
        });
        afterProcessing(rig, ready -> helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the same native wetting batch resumes after water returns"));
    }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void cancellationAndBlockEntityReloadRecoverTheSlotWithoutAnotherInputBudget(GameTestHelper helper) {
        Rig rig = new Rig(helper, LEAVES);
        rig.load(); rig.hold.release(Ending.REVOKED);
        rig.reload(BambooTrayBlockEntity::new);
        var recovered = rig.recovery();
        helper.assertValueEqual(recovered.size(), 1, "the actual saved native slot is adopted");
        TrayWork work = (TrayWork) recovered.getFirst().nodes().getFirst();
        helper.assertTrue(work.spec().needs().isEmpty() && work.planned(rig.level).isEmpty(), "recovery does not budget another pair of leaves");
        KitchenRecoveryWanted.assertKept(helper, recovered.getFirst(), rig::recovery);
        afterProcessing(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the saved slot completes through its normal native ticker");
            helper.assertValueEqual(Goods.countIn(ready.pack, Goods.specOf(ready.input)), 0L, "canceling never refunds accepted leaves");
            helper.assertValueEqual(Goods.countIn(ready.pack, Goods.specOf(ready.output)), 2L, "exactly the saved real portions are delivered");
            KitchenRecoveryWanted.assertKept(helper, recovered.getFirst(), ready::recovery);
            work.ended(ready.level, Ending.DONE);
            helper.assertTrue(new TrayStation().activeRecovery(ready.colony, ready.level, ready.at, ready.bookings).isEmpty(), "ended tray recovery is removed from wanted");
        });
    }

    @GameTest(template = "empty")
    public static void withdrawingAPlannedTrayReturnsTheOriginalIngredientsWithoutPuttingAny(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, LEAVES)) {
            TrayWork work = rig.plan();
            HoldingFixture load = rig.holding(work).prepare();
            rig.bookings.withdraw(rig.at);
            helper.assertTrue(
                load.step() instanceof Outcome.Failed, "a withdrawn generation cannot put into the real slot");
            helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(rig.input)), 2L, "the core returns the two unaccepted leaves");
            helper.assertTrue(rig.tray().isEmpty(), "the withdrawn tray remains physically empty");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPlayerAppendingMoreLeavesInvalidatesTheFullComponentAndCountClaim(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, LEAVES)) {
            TrayWork work = rig.load();
            helper.assertTrue(rig.tray().onPutItem(rig.level, rig.actor, rig.input.copyWithCount(1), 0), "a player appends one actual leaf through the public API");
            helper.assertTrue(work.refusal(rig.level).isPresent(), "three real leaves are not the colony's original two-leaf claim");
            helper.assertValueEqual(rig.tray().getItem(0).getCount(), 3, "ownership refusal leaves all real contents in place");
            helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(rig.input)), 0L, "invalidating a claim never refunds spent leaves");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 2200)
    public static void aNativeTakeHookThrowingAfterClearStillSettlesItsActualHandProduct(GameTestHelper helper) {
        Rig rig = new Rig(helper, LEAVES);
        TrayWork work = rig.load();
        afterProcessing(rig, ready -> {
            ready.reload((cell, state) -> new BambooTrayBlockEntity(cell, state) {
                @Override public boolean onTakeOut(Level level, LivingEntity user, int slot, boolean takeAll) {
                    super.onTakeOut(level, user, slot, takeAll);
                    throw new IllegalStateException("fixture: native tray extraction hook failed after actual clear");
                }
            });
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the cleared real slot settles instead of replaying extraction");
            helper.assertValueEqual(Goods.countIn(ready.pack, Goods.specOf(ready.output)), 2L, "the real hand-delivered product survives the hook exception");
            helper.assertTrue(ready.tray().getItem(0).isEmpty() && ready.drops().isEmpty(), "no extra copy is left after settlement");
        });
    }

    private static void afterProcessing(Rig rig, Consumer<Rig> check) {
        rig.helper.startSequence().thenWaitUntil(() -> rig.helper.assertTrue(TrayBatch.read(rig.tray(), rig.level)
            .filter(batch -> TrayBatch.ready(rig.tray(), rig.level, batch)).isPresent(), "the real native slot reaches its requested completion state"))
            .thenExecute(() -> { try { check.accept(rig); } finally { rig.close(); } }).thenSucceed();
    }
    private static final class Rig implements AutoCloseable {
        final Worker worker;
        private final java.util.Map<Node, HoldingFixture> claims =
                new java.util.IdentityHashMap<>();

        HoldingFixture holding(Node node) {
            return claims.computeIfAbsent(node, next -> new HoldingFixture(level, next, worker));
        }

        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        final Stances stances;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(12);
        final ResidentEntity actor;
        final RecipeHolder<BambooTrayRecipe> recipe;
        final ItemStack input;
        final ItemStack output;
        Hold hold;
        Rig(GameTestHelper helper, String recipeName) {
            this.helper = helper; level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(2, 1, 2));
            recipe = level.getRecipeManager().getAllRecipesFor(ModRecipes.BAMBOO_TRAY_RECIPE).stream().filter(holder ->
                holder.id().equals(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "bamboo_tray/" + recipeName))).findFirst().orElseThrow();
            input = recipe.value().getIngredient().getItems()[0].copyWithCount(1);
            output = recipe.value().getResult().copyWithCount(recipe.value().getResult().getCount() * 2);
            for (int x = -1; x <= 1; x++) { for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            } }
            level.setBlockAndUpdate(cell, ModBlocks.BAMBOO_TRAY.get().defaultBlockState());
            if (recipe.value().getSubtype() == BambooTrayRecipe.Subtype.WETTING) {
                level.setBlockAndUpdate(cell.above(4), Blocks.DRIPSTONE_BLOCK.defaultBlockState());
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    level.setBlockAndUpdate(cell.above(5).relative(side), Blocks.STONE.defaultBlockState());
                }
                level.setBlockAndUpdate(cell.above(5), Blocks.WATER.defaultBlockState());
                level.setBlockAndUpdate(cell.above(3), Blocks.POINTED_DRIPSTONE.defaultBlockState()
                    .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN).setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP));
            }
            at = WorldPos.of(level, cell);
            actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.south())); actor.setNoAi(true); level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition()); pack.setItem(0, input.copyWithCount(2));
            worker = TestWorker.of(actor).pack(pack);
        }
        BambooTrayBlockEntity tray() { return (BambooTrayBlockEntity) level.getBlockEntity(cell); }
        // The tray as saved, loaded back into a fresh block entity.
        void reload(BiFunction<BlockPos, BlockState, BambooTrayBlockEntity> fresh) {
            var saved = tray().saveWithoutMetadata(level.registryAccess());
            level.removeBlockEntity(cell);
            BambooTrayBlockEntity loaded = fresh.apply(cell, level.getBlockState(cell));
            loaded.loadWithComponents(saved, level.registryAccess());
            level.setBlockEntity(loaded);
        }
        List<Grown> recovery() { return new TrayStation().recovery(colony, level, at, stances, bookings, () -> {}); }
        int progress() { return tray().saveWithoutMetadata(level.registryAccess()).getIntArray("ProcessingProgress")[0]; }
        TrayWork plan() {
            var bench = new TrayStation().freeze(UUID.randomUUID(), colony, level, at, stances, bookings, () -> {}).orElseThrow();
            Produce wanted = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(output), output.getCount(), Produce.UNBOUNDED);
            var option = bench.options(wanted, Grown.of(wanted)).stream().filter(change -> change.replacement().nodes().getFirst().spec().needs()
                .stream().allMatch(need -> Goods.matches(input, need.spec()))).findFirst().orElseThrow();
            hold = option.holds().getFirst(); helper.assertTrue(hold.take().isEmpty(), "the real tray turn is booked");
            return (TrayWork) option.replacement().nodes().getFirst();
        }
        TrayWork load() {
            TrayWork work = plan(); helper.assertTrue(step(work) instanceof Outcome.Done, "the native slot accepts real input");
            helper.assertTrue(ItemStack.matches(tray().getItem(0), input.copyWithCount(2)), "the real tray stores both raw inputs");
            return work;
        }
        Outcome step(TrayWork work) {
            Outcome outcome = holding(work).step();
            if (outcome instanceof Outcome.Done done) {
                done.made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(),
                    "actual native products fit"));
            }
            return outcome;
        }
        List<ItemEntity> drops() { return level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(2)); }
        public void close() { if (hold != null) { hold.release(Ending.DROPPED); } actor.discard(); drops().forEach(ItemEntity::discard); }
    }
}
