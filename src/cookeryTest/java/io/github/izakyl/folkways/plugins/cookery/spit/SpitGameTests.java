package io.github.izakyl.folkways.plugins.cookery.spit;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.ShawarmaSpitBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SpitGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SpitGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 850)
    public static void aBookedKnifeCollectsRealCookedMeatWithoutBurnsOrHiddenPowerChanges(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        SpitWork work = rig.load();
        afterCooking(rig, ready -> {
            float health = ready.actor.getHealth();
            ItemStack previous = new ItemStack(Items.APPLE, 7);
            ready.actor.setItemInHand(InteractionHand.MAIN_HAND, previous);
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the booked knife uses native take-out");
            helper.assertValueEqual(ready.count(Items.COOKED_BEEF), 2L, "both native portions enter the actual pack");
            helper.assertValueEqual(ready.actor.getHealth(), health, "the owned knife grip prevents the native burn");
            helper.assertTrue(ready.actor.getMainHandItem() == previous, "the previous hand is restored");
            helper.assertTrue(ready.spit().getBlockState().getValue(ShawarmaSpitBlock.POWERED)
                && ready.level.getBlockState(ready.cell.west()).is(Blocks.REDSTONE_BLOCK), "the external power source is untouched");
            helper.assertTrue(ready.spit().cookedItem.isEmpty() && SpitBatch.read(ready.spit(), ready.level).isEmpty(), "the physical batch clears once");
            helper.assertTrue(work.commit(ready.level, ready) instanceof Outcome.Done, "the whole production offer settles");
            helper.assertTrue(ready.drops().isEmpty(), "captured products do not also leak as loose items");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 850)
    public static void anUnscopedKnifeHolderStillReceivesTheNativeBurn(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.load();
        afterCooking(rig, ready -> {
            ready.actor.setItemInHand(InteractionHand.MAIN_HAND, ready.pack.getItem(1));
            helper.assertTrue(ready.actor.invulnerableTime == 0 && ready.actor.getHealth() == ready.actor.getMaxHealth(), "the burn fixture starts unhurt");
            float before = ready.actor.getHealth();
            helper.assertTrue(ready.spit().onTakeCookedItem(ready.level, ready.actor), "ordinary native take-out remains available");
            helper.assertTrue(ready.actor.getHealth() < before, "a real knife alone does not create global fire immunity");
            helper.assertValueEqual(ready.drops().stream().filter(drop -> drop.getItem().is(Items.COOKED_BEEF))
                .mapToInt(drop -> drop.getItem().getCount()).sum(), 2, "the ordinary native result is still delivered on the ground");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 850)
    public static void anEmptyHandOutsideTheTaskStillUsesNativeDamageAndDelivery(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.load();
        afterCooking(rig, ready -> {
            ready.actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            float before = ready.actor.getHealth();
            helper.assertTrue(ready.spit().onTakeCookedItem(ready.level, ready.actor), "ordinary empty-hand take-out remains native");
            helper.assertTrue(ready.actor.getHealth() < before, "unscoped empty hands are not protected");
            helper.assertTrue(ready.actor.getMainHandItem().is(Items.COOKED_BEEF)
                && ready.actor.getMainHandItem().getCount() == 2, "native empty-hand delivery is unchanged");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 850)
    public static void theUpperHalfWorksWhilePlayerFoodInTheLowerHalfIsProtected(GameTestHelper helper) {
        Rig rig = new Rig(helper, true);
        ItemStack lower = rig.lower().cookingItem.copy();
        SpitWork work = rig.load();
        helper.assertTrue(ItemStack.matches(rig.lower().cookingItem, lower), "planning the upper half leaves player input intact");
        afterCooking(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the upper real block entity serves its own batch");
            helper.assertValueEqual(ready.count(Items.COOKED_BEEF), 2L, "only the colony's upper portions enter its pack");
            helper.assertTrue(!ready.lower().cookedItem.isEmpty() && ready.lower().cookedItem.is(Items.COOKED_PORKCHOP), "the player's native lower portion remains in place");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 900)
    public static void aPhysicalPowerLossPausesTheNativeClockAndRestoringItResumesCooking(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        SpitWork work = rig.load();
        rig.power(false);
        int remaining = rig.spit().cookTime;
        helper.runAfterDelay(20, () -> {
            helper.assertValueEqual(rig.spit().cookTime, remaining, "an unpowered native tower does not advance cooking");
            helper.assertTrue(work.next(rig.level, rig) instanceof Workload.Within.Turn.Wait, "the task cannot take unfinished food");
            rig.power(true);
        });
        afterCooking(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "actual cooking continues when the real signal returns");
            helper.assertValueEqual(ready.count(Items.COOKED_BEEF), 2L, "paused ingredients are neither lost nor duplicated");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 850)
    public static void aCanceledBatchRecoversWithoutAnotherRawMeatBudget(GameTestHelper helper) {
        Rig rig = new Rig(helper, false);
        rig.load();
        rig.hold.release(Ending.REVOKED);
        var recovery = new SpitStation().recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {});
        SpitWork work = (SpitWork) recovery.getFirst().nodes().getFirst();
        helper.assertTrue(work.spec().needs().isEmpty() && work.planned(rig.level).isEmpty(), "recovery adopts the actual saved cooking batch");
        afterCooking(rig, ready -> {
            helper.assertTrue(ready.step(work) instanceof Outcome.Done, "the real recovered batch can be served");
            helper.assertValueEqual(ready.count(Items.BEEF), 0L, "cancellation never refunds accepted meat");
            helper.assertValueEqual(ready.count(Items.COOKED_BEEF), 2L, "exactly the accepted portions are served");
            work.ended(ready.level, Ending.DONE);
        });
    }

    @GameTest(template = "empty")
    public static void withdrawalInvalidatesAnAlreadyPreparedLoad(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, false)) {
            SpitWork work = rig.plan();
            Node piece = ((Workload.Within.Turn.Piece) work.next(rig.level, rig)).node();
            rig.bookings.withdraw(rig.at);
            helper.assertTrue(Commit.run(rig.level, piece, rig) instanceof Outcome.Failed, "an old booking cannot load the tower");
            helper.assertValueEqual(rig.count(Items.BEEF), 2L, "the stale interaction returns its real input");
            helper.assertTrue(rig.spit().cookedItem.isEmpty() && rig.spit().cookingItem.isEmpty(), "the withdrawn tower is untouched");
        }
        helper.succeed();
    }

    @net.minecraft.gametest.framework.GameTestGenerator
    public static java.util.Collection<net.minecraft.gametest.framework.TestFunction> nativeCampfireRecipes() {
        return List.of("baked_potato", "cooked_beef", "cooked_chicken", "cooked_cod", "cooked_mutton", "cooked_porkchop",
            "cooked_rabbit", "cooked_salmon", "dried_kelp").stream().map(name -> new net.minecraft.gametest.framework.TestFunction(
                "cookery_spit_matrix", "folkways:spit_matrix_" + name, "folkways:empty", 1000, 0L, true, helper -> {
                    var id = net.minecraft.resources.ResourceLocation.withDefaultNamespace(name + "_from_campfire_cooking");
                    var recipe = helper.getLevel().getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CAMPFIRE_COOKING)
                        .stream().filter(holder -> holder.id().equals(id)).findFirst().orElseThrow();
                    ItemStack input = recipe.value().getIngredients().getFirst().getItems()[0];
                    ItemStack output = recipe.value().getResultItem(helper.getLevel().registryAccess()).copyWithCount(1);
                    Rig rig = new Rig(helper, false, input, output); SpitWork work = rig.load(); long started = rig.level.getGameTime();
                    afterCooking(rig, ready -> {
                        helper.assertTrue(ready.level.getGameTime() - started >= recipe.value().getCookingTime(), id + ": the real native clock elapsed");
                        helper.assertTrue(ready.step(work) instanceof Outcome.Done, id + ": native cooking is safely served with the actual knife");
                        helper.assertValueEqual(ready.count(output.getItem()), 2L, id + ": both native portions enter the real pack");
                        helper.assertValueEqual(ready.count(input.getItem()), 0L, id + ": the two real ingredients were spent once");
                        helper.assertTrue(ready.spit().cookedItem.isEmpty() && ready.spit().cookingItem.isEmpty() && ready.drops().isEmpty(), id + ": no physical or loose duplicate remains");
                    });
                })).toList();
    }

    @net.minecraft.gametest.framework.GameTestGenerator
    public static java.util.Collection<net.minecraft.gametest.framework.TestFunction> nativeOwnedCampfireRecipes() {
        List<net.minecraft.resources.ResourceLocation> ids = new java.util.ArrayList<>();
        try (var stream = SpitGameTests.class.getResourceAsStream("/cookery-native-recipes.json")) {
            if (stream == null) { throw new IllegalStateException("the pinned native recipe manifest is missing"); }
            for (var value : com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("recipes")) {
                String id = value.getAsString();
                if (id.startsWith("minecraft:") && id.endsWith("_from_campfire_cooking")) {
                    ids.add(net.minecraft.resources.ResourceLocation.parse(id));
                }
            }
        } catch (java.io.IOException broken) { throw new IllegalStateException("cannot read pinned native recipes", broken); }
        if (ids.size() != 7) { throw new IllegalStateException("expected all 7 native-owned campfire recipes, got " + ids.size()); }
        return ids.stream().map(id -> new net.minecraft.gametest.framework.TestFunction("cookery_spit_owned_matrix",
            "folkways:spit_owned_matrix_" + id.getPath(), "folkways:empty", 1000, 0L, true, helper -> {
                var level = helper.getLevel();
                var recipe = level.getRecipeManager().getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CAMPFIRE_COOKING)
                    .stream().filter(holder -> holder.id().equals(id)).findFirst().orElseThrow();
                ItemStack input = recipe.value().getIngredients().getFirst().getItems()[0].copyWithCount(1);
                var nativeSelection = level.getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CAMPFIRE_COOKING,
                    new net.minecraft.world.item.crafting.SingleRecipeInput(input), level).orElseThrow();
                helper.assertTrue(nativeSelection.id().equals(id), id + ": the actual native matcher selects this manifest recipe");
                helper.assertValueEqual(recipe.value().getCookingTime(), 600, id + ": the pinned native cooking time remains unchanged");
                ItemStack output = recipe.value().assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(input), level.registryAccess());
                helper.assertValueEqual(output.getCount(), 1, id + ": the native tower's one-result-per-input rule is applicable");
                Rig rig = new Rig(helper, false, input, output);
                ItemStack knife = rig.pack.getItem(1); ItemStack originalKnife = knife.copy();
                SpitWork work = rig.load(); long started = level.getGameTime();
                helper.assertTrue(ItemStack.matches(rig.spit().cookedItem, output.copyWithCount(2)), id + ": native loading creates the actual component-preserving output");
                afterCooking(rig, ready -> {
                    helper.assertTrue(ready.level.getGameTime() - started >= 600, id + ": six hundred real powered ticker steps elapsed");
                    helper.assertTrue(ready.step(work) instanceof Outcome.Done, id + ": the actual native knife interaction serves the batch");
                    int portions = 0; int knives = 0;
                    for (int slot = 0; slot < ready.pack.getContainerSize(); slot++) {
                        ItemStack stack = ready.pack.getItem(slot); if (stack.isEmpty()) { continue; }
                        if (ItemStack.isSameItemSameComponents(stack, output)) { portions += stack.getCount(); }
                        else if (ItemStack.isSameItemSameComponents(stack, originalKnife)) { knives += stack.getCount(); }
                        else { helper.fail(id + ": unexpected physical inventory after cooking: " + stack); }
                    }
                    helper.assertValueEqual(portions, 2, id + ": both real portions retain every native result component");
                    helper.assertValueEqual(knives, 1, id + ": exactly the borrowed native knife remains");
                    helper.assertTrue(ready.pack.getItem(1) == knife && ItemStack.matches(knife, originalKnife), id + ": native serving preserves the actual unworn knife reference");
                    helper.assertTrue(ready.spit().cookedItem.isEmpty() && ready.spit().cookingItem.isEmpty()
                        && SpitBatch.read(ready.spit(), ready.level).isEmpty() && ready.drops().isEmpty(), id + ": no raw input, claim or loose duplicate remains");
                    helper.assertTrue(work.commit(ready.level, ready) instanceof Outcome.Done, id + ": the actual production offer settles exactly once");
                });
            })).toList();
    }
    private static void afterCooking(Rig rig, Consumer<Rig> check) {
        rig.helper.startSequence().thenWaitUntil(() -> rig.helper.assertTrue(rig.spit().cookTime <= 0 && rig.spit().cookingItem.isEmpty(),
            "the real powered block ticker finishes the recipe")).thenExecute(() -> {
                try { check.accept(rig); } finally { rig.close(); }
            }).thenSucceed();
    }
    @GameTest(template = "empty")
    public static void anUpperSpitCannotPlanFromAStanceThatOnlyReachesItsLowerAnchor(GameTestHelper helper) {
        try (Rig rig = new Rig(helper, true)) {
            BlockPos edge = boundaryApron(rig);
            rig.actor.setPos(Vec3.atBottomCenterOf(edge));
            Stances offered = Stances.at(rig.level, edge);
            helper.assertTrue(Reach.standingIn(rig.actor, offered) && Reach.withinReach(edge, rig.cell)
                && Reach.inSight(rig.level, edge, rig.cell), "the original lower-anchor stance is physically valid and the core has arrived there");
            helper.assertTrue(!Reach.withinReach(edge, rig.part.block(rig.level)), "the same actual reserved stance cannot reach the upper native block entity");
            helper.assertTrue(new SpitStation().freeze(UUID.randomUUID(), rig.colony, rig.level, rig.at, offered,
                rig.bookings, () -> {}).isEmpty(), "no upper task is published when the only offered stance reaches its lower anchor");
            helper.assertTrue(rig.spit().cookingItem.isEmpty() && rig.count(Items.BEEF) == 2,
                "refusing an unreachable upper operation preserves all real raw inputs");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 850)
    public static void upperSpitPlansAndRecoversOnlyStancesThatPerformItsActualNativeAction(GameTestHelper helper) {
        Rig rig = new Rig(helper, true); BlockPos edge = boundaryApron(rig);
        BlockPos near = rig.cell.south(2);
        var provider = new SpitStation();
        var published = provider.stances(rig.level, rig.at).orElseThrow();
        helper.assertTrue(published.cells().contains(WorldPos.of(rig.level, edge))
            && published.cells().contains(WorldPos.of(rig.level, near)), "the real provider publishes both original lower and valid upper approaches");
        rig.stances = Stances.at(WorldPos.of(rig.level, edge), WorldPos.of(rig.level, near));
        rig.actor.setPos(Vec3.atBottomCenterOf(near));
        SpitWork original = rig.plan();
        helper.assertTrue(original.spec().stances().cells().equals(java.util.Set.of(WorldPos.of(rig.level, near))),
            "the actual produced upper batch prunes the misleading lower-only candidate before navigation");
        helper.assertTrue(rig.step(original) instanceof Outcome.Done && rig.spit().cookingItem.getCount() == 2,
            "an action from its published batch stance loads the actual upper native input");
        rig.hold.release(Ending.DROPPED); rig.hold = null;
        var grown = provider.recovery(rig.colony, rig.level, rig.at, rig.stances, rig.bookings, () -> {}).getFirst();
        SpitWork resumed = (SpitWork) grown.nodes().getFirst();
        helper.assertTrue(resumed.spec().needs().isEmpty()
            && resumed.spec().stances().cells().equals(java.util.Set.of(WorldPos.of(rig.level, near)))
            && resumed.planned(rig.level).isEmpty(), "the same native batch recovers with the same safe physical stance and no second raw charge");
        afterCooking(rig, ready -> {
            try {
                helper.assertTrue(ready.step(resumed) instanceof Outcome.Done, "the recovered upper native action uses its actual reserved approach");
                helper.assertValueEqual(ready.count(Items.COOKED_BEEF), 2L, "both original real portions are collected once");
                helper.assertValueEqual(ready.count(Items.BEEF), 0L, "accepted raw input is never refunded or recharged by approach recovery");
            } finally { resumed.ended(ready.level, Ending.DONE); }
        });
    }

    private static BlockPos boundaryApron(Rig rig) {
        for (int x = 0; x <= 4; x++) { for (int z = 0; z <= 2; z++) {
            if (x == 0 && z == 0) { continue; }
            BlockPos floor = rig.cell.offset(x, 0, z);
            rig.level.setBlockAndUpdate(floor.below(), Blocks.STONE.defaultBlockState());
            rig.level.setBlockAndUpdate(floor, Blocks.AIR.defaultBlockState());
            rig.level.setBlockAndUpdate(floor.above(), Blocks.AIR.defaultBlockState());
        } }
        return rig.cell.offset(4, 0, 2);
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
        final ItemStack input;
        final ItemStack output;
        Hold hold;
        Rig(GameTestHelper helper, boolean playerLower) {
            this(helper, playerLower, new ItemStack(Items.BEEF), new ItemStack(Items.COOKED_BEEF));
        }
        Rig(GameTestHelper helper, boolean playerLower, ItemStack input, ItemStack output) {
            this.input = input.copyWithCount(1); this.output = output.copyWithCount(1);
            this.helper = helper; level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(2, 1, 2));
            for (int x = -1; x <= 1; x++) { for (int z = -1; z <= 1; z++) {
                level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            } }
            var state = ModBlocks.SHAWARMA_SPIT.get().defaultBlockState();
            level.setBlockAndUpdate(cell, state);
            state.getBlock().setPlacedBy(level, cell, state, null, new ItemStack(ModItems.SHAWARMA_SPIT.get()));
            at = WorldPos.of(level, cell);
            part = playerLower ? at.above() : at;
            power(true);
            if (playerLower) { helper.assertTrue(lower().onPutCookingItem(level, new ItemStack(Items.PORKCHOP)), "the player supplies a real lower batch"); }
            actor = PersonBody.RESIDENT.get().create(level);
            actor.setPos(Vec3.atBottomCenterOf(cell.south()));
            actor.setNoAi(true);
            level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition());
            pack.setItem(0, input.copyWithCount(2));
            pack.setItem(1, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        }
        void power(boolean on) {
            level.setBlockAndUpdate(cell.west(), (on ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState());
            level.setBlockAndUpdate(cell.west().above(), (on ? Blocks.REDSTONE_BLOCK : Blocks.AIR).defaultBlockState());
        }
        ShawarmaSpitBlockEntity lower() { return (ShawarmaSpitBlockEntity) level.getBlockEntity(cell); }
        ShawarmaSpitBlockEntity spit() { return (ShawarmaSpitBlockEntity) level.getBlockEntity(part.block(level)); }
        SpitWork plan() {
            var bench = new SpitStation().freeze(UUID.randomUUID(), colony, level, at, stances, bookings, () -> {}).orElseThrow();
            Produce wanted = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(output), 2, Produce.UNBOUNDED);
            var option = bench.options(wanted, Grown.of(wanted)).getFirst();
            hold = option.holds().getFirst();
            helper.assertTrue(hold.take().isEmpty(), "the real tower is booked");
            return (SpitWork) option.replacement().nodes().getFirst();
        }
        SpitWork load() {
            SpitWork work = plan();
            Outcome outcome = step(work);
            helper.assertTrue(outcome instanceof Outcome.Done, "native tower loading: " + outcome);
            helper.assertValueEqual(spit().cookingItem.getCount(), 2, "the native tower holds both real ingredients");
            return work;
        }
        Outcome step(SpitWork work) {
            var turn = work.next(level, this);
            helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, "expected native spit action, got " + turn + "; refusal=" + work.refusal(level)
                + "; input=" + spit().cookingItem + "; output=" + spit().cookedItem + "; time=" + spit().cookTime);
            Node piece = ((Workload.Within.Turn.Piece) turn).node();
            Outcome result = Commit.run(level, piece, this);
            if (result instanceof Outcome.Done done) {
                done.made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "the native output fits"));
                work.ended(level, piece, Ending.DONE);
            }
            return result;
        }
        List<ItemEntity> drops() { return level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(2)); }
        long count(net.minecraft.world.item.Item item) { return Goods.countIn(pack, Goods.specOf(new ItemStack(item))); }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) {
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                if (Tools.answers(pack.getItem(slot), need)) { return pack.getItem(slot); }
            }
            return ItemStack.EMPTY;
        }
        public void spill(ItemStack stack) { throw new IllegalStateException("unexpected spill"); }
        public int rankOf(String perk) { return 0; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() {
            if (hold != null) { hold.release(Ending.DROPPED); }
            actor.discard();
            drops().forEach(ItemEntity::discard);
        }
    }
}
