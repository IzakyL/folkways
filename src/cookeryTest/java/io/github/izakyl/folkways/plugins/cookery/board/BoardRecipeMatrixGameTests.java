package io.github.izakyl.folkways.plugins.cookery.board;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.ChoppingBoardRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.google.gson.JsonParser;
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
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
public final class BoardRecipeMatrixGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(BoardRecipeMatrixGameTests.class); }
    @GameTestGenerator
    public static Collection<TestFunction> nativeRecipes() {
        List<ResourceLocation> ids = new ArrayList<>();
        try (var stream = BoardRecipeMatrixGameTests.class.getResourceAsStream("/cookery-native-recipes.json")) {
            if (stream == null) { throw new IllegalStateException("the pinned native recipe manifest is missing"); }
            for (var value : JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("recipes")) {
                String id = value.getAsString(); if (id.startsWith("kaleidoscope_cookery:chopping_board/")) { ids.add(ResourceLocation.parse(id)); }
            }
        } catch (java.io.IOException broken) { throw new IllegalStateException("cannot read pinned native recipes", broken); }
        if (ids.size() != 16) { throw new IllegalStateException("expected all 16 native chopping recipes, got " + ids.size()); }
        return ids.stream().map(id -> new TestFunction("cookery_board_matrix", "folkways:board_matrix_" + id.getPath().replace('/', '_'),
            "folkways:empty", 200, 0L, true, helper -> run(helper, id))).toList();
    }
    private static void run(GameTestHelper helper, ResourceLocation id) {
        try (Rig rig = new Rig(helper, id)) {
            var work = rig.plan(); int steps = 0; boolean completed = false;
            while (++steps <= rig.recipe.value().getCutCount() + 4) {
                var turn = work.next(rig.level, rig);
                if (turn instanceof Workload.Within.Turn.Leave) {
                    helper.assertTrue(work.commit(rig.level, rig) instanceof Outcome.Done, id + ": actual board workload settles after native extraction");
                    completed = true; break;
                }
                helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, id + ": native board action does not stall: " + turn);
                Node piece = ((Workload.Within.Turn.Piece) turn).node(); Outcome outcome = Commit.run(rig.level, piece, rig);
                helper.assertTrue(outcome instanceof Outcome.Done, id + ": each physical native put/cut interaction succeeds: " + outcome);
                if (outcome instanceof Outcome.Done done) {
                    done.made().forEach(stack -> helper.assertTrue(Containers.insert(rig.pack, stack).isEmpty(), id + ": every actual main and secondary product fits"));
                }
                work.ended(rig.level, piece, Ending.DONE);
            }
            helper.assertTrue(completed, id + ": finite native cut count completes without reloading an ingredient");
            List<ItemStack> expected = rig.recipe.value().getResults();
            for (ItemStack output : expected) {
                int wanted = expected.stream().filter(stack -> ItemStack.isSameItemSameComponents(stack, output)).mapToInt(ItemStack::getCount).sum();
                int actual = 0; for (int slot = 0; slot < rig.pack.getContainerSize(); slot++) {
                    ItemStack stack = rig.pack.getItem(slot); if (ItemStack.isSameItemSameComponents(stack, output)) { actual += stack.getCount(); }
                }
                helper.assertValueEqual(actual, wanted, id + ": native result components and full counts, including every byproduct, are delivered exactly once");
            }
            helper.assertValueEqual(Goods.countIn(rig.pack, Goods.specOf(rig.input)), 0L, id + ": the one real raw ingredient was consumed once");
            helper.assertTrue(rig.board().getCurrentCutStack().isEmpty(), id + ": actual native board is empty after completed cutting");
            helper.assertTrue(rig.level.getEntitiesOfClass(ItemEntity.class, new AABB(rig.cell).inflate(2)).isEmpty(), id + ": products are not also left as loose drops");
        }
        helper.succeed();
    }
    private static final class Rig implements Worker, AutoCloseable {
        final GameTestHelper helper; final ServerLevel level; final UUID colony = UUID.randomUUID(); final BlockPos cell; final WorldPos at;
        final ResidentEntity actor; final Stances stances; final KitchenBookings bookings = new KitchenBookings(); final SimpleContainer pack = new SimpleContainer(12);
        final RecipeHolder<ChoppingBoardRecipe> recipe; final ItemStack input; Hold hold;
        Rig(GameTestHelper helper, ResourceLocation id) {
            this.helper = helper; level = helper.getLevel(); cell = helper.absolutePos(new BlockPos(2, 1, 2));
            recipe = level.getRecipeManager().getAllRecipesFor(ModRecipes.CHOPPING_BOARD_RECIPE).stream().filter(holder -> holder.id().equals(id)).findFirst().orElseThrow();
            input = recipe.value().getIngredient().getItems()[0].copyWithCount(1);
            for (int x = -1; x <= 1; x++) { for (int z = -1; z <= 1; z++) { level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState()); } }
            level.setBlockAndUpdate(cell, ModBlocks.CHOPPING_BOARD.get().defaultBlockState()); at = WorldPos.of(level, cell);
            actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(cell.south())); actor.setNoAi(true); level.addFreshEntity(actor);
            stances = Stances.at(level, actor.blockPosition()); pack.setItem(0, input.copy()); pack.setItem(1, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
            helper.assertTrue(level.getRecipeManager().getRecipeFor(ModRecipes.CHOPPING_BOARD_RECIPE, new SingleRecipeInput(input), level)
                .filter(holder -> holder.id().equals(id)).isPresent(), id + ": the actual native ingredient matcher selects this exact resource");
        }
        ChoppingBoardBlockEntity board() { return (ChoppingBoardBlockEntity) level.getBlockEntity(cell); }
        BoardWork plan() {
            ItemStack desired = recipe.value().getResults().getFirst();
            var bench = ChoppingBoardWorkshops.freeze(UUID.randomUUID(), colony, level, at, stances, bookings, () -> {}).orElseThrow();
            Produce want = Produce.of(CookeryContent.ID, new WorkSite.AtBlock(at), Goods.specOf(desired), desired.getCount(), Produce.UNBOUNDED);
            var option = bench.options(want, Grown.of(want)).stream().filter(change -> change.replacement().nodes().getFirst().spec().needs().stream()
                .allMatch(need -> Goods.matches(input, need.spec()))).findFirst().orElseThrow();
            hold = option.holds().getFirst(); helper.assertTrue(hold.take().isEmpty(), recipe.id() + ": the physical board turn is booked"); return (BoardWork) option.replacement().nodes().getFirst();
        }
        public Resident resident() { return actor.resident(); }
        public Mob body() { return actor; }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { for (int slot = 0; slot < pack.getContainerSize(); slot++) { if (Tools.answers(pack.getItem(slot), need)) { return pack.getItem(slot); } } return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { throw new IllegalStateException("unexpected spill"); }
        public int rankOf(String perk) { return 0; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() { if (hold != null) { hold.release(Ending.DROPPED); } actor.discard(); }
    }
}
