package io.github.izakyl.folkways.cookery.board;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.ChoppingBoardRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.FolkwaysMod;
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
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
public final class BoardRecipeMatrixGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(BoardRecipeMatrixGameTests.class); }

    @GameTestGenerator
    public static Collection<TestFunction> nativeRecipes() {
        List<ResourceLocation> ids = NativeCookingMatrix.ids(id -> id.toString().startsWith("kaleidoscope_cookery:chopping_board/"));
        if (ids.size() != 16) {
            throw new IllegalStateException("expected all 16 native chopping recipes, got " + ids.size());
        }
        return ids.stream().map(id -> new TestFunction("cookery_board_matrix", "folkways:board_matrix_" + id.getPath().replace('/', '_'),
            "folkways:empty", 200, 0L, true, helper -> run(helper, id))).toList();
    }

    private static void run(GameTestHelper helper, ResourceLocation id) {
        try (Rig rig = new Rig(helper, id)) {
            var work = rig.plan(); int steps = 0; boolean completed = false;
            HoldingFixture holding = new HoldingFixture(rig.level, work, rig.worker);
            while (++steps <= rig.recipe.value().getCutCount() + 4) {
                if (holding.finished()) {
                    helper.assertTrue(work.commit(rig.level, rig.worker) instanceof Outcome.Done, id + ": actual board workload settles after native extraction");
                    completed = true; break;
                }
                Outcome outcome = holding.step();
                helper.assertTrue(outcome instanceof Outcome.Done, id + ": each physical native put/cut interaction succeeds: " + outcome);
                if (outcome instanceof Outcome.Done done) {
                    done.made().forEach(stack -> helper.assertTrue(Containers.insert(rig.pack, stack).isEmpty(), id + ": every actual main and secondary product fits"));
                }
            }
            helper.assertTrue(completed, id + ": finite native cut count completes without reloading an ingredient");
            List<ItemStack> expected = rig.recipe.value().getResults();
            for (ItemStack output : expected) {
                int wanted = expected.stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, output)).mapToInt(ItemStack::getCount).sum();
                int actual = 0;
                for (int slot = 0; slot < rig.pack.getContainerSize(); slot++) {
                    ItemStack stack = rig.pack.getItem(slot);
                    if (ItemStack.isSameItemSameComponents(stack, output)) {
                        actual += stack.getCount();
                    }
                }
                helper.assertValueEqual(actual, wanted, id + ": native result components and full counts, including every byproduct, are delivered exactly once");
            }
            helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(rig.input)), 0L, id + ": the one real raw ingredient was consumed once");
            helper.assertTrue(rig.board().getCurrentCutStack().isEmpty(), id + ": actual native board is empty after completed cutting");
            helper.assertTrue(rig.level.getEntitiesOfClass(ItemEntity.class, new AABB(rig.cell).inflate(2)).isEmpty(), id + ": products are not also left as loose drops");
        }
        helper.succeed();
    }

    private static final class Rig implements AutoCloseable {
        final Worker worker;
        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos cell;
        final WorldPos at;
        final ResidentEntity actor;
        final Stances stances;
        final KitchenBookings bookings = new KitchenBookings();
        final SimpleContainer pack = new SimpleContainer(12);
        final RecipeHolder<ChoppingBoardRecipe> recipe;
        final ItemStack input;
        Hold hold;

        Rig(GameTestHelper helper, ResourceLocation id) {
            this.helper = helper;
            level = helper.getLevel();
            cell = helper.absolutePos(new BlockPos(2, 1, 2));
            recipe = level.getRecipeManager().getAllRecipesFor(ModRecipes.CHOPPING_BOARD_RECIPE).stream()
                .filter(holder -> holder.id().equals(id)).findFirst().orElseThrow();
            input = recipe.value().getIngredient().getItems()[0].copyWithCount(1);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(cell, ModBlocks.CHOPPING_BOARD.get().defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
            at = WorldPos.of(level, cell);
            actor = PersonBody.RESIDENT.get().create(level);
            actor.setPos(Vec3.atBottomCenterOf(cell.south()));
            actor.setNoAi(true);
            level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition());
            pack.setItem(0, input.copy());
            pack.setItem(1, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
            helper.assertTrue(level.getRecipeManager().getRecipeFor(ModRecipes.CHOPPING_BOARD_RECIPE, new SingleRecipeInput(input), level)
                .filter(holder -> holder.id().equals(id)).isPresent(), id + ": the actual native ingredient matcher selects this exact resource");
            worker = TestWorker.of(actor).pack(pack).heldFromPack();
        }

        ChoppingBoardBlockEntity board() { return (ChoppingBoardBlockEntity) level.getBlockEntity(cell); }

        BoardWork plan() {
            ItemStack desired = recipe.value().getResults().getFirst();
            var bench = ChoppingBoardWorkshops.freeze(UUID.randomUUID(), colony, level, at, stances, bookings, () -> {}).orElseThrow();
            Produce want = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(desired), desired.getCount(), Produce.UNBOUNDED);
            var option = bench.options(want, Grown.of(want)).stream().filter(change -> change.replacement().nodes().getFirst().spec().needs().stream()
                .allMatch(need -> Goods.matches(input, need.spec()))).findFirst().orElseThrow();
            hold = option.holds().getFirst();
            helper.assertTrue(hold.take().isEmpty(), recipe.id() + ": the physical board turn is booked");
            return (BoardWork) option.replacement().nodes().getFirst();
        }

        public void close() {
            if (hold != null) {
                hold.release(Ending.DROPPED);
            }
            actor.discard();
        }
    }
}
