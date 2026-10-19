package io.github.izakyl.folkways.cookery.testing;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

// The pinned jar's manifest determines registration; each test resolves and executes that real runtime recipe.
public final class NativeCookingMatrix {
    private NativeCookingMatrix() { }

    public static List<TestFunction> recipes(String folder, int expected, int maxTicks,
                                             BiConsumer<GameTestHelper, ResourceLocation> execute) {
        var stream = Objects.requireNonNull(NativeCookingMatrix.class.getResourceAsStream("/cookery-native-recipes.json"));
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var manifest = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("recipes");
            List<ResourceLocation> recipes = new ArrayList<>();
            manifest.forEach(value -> {
                ResourceLocation id = ResourceLocation.parse(value.getAsString());
                if (id.getNamespace().equals("kaleidoscope_cookery") && id.getPath().startsWith(folder + "/")) recipes.add(id);
            });
            if (recipes.size() != expected) throw new IllegalStateException(folder + ": expected " + expected + " pinned native recipes, found " + recipes.size());
            return recipes.stream().map(id -> new TestFunction("cookery_" + folder + "_matrix",
                "folkways:matrix_" + id.getPath().replace('/', '_'), "folkways:empty", maxTicks, 0L, true,
                helper -> execute.accept(helper, id))).toList();
        } catch (java.io.IOException broken) {
            throw new IllegalStateException("cannot read pinned Cookery recipe manifest", broken);
        }
    }

    public static Optional<List<ItemStack>> ingredients(List<Ingredient> ingredients, boolean distinct,
                                                        Predicate<List<ItemStack>> nativeSelection) {
        NonNullList<ItemStack> input = NonNullList.withSize(ingredients.size(), ItemStack.EMPTY);
        return choose(ingredients, distinct, nativeSelection, input, 0)
            ? Optional.of(input.stream().map(ItemStack::copy).toList()) : Optional.empty();
    }

    private static boolean choose(List<Ingredient> ingredients, boolean distinct, Predicate<List<ItemStack>> selected,
                                   List<ItemStack> input, int at) {
        if (at == ingredients.size()) return selected.test(input);
        Ingredient ingredient = ingredients.get(at);
        if (ingredient.isEmpty()) return choose(ingredients, distinct, selected, input, at + 1);
        for (ItemStack candidate : ingredient.getItems()) {
            if (candidate.isEmpty() || distinct && input.stream().anyMatch(stack -> !stack.isEmpty() && stack.is(candidate.getItem()))) continue;
            input.set(at, candidate.copyWithCount(1));
            if (choose(ingredients, distinct, selected, input, at + 1)) return true;
            input.set(at, ItemStack.EMPTY);
        }
        return false;
    }

    public static void count(Map<Item, Long> counts, ItemStack stack) {
        if (!stack.isEmpty()) counts.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
    }

    public static void inventory(GameTestHelper helper, Container inventory, Map<Item, Long> expected, ResourceLocation recipe) {
        Map<Item, Long> actual = new LinkedHashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) count(actual, inventory.getItem(slot));
        helper.assertTrue(actual.equals(expected), "the real recipe conserves products, containers, tools and all spent inputs: "
            + recipe + "; expected=" + expected + "; actual=" + actual);
    }
}
