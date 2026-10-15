package io.github.izakyl.folkways.plugins.cookery.teapot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.ITeapot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
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
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.CookeryRefusal;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.cookery.tray.TrayStation;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TeapotGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(TeapotGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeFlowerTea(GameTestHelper helper) { brew(helper, "flower_tea"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeBarleyTea(GameTestHelper helper) { brew(helper, "barley_tea"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeTieguanyin(GameTestHelper helper) { brew(helper, "tieguanyin"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeBiluochun(GameTestHelper helper) { brew(helper, "biluochun"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeOolong(GameTestHelper helper) { brew(helper, "oolong"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeSakuraFubuki(GameTestHelper helper) { brew(helper, "sakura_fubuki"); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void nativeButterTea(GameTestHelper helper) { brew(helper, "butter_tea"); }

    private static void brew(GameTestHelper helper, String recipe) {
        Rig rig = new Rig(helper, recipe);
        TeapotWork work = rig.load(); long started = rig.level.getGameTime();
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.level.getGameTime() - started >= 200 + ready.job.time(), "both native ingredient grace and brewing clocks really elapsed");
            ItemStack old = new ItemStack(Items.APPLE, 5); ready.actor.setItemInHand(InteractionHand.MAIN_HAND, old);
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "actual native tea is poured through twelve physical cup blocks");
            ready.assertServed();
            helper.assertTrue(ready.actor.getMainHandItem() == old, "the precise previous main hand is restored after pouring");
            helper.assertTrue(work.commit(ready.level, ready) instanceof Outcome.Done, "the original production promise settles after physical serving");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void removingRealHeatPausesTheNativeBrewingClock(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        rig.level.setBlockAndUpdate(rig.cell.below(), Blocks.STONE.defaultBlockState());
        int before = rig.pot().getCurrentTick();
        helper.runAfterDelay(60, () -> {
            helper.assertValueEqual(rig.pot().getCurrentTick(), before, "a physically cold teapot does not advance its native ingredient clock");
            helper.assertTrue(work.next(rig.level, rig) instanceof Workload.Within.Turn.Wait, "unfinished raw tea cannot be served");
            rig.level.setBlockAndUpdate(rig.cell.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
            helper.assertTrue(work.refusal(rig.level).isEmpty(), "native base/chain appearance updates do not invalidate the actual tea batch");
        });
        afterBrewing(rig, ready -> { helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the same real tea resumes on returning heat"); ready.assertServed(); });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void cancellationAfterFluidRecoversOnlyTheUnspentTeaBagAndCups(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork old = rig.plan();
        helper.assertTrue(rig.step(old) instanceof Outcome.Done, "native filling accepts one real bucket");
        helper.assertValueEqual(rig.count(Items.BUCKET), 1L, "native filling returns its actual empty bucket");
        TeapotWork work = rig.recover();
        helper.assertTrue(work.spec().needs().stream().noneMatch(need -> Goods.matches(rig.job.bucket(), need.spec())), "partial recovery never budgets a second fluid bucket");
        helper.assertTrue(rig.step(work) instanceof Outcome.Done, "the remaining real tea bag is inserted once");
        afterBrewing(rig, ready -> { helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the saved fluid and newly accepted bag brew normally"); ready.assertServed(); });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void blockEntityReloadKeepsTheAcceptedTeaWithoutBudgetingAnotherFluidOrBag(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); rig.load();
        var saved = rig.pot().saveWithoutMetadata(rig.level.registryAccess());
        rig.level.removeBlockEntity(rig.cell);
        TeapotBlockEntity loaded = new TeapotBlockEntity(rig.cell, rig.level.getBlockState(rig.cell));
        loaded.loadWithComponents(saved, rig.level.registryAccess()); rig.level.setBlockEntity(loaded);
        TeapotWork work = rig.recover();
        helper.assertValueEqual(work.spec().needs().size(), 1, "fully accepted recovery needs only twelve real empty cups");
        afterBrewing(rig, ready -> { helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the saved native ticker reaches actual completion"); ready.assertServed(); });
    }

    @GameTest(template = "empty")
    public static void withdrawalRejectsAnAlreadyPlannedFluidInteraction(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, "barley_tea")) {
            TeapotWork work = rig.plan();
            var turn = work.next(rig.level, rig); helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "the native filling action is planned");
            rig.bookings.withdraw(rig.at);
            helper.assertTrue(Commit.run(rig.level, ((Workload.Within.Turn.Piece) turn).node(), rig) instanceof Outcome.Failed, "the withdrawn generation refuses the old fill");
            helper.assertValueEqual(rig.count(rig.job.bucket().getItem()), 1L, "the unaccepted full bucket remains real stock");
            helper.assertValueEqual(rig.count(Items.BUCKET), 0L, "no empty bucket is manufactured by a rejected interaction");
            helper.assertTrue(TeapotStation.empty(rig.pot()), "the actual teapot stays completely empty");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void elevenCupsCannotServeOrLoseAnyOfTheTwelveNativePortions(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        rig.pack.getItem(1).shrink(1);
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Failed, "the core rejects a missing twelfth physical cup before native serving");
            helper.assertValueEqual(ready.count(ModItems.EMPTY_CUP.get()), 11L, "the eleven real cups are unchanged");
            helper.assertValueEqual(ready.pot().getResult().getCount(), 12, "all twelve actual portions remain in the finished teapot");
            helper.assertValueEqual(ready.count(ready.job.output().getItem()), 0L, "no tea is produced without its real cup");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void playerCupPositionsAreNeverOverwrittenToServeAnOwnedPot(GameTestHelper helper) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        for (Direction side : Direction.Plane.HORIZONTAL) { rig.level.setBlockAndUpdate(rig.cell.relative(side), ModBlocks.EMPTY_CUP.get().defaultBlockState()); }
        afterBrewing(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Failed, "without a new empty reachable place, serving refuses");
            for (Direction side : Direction.Plane.HORIZONTAL) { helper.assertTrue(ready.level.getBlockState(ready.cell.relative(side)).is(ModBlocks.EMPTY_CUP.get()), "each player's real cup is preserved"); }
            helper.assertValueEqual(ready.count(ModItems.EMPTY_CUP.get()), 12L, "the task's own cups return after refusal");
            helper.assertValueEqual(ready.pot().getResult().getCount(), 12, "none of the native tea is taken");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aThrowBeforeNativeTakeLeavesTheFinishedPotAndRefundsOnlyCups(GameTestHelper helper) { throwFromPot(helper, false); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aThrowAfterNativeTakeRestoresExactlyOneFinishedPhysicalPot(GameTestHelper helper) { throwFromPot(helper, true); }

    private static void throwFromPot(GameTestHelper helper, boolean after) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        afterBrewing(rig, ready -> {
            var saved = ready.pot().saveWithoutMetadata(ready.level.registryAccess());
            ready.level.removeBlockEntity(ready.cell);
            TeapotBlockEntity throwing = new TeapotBlockEntity(ready.cell, ready.level.getBlockState(ready.cell)) {
                @Override public boolean takeTeapot(Level level, LivingEntity user) {
                    if (after) { super.takeTeapot(level, user); }
                    throw new IllegalStateException("fixture: native pot take hook failed " + (after ? "after" : "before") + " physical removal");
                }
            };
            throwing.loadWithComponents(saved, ready.level.registryAccess()); ready.level.setBlockEntity(throwing);
            helper.assertTrue(ready.step(work) instanceof Outcome.Failed, "the failed native extraction rolls back");
            ready.assertRolledBack();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aThrowOnTheFirstPouredCupRollsBackWithoutReturningSpentTeaIngredients(GameTestHelper helper) { throwWhileServing(helper, 1); }
    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aThrowOnTheLastPouredCupCannotLeaveElevenProductsAndRefundTwelveCups(GameTestHelper helper) { throwWhileServing(helper, 12); }

    private static void throwWhileServing(GameTestHelper helper, int failAt) {
        Rig rig = new Rig(helper, "barley_tea"); TeapotWork work = rig.load();
        afterBrewing(rig, ready -> {
            AtomicInteger actualPours = new AtomicInteger();
            Consumer<EntityJoinLevelEvent> hook = event -> {
                if (event.getLevel() == ready.level && event.getEntity() instanceof ItemEntity drop
                        && new AABB(ready.cell).inflate(3).contains(drop.position()) && drop.getItem().getItem() == ready.job.output().getItem()
                        && actualPours.incrementAndGet() == failAt) { throw new IllegalStateException("fixture: actual poured-cup delivery failed at " + failAt); }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, hook);
            try {
                helper.assertTrue(ready.step(work) instanceof Outcome.Failed, "partly completed physical serving is rolled back");
                helper.assertValueEqual(actualPours.get(), failAt, "the fixture failed at the requested real cup, not before pouring");
                ready.assertRolledBack();
            } finally { NeoForge.EVENT_BUS.unregister(hook); }
            TeapotWork recovered = ready.recover();
            helper.assertTrue(ready.step(recovered) instanceof Outcome.Done, "the same native finished batch can be served once after recovery");
            ready.assertServed();
            recovered.ended(ready.level, Ending.DONE);
        });
    }

    @GameTest(template = "empty", timeoutTicks = 2900)
    public static void freshLeavesBecomeARealCraftedTeaBagThenTwelveServedCups(GameTestHelper helper) {
        Rig rig = new Rig(helper, "tieguanyin");
        rig.pack.setItem(2, new ItemStack(ModItems.FRESH_TEA_LEAVES.get(), 8));
        rig.pack.setItem(3, new ItemStack(Items.IRON_NUGGET));
        BlockPos trayCell = rig.cell.east(2);
        rig.level.setBlockAndUpdate(trayCell.below(), Blocks.STONE.defaultBlockState());
        rig.level.setBlockAndUpdate(trayCell, ModBlocks.BAMBOO_TRAY.get().defaultBlockState());
        WorldPos trayAt = WorldPos.of(rig.level, trayCell);
        var bench = new TrayStation().freeze(UUID.randomUUID(), rig.colony, rig.level, trayAt, rig.stances, rig.bookings, () -> {}).orElseThrow();
        Produce dried = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(trayAt), Goods.specOf(new ItemStack(ModItems.DRIED_TEA_LEAVES.get())), 8, Produce.UNBOUNDED);
        var option = bench.options(dried, Grown.of(dried)).getFirst(); Hold trayHold = option.holds().getFirst();
        helper.assertTrue(trayHold.take().isEmpty(), "the native drying station is booked");
        Node trayWork = option.replacement().nodes().getFirst();
        helper.assertTrue(rig.step(trayWork) instanceof Outcome.Done, "all eight real fresh leaves are placed on the native tray");
        helper.startSequence().thenWaitUntil(() -> {
            var actual = (com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity) rig.level.getBlockEntity(trayCell);
            helper.assertTrue(actual.canTakeItemThroughFace(0, actual.getItem(0), Direction.DOWN), "the native drying completion flag is reached without injecting dried leaves");
        }).thenExecute(() -> {
            helper.assertTrue(rig.step(trayWork) instanceof Outcome.Done, "the actual dried leaves enter the real pack"); trayHold.release(Ending.DONE);
            List<ItemStack> grid = new ArrayList<>();
            for (int slot = 0; slot < 9; slot++) {
                Item item = slot == 4 ? Items.IRON_NUGGET : ModItems.DRIED_TEA_LEAVES.get();
                ItemStack used = Containers.extract(rig.pack, stack -> stack.is(item), 1);
                helper.assertTrue(!used.isEmpty(), "every real tea-bag crafting cell consumes existing basic-chain material"); grid.add(used);
            }
            CraftingInput input = CraftingInput.of(3, 3, grid);
            var recipe = rig.level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, rig.level).orElseThrow();
            helper.assertTrue(recipe.id().equals(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "tieguanyin_tea_bag")), "the real native shaped recipe matches the actual dried leaves and nugget");
            ItemStack bag = recipe.value().assemble(input, rig.level.registryAccess());
            helper.assertTrue(bag.is(ModItems.TIEGUANYIN_TEA_BAG.get()) && bag.getCount() == 1, "the tea bag comes from native assemble");
            helper.assertTrue(Containers.insert(rig.pack, bag).isEmpty(), "the actually crafted bag enters the pack");
            for (ItemStack remainder : recipe.value().getRemainingItems(input)) { if (!remainder.isEmpty()) { Containers.insert(rig.pack, remainder); } }
            rig.load();
        }).thenWaitUntil(() -> helper.assertTrue(rig.pot().getStatus() == ITeapot.FINISHED, "the real crafted bag completes native brewing"))
            .thenExecute(() -> {
                try {
                    TeapotWork work = rig.current;
                    helper.assertTrue(rig.step(work) instanceof Outcome.Done, "real cups serve tea made from the original fresh leaves"); rig.assertServed();
                    helper.assertValueEqual(rig.count(ModItems.FRESH_TEA_LEAVES.get()) + rig.count(ModItems.DRIED_TEA_LEAVES.get())
                        + rig.count(ModItems.TIEGUANYIN_TEA_BAG.get()) + rig.count(Items.IRON_NUGGET), 0L, "no intermediate or consumed base material is duplicated");
                } finally { rig.close(); }
            }).thenSucceed();
    }

    private static void afterBrewing(Rig rig, Consumer<Rig> check) {
        rig.helper.startSequence().thenWaitUntil(() -> rig.helper.assertTrue(rig.pot().getStatus() == ITeapot.FINISHED,
            "the actual native teapot finishes its unchanged ingredient and brewing clocks"))
            .thenExecute(() -> { try { check.accept(rig); } finally { rig.close(); } }).thenSucceed();
    }
    private static final class Rig implements Worker, AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        final Stances stances;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(20);
        final ResidentEntity actor;
        final TeapotStation.Job job;
        Hold hold;
        TeapotWork current;
        Rig(GameTestHelper helper, String name) {
            this.helper = helper; level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(2, 1, 2));
            job = TeapotStation.jobs(level).stream().filter(job -> job.recipe().equals(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "teapot/" + name))).findFirst().orElseThrow();
            for (int x = -1; x <= 1; x++) { for (int z = -1; z <= 1; z++) { level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState()); } }
            level.setBlockAndUpdate(cell.below(), Blocks.MAGMA_BLOCK.defaultBlockState()); level.setBlockAndUpdate(cell, ModBlocks.TEAPOT.get().defaultBlockState());
            at = WorldPos.of(level, cell); actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.south())); actor.setNoAi(true); level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition()); pack.setItem(0, job.bucket()); pack.setItem(1, new ItemStack(ModItems.EMPTY_CUP.get(), 12)); pack.setItem(2, job.input().copy());
        }
        TeapotBlockEntity pot() { return (TeapotBlockEntity) level.getBlockEntity(cell); }
        TeapotWork plan() {
            var bench = new TeapotStation().freeze(UUID.randomUUID(), colony, level, at, stances, bookings, () -> {}).orElseThrow();
            Produce wanted = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(job.output()), 12, Produce.UNBOUNDED);
            var option = bench.options(wanted, Grown.of(wanted)).stream().filter(change -> change.replacement().nodes().getFirst().spec().needs().stream()
                .anyMatch(need -> Goods.matches(job.input(), need.spec()))).findFirst().orElseThrow();
            hold = option.holds().getFirst(); helper.assertTrue(hold.take().isEmpty(), "the native teapot station is booked");
            current = (TeapotWork) option.replacement().nodes().getFirst(); return current;
        }
        TeapotWork load() {
            TeapotWork work = plan(); helper.assertTrue(step(work) instanceof Outcome.Done, "one real fluid bucket is accepted by native addTeaFluid");
            helper.assertTrue(step(work) instanceof Outcome.Done, "one real ingredient is accepted by native addIngredient");
            helper.assertValueEqual(pot().getTeaFluidId(), job.fluid(), "the actual teapot contains the requested native fluid");
            helper.assertTrue(ItemStack.matches(pot().getInput(), job.input()), "the actual native ingredient is present");
            return work;
        }
        TeapotWork recover() {
            if (hold != null) { hold.release(Ending.REVOKED); hold = null; }
            var recovered = new TeapotStation().recovery(colony, level, at, stances, bookings, () -> {});
            helper.assertValueEqual(recovered.size(), 1, "the actual owned batch has one recovery task");
            current = (TeapotWork) recovered.getFirst().nodes().getFirst(); helper.assertTrue(current.planned(level).isEmpty(), "the recovery acquires the current native station turn"); return current;
        }
        Outcome step(Node work) {
            var within = (Workload.Within) work.spec().workload(); var turn = within.next(level, this);
            if (!(turn instanceof Workload.Within.Turn.Piece)) {
                helper.fail("expected native appliance action, got " + turn + "; refusal=" + work.refusal(level) + "; pot=" + pot().getStatus()
                    + "; time=" + pot().getCurrentTick() + "; input=" + pot().getInput() + "; result=" + pot().getResult());
                return Outcome.failed(CookeryRefusal.STATION_CHANGED);
            }
            Node piece = ((Workload.Within.Turn.Piece) turn).node(); Outcome outcome = Commit.run(level, piece, this);
            if (outcome instanceof Outcome.Done done) { done.made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "actual native goods fit")); within.ended(level, piece, Ending.DONE); }
            return outcome;
        }
        void assertServed() {
            helper.assertValueEqual(count(job.output().getItem()), 12L, "exactly twelve physical filled cups enter the real pack");
            helper.assertValueEqual(count(ModItems.EMPTY_CUP.get()), 0L, "all twelve actual empty cups become served portions");
            helper.assertValueEqual(count(Items.BUCKET), 1L, "the one actual empty fluid bucket is preserved");
            helper.assertValueEqual(count(job.input().getItem()), 0L, "the real tea ingredient was consumed once");
            helper.assertValueEqual(count(ModItems.TEAPOT.get()), 0L, "no duplicate pot is added to the pack");
            helper.assertTrue(TeapotStation.empty(pot()) && TeaBatch.read(pot(), level).isEmpty(), "the one actual empty teapot is placed back at its station");
            helper.assertTrue(drops().isEmpty(), "no filled cups or pot also escape as ground duplicates");
        }
        void assertRolledBack() {
            helper.assertValueEqual(count(job.output().getItem()), 0L, "cancelled native cup drops are not also delivered");
            helper.assertValueEqual(count(ModItems.EMPTY_CUP.get()), 12L, "only this failed action's twelve physical cups are returned");
            helper.assertValueEqual(count(Items.BUCKET), 1L, "previously completed fluid loading remains consumed with its one empty bucket");
            helper.assertValueEqual(count(job.input().getItem()), 0L, "the already accepted tea bag is never refunded");
            helper.assertValueEqual(count(ModItems.TEAPOT.get()), 0L, "a captured temporary full pot does not become an extra inventory copy");
            helper.assertTrue(pot().getStatus() == ITeapot.FINISHED && pot().getResult().getCount() == 12 && TeaBatch.read(pot(), level).isPresent(), "the original actual native finished batch and ownership are restored");
            helper.assertTrue(drops().isEmpty(), "failed serving leaves no uncaptured real product or duplicate pot");
        }
        long count(Item item) { return Goods.countIn(pack, Goods.specOf(new ItemStack(item))); }
        List<ItemEntity> drops() { return level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(3)); }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new IllegalStateException("unexpected spill"); }
        public int rankOf(String perk) { return 0; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() { if (hold != null) { hold.release(Ending.DROPPED); } if (current != null) { current.ended(level, Ending.DROPPED); } actor.discard(); drops().forEach(ItemEntity::discard); }
    }
}
