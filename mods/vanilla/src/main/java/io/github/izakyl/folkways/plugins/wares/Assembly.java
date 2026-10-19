package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

interface Assembly {

    default Optional<List<ItemStack>> runOnce(ServerLevel level, Container pool,
            io.github.izakyl.folkways.core.api.work.Worker who) {
        return runOnce(level, pool);
    }

    static Assembly rewarded(net.minecraft.resources.ResourceLocation recipe, Assembly base) {
        return new Assembly() {
            @Override
            public List<Amount> remainders() { return base.remainders(); }

            @Override
            public Optional<List<ItemStack>> runOnce(ServerLevel level, Container pool) {
                return base.runOnce(level, pool);
            }

            @Override
            public Optional<List<ItemStack>> runOnce(ServerLevel level, Container pool,
                    io.github.izakyl.folkways.core.api.work.Worker who) {
                List<ItemStack> before = new ArrayList<>();
                for (int slot = 0; slot < pool.getContainerSize(); slot++) {
                    before.add(pool.getItem(slot).copy());
                }
                return base.runOnce(level, pool).map(result -> {
                    List<ItemStack> made = new ArrayList<>(result);
                    CraftPerks.reward(level, recipe, before, pool, who, made);
                    return List.copyOf(made);
                });
            }
        };
    }

    // One run, drawn from the goods handed over for it; what the run does not take stays in the pool.
    Optional<List<ItemStack>> runOnce(ServerLevel level, Container pool);

    default List<Amount> remainders() { return List.of(); }

    static Assembly grid(RecipeHolder<CraftingRecipe> holder, List<Ingredient> grid, int width, int height) {
        return rewarded(holder.id(), gridBase(holder, grid, width, height));
    }

    private static Assembly gridBase(RecipeHolder<CraftingRecipe> holder, List<Ingredient> grid, int width, int height) {
        Assembly making = (level, pool) -> {
            List<Predicate<ItemStack>> slots = new ArrayList<>();
            List<Integer> slotOf = new ArrayList<>();
            for (int slot = 0; slot < grid.size(); slot++) {
                if (!grid.get(slot).isEmpty()) {
                    slots.add(grid.get(slot));
                    slotOf.add(slot);
                }
            }
            return draw(pool, slots).flatMap(taken -> {
                // A shapeless recipe's ingredients need not fill the grid they are laid out on: the rest is empty.
                List<ItemStack> shaped = new ArrayList<>(
                    Collections.nCopies(Math.max(grid.size(), width * height), ItemStack.EMPTY));
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
        List<Amount> remainders = containerRemainders(holder.value(), grid, width, height);
        return new Assembly() {
            public Optional<List<ItemStack>> runOnce(ServerLevel level, Container pool) { return making.runOnce(level, pool); }
            public List<Amount> remainders() { return remainders; }
        };
    }

    // Ask the same native method used at commit time. Its default implementation handles slots
    // independently; an override may depend on the complete input, so inspect all its possible grids.
    private static List<Amount> containerRemainders(CraftingRecipe recipe, List<Ingredient> grid, int width, int height) {
        List<List<ItemStack>> choices = new ArrayList<>();
        for (Ingredient ingredient : grid) {
            choices.add(ingredient.isEmpty() ? List.of(ItemStack.EMPTY)
                : java.util.Arrays.stream(ingredient.getItems()).filter(stack -> !stack.isEmpty())
                    .map(stack -> stack.copyWithCount(1)).toList());
        }
        if (choices.stream().anyMatch(List::isEmpty)) { return List.of(); }
        Map<ItemSpec, Long> budget = new LinkedHashMap<>();
        if (defaultRemainders(recipe)) {
            for (int slot = 0; slot < choices.size(); slot++) {
                Map<ItemSpec, Long> possible = new LinkedHashMap<>();
                for (ItemStack item : choices.get(slot)) {
                    List<ItemStack> input = new ArrayList<>(Collections.nCopies(Math.max(grid.size(), width * height), ItemStack.EMPTY));
                    input.set(slot, item.copy());
                    maximum(possible, remainderAmounts(recipe.getRemainingItems(CraftingInput.of(width, height, input))));
                }
                possible.forEach((spec, count) -> budget.merge(spec, count, Long::sum));
            }
        } else {
            long combinations = 1;
            for (List<ItemStack> slot : choices) {
                combinations *= slot.size();
                // Do not freeze an unbounded custom-recipe search or pretend its unseen outputs fit.
                if (combinations > 4096) { return Leaves.room(4096); }
            }
            inspectRemainders(recipe, choices, width, height, 0,
                new ArrayList<>(Collections.nCopies(Math.max(grid.size(), width * height), ItemStack.EMPTY)), budget);
        }
        return budget.entrySet().stream().map(entry -> new Amount(entry.getKey(), entry.getValue())).toList();
    }

    private static boolean defaultRemainders(CraftingRecipe recipe) {
        try {
            return recipe.getClass().getMethod("getRemainingItems", RecipeInput.class).getDeclaringClass() == Recipe.class;
        } catch (NoSuchMethodException broken) { throw new IllegalStateException(broken); }
    }

    private static void inspectRemainders(CraftingRecipe recipe, List<List<ItemStack>> choices,
                                         int width, int height, int slot, List<ItemStack> input, Map<ItemSpec, Long> budget) {
        if (slot == choices.size()) {
            maximum(budget, remainderAmounts(recipe.getRemainingItems(CraftingInput.of(width, height,
                input.stream().map(ItemStack::copy).toList()))));
            return;
        }
        for (ItemStack item : choices.get(slot)) {
            input.set(slot, item);
            inspectRemainders(recipe, choices, width, height, slot + 1, input, budget);
        }
    }

    private static Map<ItemSpec, Long> remainderAmounts(List<ItemStack> remaining) {
        Map<ItemSpec, Long> amounts = new LinkedHashMap<>();
        for (ItemStack stack : remaining) {
            if (stack.isEmpty()) { continue; }
            if (ItemStack.isSameItemSameComponents(stack, stack.getItem().getDefaultInstance())) {
                amounts.merge(Goods.specOf(stack), (long) stack.getCount(), Long::sum);
            } else {
                // An item-only spec cannot promise that two component-bearing containers stack together.
                int size = Math.max(1, stack.getMaxStackSize());
                amounts.merge(Leaves.UNSEEN, (stack.getCount() + (long) size - 1) / size, Long::sum);
            }
        }
        return amounts;
    }

    private static void maximum(Map<ItemSpec, Long> into, Map<ItemSpec, Long> seen) {
        seen.forEach((spec, count) -> into.merge(spec, count, Math::max));
    }

    static Assembly stonecut(RecipeHolder<StonecutterRecipe> holder, ItemSpec input) {
        return rewarded(holder.id(), stonecutBase(holder, input));
    }

    private static Assembly stonecutBase(RecipeHolder<StonecutterRecipe> holder, ItemSpec input) {
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
        return rewarded(holder.id(), smithBase(holder));
    }

    private static Assembly smithBase(RecipeHolder<SmithingRecipe> holder) {
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
