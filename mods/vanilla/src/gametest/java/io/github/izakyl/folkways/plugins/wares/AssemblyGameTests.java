package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.List;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AssemblyGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(AssemblyGameTests.class);
    }

    // A book is three paper and a leather in any order: four ingredients, laid out on a grid three wide and two high.
    @SuppressWarnings("unchecked")
    @GameTest(template = "empty")
    public static void aShapelessRecipeThatDoesNotFillItsGridIsAssembled(GameTestHelper helper) {
        RecipeHolder<CraftingRecipe> book = (RecipeHolder<CraftingRecipe>) helper.getLevel().getRecipeManager()
            .byKey(ResourceLocation.withDefaultNamespace("book")).orElseThrow();
        List<Ingredient> grid = List.copyOf(book.value().getIngredients());
        helper.assertValueEqual(grid.size(), 4, "a book's ingredients");
        SimpleContainer pool = new SimpleContainer(new ItemStack(Items.PAPER, 3), new ItemStack(Items.LEATHER));
        Optional<List<ItemStack>> made = Assembly.grid(book, grid, 3, 2).runOnce(helper.getLevel(), pool);
        helper.assertTrue(made.isPresent() && made.get().getFirst().is(Items.BOOK), "the book is made, not " + made);
        helper.assertTrue(pool.isEmpty(), "and its ingredients are spent");
        helper.succeed();
    }
}
