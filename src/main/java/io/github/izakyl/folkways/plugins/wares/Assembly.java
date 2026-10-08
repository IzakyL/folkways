package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

interface Assembly {

    // One run, drawn from the goods handed over for it; what the run does not take stays in the pool.
    Optional<List<ItemStack>> runOnce(ServerLevel level, Container pool);

    static Assembly grid(RecipeHolder<CraftingRecipe> holder, List<Ingredient> grid, int width, int height) {
        return (level, pool) -> {
            List<Predicate<ItemStack>> slots = new ArrayList<>();
            List<Integer> slotOf = new ArrayList<>();
            for (int slot = 0; slot < grid.size(); slot++) {
                if (!grid.get(slot).isEmpty()) {
                    slots.add(grid.get(slot));
                    slotOf.add(slot);
                }
            }
            return draw(pool, slots).flatMap(taken -> {
                List<ItemStack> shaped = new ArrayList<>(Collections.nCopies(grid.size(), ItemStack.EMPTY));
                for (int index = 0; index < taken.size(); index++) {
                    shaped.set(slotOf.get(index), taken.get(index));
                }
                CraftingInput input = CraftingInput.of(width, height,
                    shaped.stream().map(ItemStack::copy).toList());
                CraftingRecipe recipe = holder.value();
                if (!recipe.matches(input, level)) {
                    putBack(pool, taken);
                    return Optional.empty();
                }
                ItemStack result = recipe.assemble(input, level.registryAccess());
                if (result.isEmpty()) {
                    putBack(pool, taken);
                    return Optional.empty();
                }
                List<ItemStack> made = new ArrayList<>();
                made.add(result);
                NonNullList<ItemStack> left = recipe.getRemainingItems(input);
                for (ItemStack remaining : left) {
                    if (!remaining.isEmpty()) {
                        made.add(remaining.copy());
                    }
                }
                return Optional.of(List.copyOf(made));
            });
        };
    }

    static Assembly stonecut(RecipeHolder<StonecutterRecipe> holder, ItemSpec input) {
        return (level, pool) -> draw(pool, List.of(stack -> matches(stack, input)))
            .flatMap(taken -> {
                SingleRecipeInput single = new SingleRecipeInput(taken.get(0).copy());
                if (!holder.value().matches(single, level)) {
                    putBack(pool, taken);
                    return Optional.empty();
                }
                ItemStack result = holder.value().assemble(single, level.registryAccess());
                if (result.isEmpty()) {
                    putBack(pool, taken);
                    return Optional.empty();
                }
                return Optional.of(List.of(result));
            });
    }

    static Assembly smith(RecipeHolder<SmithingRecipe> holder) {
        SmithingRecipe recipe = holder.value();
        List<Predicate<ItemStack>> slots =
            List.of(recipe::isTemplateIngredient, recipe::isBaseIngredient, recipe::isAdditionIngredient);
        return (level, pool) -> draw(pool, slots).flatMap(taken -> {
            SmithingRecipeInput input = new SmithingRecipeInput(
                taken.get(0).copy(), taken.get(1).copy(), taken.get(2).copy());
            if (!recipe.matches(input, level)) {
                putBack(pool, taken);
                return Optional.empty();
            }
            ItemStack result = recipe.assemble(input, level.registryAccess());
            if (result.isEmpty()) {
                putBack(pool, taken);
                return Optional.empty();
            }
            return Optional.of(List.of(result));
        });
    }

    private static Optional<List<ItemStack>> draw(Container pool, List<Predicate<ItemStack>> slots) {
        List<ItemStack> taken = new ArrayList<>(slots.size());
        for (Predicate<ItemStack> slot : slots) {
            ItemStack drawn = Containers.extract(pool, slot, 1);
            if (drawn.isEmpty()) {
                putBack(pool, taken);
                return Optional.empty();
            }
            taken.add(drawn);
        }
        return Optional.of(taken);
    }

    private static void putBack(Container pool, List<ItemStack> taken) {
        for (ItemStack stack : taken) {
            Containers.insert(pool, stack);
        }
    }

    private static boolean matches(ItemStack stack, ItemSpec spec) {
        return Goods.matches(stack, spec);
    }
}
