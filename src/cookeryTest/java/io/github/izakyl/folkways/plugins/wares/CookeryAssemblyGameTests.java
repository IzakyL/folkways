package io.github.izakyl.folkways.plugins.wares;

import com.google.gson.JsonParser;
import io.github.izakyl.folkways.FolkwaysMod;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryAssemblyGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryAssemblyGameTests.class); }

    private static Set<ResourceLocation> owned() {
        var manifest = JsonParser.parseReader(new InputStreamReader(java.util.Objects.requireNonNull(
            CookeryAssemblyGameTests.class.getResourceAsStream("/cookery-native-recipes.json")),
            StandardCharsets.UTF_8)).getAsJsonObject();
        Set<ResourceLocation> ids = new HashSet<>();
        manifest.getAsJsonArray("recipes").forEach(id -> ids.add(ResourceLocation.parse(id.getAsString())));
        return ids;
    }

    @GameTest(template = "empty")
    public static void everyOwnedCraftingResourceActuallyMatchesAssemblesAndReturnsItsContainers(GameTestHelper helper) {
        var level = helper.getLevel();
        Set<ResourceLocation> owned = owned();
        int count = 0;
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(recipe -> owned.contains(recipe.id())).sorted(Comparator.comparing(recipe -> recipe.id().toString())).toList()) {
            var recipe = holder.value();
            List<Ingredient> grid = List.copyOf(recipe.getIngredients());
            helper.assertFalse(grid.isEmpty(), holder.id() + " has finite production inputs");
            int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : Math.min(3, grid.size());
            int height = recipe instanceof ShapedRecipe shaped ? shaped.getHeight() : (grid.size() + width - 1) / width;
            List<ItemStack> input = new ArrayList<>();
            for (Ingredient ingredient : grid) {
                input.add(ingredient.isEmpty() ? ItemStack.EMPTY : java.util.Arrays.stream(ingredient.getItems())
                    .filter(stack -> !stack.isEmpty()).sorted(Comparator.comparing(stack ->
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())).findFirst().orElseThrow().copyWithCount(1));
            }
            while (input.size() < width * height) input.add(ItemStack.EMPTY);
            CraftingInput nativeInput = CraftingInput.of(width, height, input.stream().map(ItemStack::copy).toList());
            helper.assertTrue(recipe.matches(nativeInput, level), holder.id() + " actual native inputs match");
            ItemStack expected = recipe.assemble(nativeInput, level.registryAccess());
            helper.assertFalse(expected.isEmpty(), holder.id() + " returns a real product");
            List<ItemStack> expectedOutputs = new ArrayList<>();
            expectedOutputs.add(expected.copy());
            recipe.getRemainingItems(nativeInput).stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy)
                .forEach(expectedOutputs::add);
            SimpleContainer pool = new SimpleContainer(input.stream().map(ItemStack::copy).toArray(ItemStack[]::new));
            var made = Assembly.grid(holder, grid, width, height).runOnce(level, pool).orElseThrow();
            helper.assertTrue(pool.isEmpty(), holder.id() + " spends each actual ingredient exactly once");
            helper.assertValueEqual(made.size(), expectedOutputs.size(), holder.id() + " preserves all native containers");
            for (int index = 0; index < made.size(); index++) {
                helper.assertTrue(ItemStack.matches(made.get(index), expectedOutputs.get(index)),
                    holder.id() + " keeps native result count and components at " + index);
            }
            count++;
        }
        helper.assertValueEqual(count, 121, "all native shaped, shapeless and special rice-bowl crafting resources execute");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeNetheriteKnifeUpgradeConsumesTemplateAndRetainsRealToolComponents(GameTestHelper helper) {
        var level = helper.getLevel();
        @SuppressWarnings("unchecked")
        RecipeHolder<SmithingRecipe> holder = (RecipeHolder<SmithingRecipe>) level.getRecipeManager().byKey(
            ResourceLocation.withDefaultNamespace("netherite_kitchen_knife_smithing")).orElseThrow();
        ItemStack knife = BuiltInRegistries.ITEM.get(ResourceLocation.parse("kaleidoscope_cookery:diamond_kitchen_knife")).getDefaultInstance();
        knife.setDamageValue(7);
        knife.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Shared knife"));
        SimpleContainer pool = new SimpleContainer(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), knife.copy(),
            new ItemStack(Items.NETHERITE_INGOT));
        var made = Assembly.smith(holder).runOnce(level, pool).orElseThrow();
        helper.assertTrue(pool.isEmpty(), "the template, diamond knife and ingot are each spent");
        helper.assertValueEqual(made.size(), 1, "one actual upgraded knife, without an extra template or old knife");
        ItemStack expected = holder.value().assemble(new net.minecraft.world.item.crafting.SmithingRecipeInput(
            new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), knife.copy(), new ItemStack(Items.NETHERITE_INGOT)), level.registryAccess());
        helper.assertTrue(ItemStack.matches(made.getFirst(), expected), "native upgrade keeps damage and name components");
        helper.succeed();
    }
}
