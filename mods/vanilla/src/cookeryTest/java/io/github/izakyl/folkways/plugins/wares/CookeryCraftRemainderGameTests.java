package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.effect.Effects;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryCraftRemainderGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryCraftRemainderGameTests.class); }

    @SuppressWarnings("unchecked")
    @GameTest(template = "empty")
    public static void allEightRealDoughRecipesBudgetAndReturnExactlyOneBucketPerRun(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos cell = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(cell, Blocks.CRAFTING_TABLE.defaultBlockState()); WorldPos at = WorldPos.of(level, cell);
        for (int amount = 1; amount <= 8; amount++) {
            var id = ResourceLocation.withDefaultNamespace("flour_from_" + amount + "_wheat");
            var recipe = (RecipeHolder<CraftingRecipe>) level.getRecipeManager().byKey(id).orElseThrow();
            var grid = List.copyOf(recipe.value().getIngredients()); int width = Math.min(3, grid.size());
            var assembly = Assembly.grid(recipe, grid, width, (grid.size() + width - 1) / width);
            var result = recipe.value().getResultItem(level.registryAccess());
            var node = new PackCraftNode(at, Blocks.CRAFTING_TABLE, Stances.at(at.above()), Ingredients.demandsOf(grid).orElseThrow(),
                Goods.specOf(result), result.getCount(), 2, assembly);
            helper.assertTrue(node.spec().leaves().size() == 1 && Goods.matches(new ItemStack(Items.BUCKET), node.spec().leaves().getFirst().spec()),
                id + ": actual water-container remainders are in the planner's output capacity budget");
            helper.assertValueEqual(node.spec().leaves().getFirst().count(), 2L, id + ": two real runs reserve exactly two buckets without duplicating the main dough budget");
            var body = PersonBody.RESIDENT.get().create(level); body.setPos(Vec3.atBottomCenterOf(cell.south()));
            SimpleContainer pack = new SimpleContainer(12); pack.setItem(0, new ItemStack(ModItems.FLOUR.get(), amount * 2));
            pack.setItem(1, new ItemStack(Items.WATER_BUCKET)); pack.setItem(2, new ItemStack(Items.WATER_BUCKET));
            Worker worker = new Worker() {
                public Resident resident() { return body.resident(); }
                public Mob body() { return body; }
                public Container pack() { return pack; }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) { if (!stack.isEmpty()) { throw new IllegalStateException("unexpected spill"); } }
                public Effects effects() { return body.effects(); }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
            try {
                Outcome outcome = Commit.run(level, node, worker);
                helper.assertTrue(outcome instanceof Outcome.Done, id + ": the actual native recipe commits both real runs");
                ((Outcome.Done) outcome).made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "actual main and native remaining outputs fit"));
                helper.assertValueEqual(Goods.countIn(pack, Goods.specOf(ModItems.RAW_DOUGH.get().getDefaultInstance())), (long) amount * 2, id + ": every original flour becomes actual native dough once");
                helper.assertValueEqual(Goods.countIn(pack, Goods.specOf(new ItemStack(Items.BUCKET))), 2L, id + ": the actual recipe returns two empty buckets");
                helper.assertValueEqual(Goods.countIn(pack, Goods.specOf(ModItems.FLOUR.get().getDefaultInstance()))
                    + Goods.countIn(pack, Goods.specOf(new ItemStack(Items.WATER_BUCKET))), 0L, id + ": no accepted raw flour or full bucket remains");
            } finally { body.discard(); }
        }
        helper.succeed();
    }
}