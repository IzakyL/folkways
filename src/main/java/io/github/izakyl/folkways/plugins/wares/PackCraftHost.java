package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

final class PackCraftHost implements StationHost {

    private final UUID id = UUID.randomUUID();
    private final WorldPos at;
    private final Block block;
    private final Stances stances;

    PackCraftHost(WorldPos at, Block block, Stances stances) {
        this.at = at;
        this.block = block;
        this.stances = stances;
    }

    @Override
    public Stances stances() {
        return stances;
    }

    @Override
    public Workshop frozen(RecipeIndex recipes, ServerLevel level, Optional<ItemSpec> fuel) {
        return new Bench(id, at, block, stances, recipes);
    }

    record Job(ItemSpec produces, long perRun, List<Ingredients.Demand> takes, Assembly assembly) {

        static Job of(List<Ingredients.Demand> demands, ItemStack result, Assembly assembly) {
            return new Job(Goods.specOf(result), result.getCount(), List.copyOf(demands), assembly);
        }

        List<Need> inputs() {
            List<Need> inputs = new ArrayList<>(takes.size());
            for (Ingredients.Demand demand : takes) {
                inputs.add(new Need(demand.spec(), demand.count()));
            }
            return inputs;
        }
    }

    private record Bench(UUID key, WorldPos at, Block block, Stances stances,
                         RecipeIndex recipes) implements Workshop {

        @Override
        public ResourceLocation id() {
            return WaresContent.ID;
        }

        @Override
        public WorkSite site() {
            return new WorkSite.AtBlock(at);
        }

        @Override
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)) {
                return List.of();
            }
            List<Refinement.Change> answers = new ArrayList<>();
            for (Job job : jobs(produce.wanted())) {
                long runs = produce.runs(site(), job.inputs(), job.perRun(),
                    Optional.of(WaresContent.trade()), Long.MAX_VALUE);
                answers.add(Refinement.Change.of(Grown.of(new PackCraftNode(at, block, stances, job.takes(),
                    job.produces(), job.perRun(), (int) Math.min(runs, Integer.MAX_VALUE), job.assembly()))));
            }
            return List.copyOf(answers);
        }

        private List<Job> jobs(ItemSpec goal) {
            List<Job> found = new ArrayList<>();
            if (block == Blocks.CRAFTING_TABLE) {
                for (RecipeHolder<CraftingRecipe> holder : recipes.producing(RecipeType.CRAFTING, goal)) {
                    grid(holder).ifPresent(found::add);
                }
            } else if (block == Blocks.STONECUTTER) {
                for (RecipeHolder<StonecutterRecipe> holder
                        : recipes.producing(RecipeType.STONECUTTING, goal)) {
                    stonecut(holder).ifPresent(found::add);
                }
            } else if (block == Blocks.SMITHING_TABLE) {
                for (RecipeHolder<SmithingRecipe> holder : recipes.producing(RecipeType.SMITHING, goal)) {
                    smith(holder).ifPresent(found::add);
                }
            }
            return found;
        }

        private Optional<Job> grid(RecipeHolder<CraftingRecipe> holder) {
            CraftingRecipe recipe = holder.value();
            List<Ingredient> grid = List.copyOf(recipe.getIngredients());
            Optional<List<Ingredients.Demand>> demands = Ingredients.demandsOf(grid);
            ItemStack result = recipes.resultOf(recipe);
            if (demands.isEmpty() || result.isEmpty()) {
                return Optional.empty();
            }
            int width = recipe instanceof ShapedRecipe shaped
                ? shaped.getWidth()
                : Math.min(3, grid.size());
            int height = recipe instanceof ShapedRecipe shaped
                ? shaped.getHeight()
                : (grid.size() + Math.max(1, width) - 1) / Math.max(1, width);
            return Optional.of(Job.of(demands.get(), result,
                Assembly.grid(holder, grid, Math.max(1, width), Math.max(1, height))));
        }

        private Optional<Job> stonecut(RecipeHolder<StonecutterRecipe> holder) {
            List<Ingredient> ingredients = List.copyOf(holder.value().getIngredients());
            Optional<List<Ingredients.Demand>> demands = Ingredients.demandsOf(ingredients);
            ItemStack result = recipes.resultOf(holder.value());
            if (demands.isEmpty() || result.isEmpty() || demands.get().size() != 1) {
                return Optional.empty();
            }
            return Optional.of(Job.of(demands.get(), result,
                Assembly.stonecut(holder, demands.get().get(0).spec())));
        }

        private Optional<Job> smith(RecipeHolder<SmithingRecipe> holder) {
            SmithingRecipe recipe = holder.value();
            if (!(recipe instanceof SmithingTransformRecipe) || recipe.isIncomplete()) {
                return Optional.empty();
            }
            ItemStack result = recipes.resultOf(recipe);
            if (result.isEmpty()) {
                return Optional.empty();
            }
            List<Ingredients.Demand> demands = new ArrayList<>();
            for (Predicate<ItemStack> slot : List.<Predicate<ItemStack>>of(
                    recipe::isTemplateIngredient, recipe::isBaseIngredient,
                    recipe::isAdditionIngredient)) {
                Optional<ItemSpec> spec = Goods.accepting(slot);
                if (spec.isEmpty()) {
                    return Optional.empty();
                }
                demands.add(new Ingredients.Demand(spec.get(), 1));
            }
            return Optional.of(Job.of(demands, result, Assembly.smith(holder)));
        }
    }
}
