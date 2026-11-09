package io.github.izakyl.folkways.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.container.SimpleInput;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.MillstoneRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.testing.NativeCookingMatrix;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.runtime.HoldingFixture;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.effect.Effects;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_cookery")
public final class MillstoneRecipeMatrixGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(MillstoneRecipeMatrixGameTests.class); }
    @GameTestGenerator
    public static Collection<TestFunction> nativeRecipes() {
        List<ResourceLocation> ids = NativeCookingMatrix.ids(id -> id.toString().startsWith("kaleidoscope_cookery:millstone/"));
        if (ids.size() != 85) { throw new IllegalStateException("expected all 85 native millstone recipes, got " + ids.size()); }
        return ids.stream().map(id -> new TestFunction("cookery_mill_matrix", "folkways_cookery:mill_matrix_" + id.getPath().replace('/', '_'),
            "folkways_core:empty", 2500, 0L, true, helper -> run(helper, id))).toList();
    }
    private static void run(GameTestHelper helper, ResourceLocation id) {
        Rig rig = new Rig(helper, id);
        MillstoneWork work = rig.plan();
        helper.assertTrue(rig.step(work) instanceof Outcome.Done, id + ": the real ingredients enter the actual native mill");
        helper.assertTrue(rig.mill().isBoundTo(rig.actor), id + ": the assigned resident becomes the real native motor");
        helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(rig.input)), 0L, id + ": the one actual ingredient was spent exactly once");
        int nativeTime = rig.mill().saveWithoutMetadata(rig.level.registryAccess()).getInt("Progress");
        helper.assertTrue(nativeTime > 0, id + ": processing starts with the actual native countdown");
        long started = rig.level.getGameTime();
        HoldingFixture drive = rig.holding(work).prepare(); List<ItemStack> actual = new ArrayList<>();
        float walked = rig.actor.walkDist;
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(rig.mill().getInput().isEmpty(), id + ": the unmodified native ticker finishes grinding"))
            .thenExecute(() -> {
                helper.assertTrue(rig.level.getGameTime() - started >= nativeTime, id + ": no progress or maturity is injected by the fixture");
                helper.assertTrue(!rig.mill().hasEntity() && MillstoneDrive.exiting(rig.actor), id + ": native completion releases but does not fake a physical exit");
                for (int slot = 0; slot < rig.mill().getOutputs().getSlots(); slot++) {
                    ItemStack output = rig.mill().getOutputs().getStackInSlot(slot); if (!output.isEmpty()) { actual.add(output.copy()); }
                }
                for (var promised : rig.recipe.value().results().stream().filter(output -> output.chance() >= 1).toList()) {
                    int total = actual.stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, promised.stack())).mapToInt(ItemStack::getCount).sum();
                    helper.assertTrue(total >= promised.stack().getCount(), id + ": every guaranteed native result is present in the actual output slots");
                }
            }).thenWaitUntil(() -> helper.assertTrue(!drive.running(), id + ": resident actually walks outside the capture region"))
            .thenExecute(() -> {
                try {
                    helper.assertTrue(rig.actor.walkDist > walked + 0.05F, id + ": exit required real locomotion");
                    helper.assertTrue(drive.step() instanceof Outcome.Done, id + ": driving settles only after the physical exit");

                    helper.assertTrue(rig.step(work) instanceof Outcome.Done, id + ": native output slots are actually extracted");
                    for (ItemStack output : actual) {
                        int expected = actual.stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, output)).mapToInt(ItemStack::getCount).sum();
                        int delivered = 0; for (int slot = 0; slot < rig.pack.getContainerSize(); slot++) {
                            ItemStack stack = rig.pack.getItem(slot); if (ItemStack.isSameItemSameComponents(stack, output)) { delivered += stack.getCount(); }
                        }
                        helper.assertValueEqual(delivered, expected, id + ": actual guaranteed and probabilistic outputs are delivered once without rerolling");
                    }
                    helper.assertTrue(work.commit(rig.level, rig.worker) instanceof Outcome.Done && rig.mill().isOutputEmpty() && !rig.mill().hasEntity(), id + ": the real batch completes empty and unbound");
                    helper.assertTrue(rig.level.getEntitiesOfClass(ItemEntity.class, new AABB(rig.cell).inflate(5)).isEmpty(), id + ": asynchronous ejection does not duplicate actual delivery");
                } finally { rig.close(); }
            }).thenSucceed();
    }
    private static final class Rig implements AutoCloseable {
        final Worker worker;
        private final java.util.Map<Node, HoldingFixture> claims =
                new java.util.IdentityHashMap<>();

        HoldingFixture holding(Node node) {
            return claims.computeIfAbsent(node, next -> new HoldingFixture(level, next, worker));
        }

        final GameTestHelper helper; final ServerLevel level; final UUID colony = UUID.randomUUID(); final BlockPos cell; final WorldPos at;
        final ResidentEntity actor; final Stances stances; final KitchenBookings bookings = new KitchenBookings(); final SimpleContainer pack = new SimpleContainer(12);
        final RecipeHolder<MillstoneRecipe> recipe; final ItemStack input; Hold hold;
        Rig(GameTestHelper helper, ResourceLocation id) {
            this.helper = helper; level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(3, 1, 3));
            recipe = level.getRecipeManager().getAllRecipesFor(ModRecipes.MILLSTONE_RECIPE).stream().filter(holder -> holder.id().equals(id)).findFirst().orElseThrow();
            helper.assertTrue(recipe.value().results().stream().anyMatch(output -> output.chance() >= 1), id + ": this recipe has no guaranteed product and cannot be promised by production");
            input = recipe.value().ingredient().getItems()[0].copyWithCount(1);
            for (int x = -3; x <= 3; x++) { for (int z = -3; z <= 3; z++) {
                level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(cell.offset(x, 0, z), Blocks.AIR.defaultBlockState()); level.setBlockAndUpdate(cell.offset(x, 1, z), Blocks.AIR.defaultBlockState());
            } }
            var state = ModBlocks.MILLSTONE.get().defaultBlockState(); level.setBlockAndUpdate(cell, state);
            state.getBlock().setPlacedBy(level, cell, state, null, new ItemStack(ModItems.MILLSTONE.get())); at = WorldPos.of(level, cell);
            actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.east(3))); actor.setOnGround(true); level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition()); pack.setItem(0, input.copy());
            helper.assertTrue(mill().matchRecipe(new SimpleInput(List.of(input)), level).filter(holder -> holder.id().equals(id)).isPresent(), id + ": the actual native matcher selects this exact resource");
            worker = TestWorker.of(actor).pack(pack).effects(Effects.NONE);
        }
        MillstoneBlockEntity mill() { return (MillstoneBlockEntity) level.getBlockEntity(cell); }
        MillstoneWork plan() {
            ItemStack desired = recipe.value().results().stream().filter(output -> output.chance() >= 1).findFirst().orElseThrow().stack();
            var bench = new MillstoneStation().freeze(UUID.randomUUID(), colony, level, at, stances, bookings, () -> {}).orElseThrow();
            Produce want = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(desired), desired.getCount(), Produce.UNBOUNDED);
            var option = bench.options(want, Grown.of(want)).stream().filter(change -> change.replacement().nodes().getFirst().spec().needs().stream()
                .allMatch(need -> Goods.matches(input, need.spec()) && need.count() == 1)).findFirst().orElseThrow();
            hold = option.holds().getFirst(); helper.assertTrue(hold.take().isEmpty(), recipe.id() + ": actual station is booked"); return (MillstoneWork) option.replacement().nodes().getFirst();
        }
        Outcome step(MillstoneWork work) {
            Outcome outcome = holding(work).step();
            if (outcome instanceof Outcome.Done done) {
                done.made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(),
                    "actual native products fit")); }
            return outcome;
        }
        public void close() { MillstoneDrive.stop(colony); if (hold != null) { hold.release(Ending.DROPPED); } actor.discard(); }
    }
}
