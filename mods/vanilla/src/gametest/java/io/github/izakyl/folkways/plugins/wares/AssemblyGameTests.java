package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Goods;
import net.minecraft.core.NonNullList;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.List;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
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

    @SuppressWarnings("unchecked")
    @GameTest(template = "empty")
    public static void nativeCakeCraftingReservesAndReturnsAllThreeMilkContainers(GameTestHelper helper) {
        RecipeHolder<CraftingRecipe> cake = (RecipeHolder<CraftingRecipe>) helper.getLevel().getRecipeManager()
            .byKey(ResourceLocation.withDefaultNamespace("cake")).orElseThrow();
        Assembly assembly = Assembly.grid(cake, List.copyOf(cake.value().getIngredients()), 3, 3);
        helper.assertTrue(assembly.remainders().size() == 1 && Goods.matches(
            new ItemStack(Items.BUCKET), assembly.remainders().getFirst().spec()), "all native milk container slots share one actual bucket budget");
        helper.assertValueEqual(assembly.remainders().getFirst().count(), 3L, "the native recipe leaves three actual buckets per run");
        SimpleContainer pool = new SimpleContainer(new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
            new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.SUGAR, 2), new ItemStack(Items.EGG), new ItemStack(Items.WHEAT, 3));
        List<ItemStack> made = assembly.runOnce(helper.getLevel(), pool).orElseThrow();
        helper.assertValueEqual(made.stream().filter(stack -> stack.is(Items.CAKE)).mapToInt(ItemStack::getCount).sum(), 1, "the actual vanilla recipe makes one cake");
        helper.assertValueEqual(made.stream().filter(stack -> stack.is(Items.BUCKET)).mapToInt(ItemStack::getCount).sum(), 3, "actual getRemainingItems returns all three containers");
        helper.assertTrue(pool.isEmpty(), "native crafting consumes the exact original ingredients once");
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void customNativeRemaindersAreReadAcrossCompleteIngredientAlternatives(GameTestHelper helper) {
        Ingredient either = Ingredient.of(Items.WHEAT, Items.SUGAR);
        CraftingRecipe nativeRecipe = new ShapelessRecipe("", CraftingBookCategory.MISC, new ItemStack(Items.BOOK),
            NonNullList.of(Ingredient.EMPTY, either, either)) {
            public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
                NonNullList<ItemStack> left = NonNullList.withSize(input.size(), ItemStack.EMPTY);
                boolean bothSugar = input.items().stream().filter(stack -> stack.is(Items.SUGAR)).count() == 2;
                left.set(0, new ItemStack(bothSugar ? Items.GLASS_BOTTLE : Items.BUCKET, bothSugar ? 4 : 1));
                return left;
            }
        };
        var holder = new RecipeHolder<>(ResourceLocation.fromNamespaceAndPath("folkways_test", "native_remainder_override"), nativeRecipe);
        Assembly assembly = Assembly.grid(holder, List.copyOf(nativeRecipe.getIngredients()), 2, 1);
        helper.assertTrue(assembly.remainders().stream().anyMatch(amount -> Goods.matches(new ItemStack(Items.GLASS_BOTTLE), amount.spec()) && amount.count() == 4), "native output depending on both alternate ingredients is budgeted, although sugar itself has no crafting container");
        helper.assertTrue(assembly.remainders().stream().anyMatch(amount -> Goods.matches(new ItemStack(Items.BUCKET), amount.spec()) && amount.count() == 1), "the other actual native container possibility keeps its own stack room");
        var pool = new SimpleContainer(new ItemStack(Items.SUGAR, 2));
        List<ItemStack> made = assembly.runOnce(helper.getLevel(), pool).orElseThrow();
        helper.assertValueEqual(made.stream().filter(stack -> stack.is(Items.GLASS_BOTTLE)).mapToInt(ItemStack::getCount).sum(), 4, "the same native override actually returns the four containers when the complete input commits");
        helper.assertValueEqual(made.stream().filter(stack -> stack.is(Items.BOOK)).mapToInt(ItemStack::getCount).sum(), 1, "the actual primary output occurs once");
        helper.assertTrue(pool.isEmpty(), "native custom crafting spends the actual two ingredients");
        helper.succeed();
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
