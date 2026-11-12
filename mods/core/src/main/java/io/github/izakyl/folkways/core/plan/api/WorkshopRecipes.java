package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

/** Enumerates the same material transformations offered for derived Produce demands, never root orders. */
public final class WorkshopRecipes {

    public record Recipe(List<Need> inputs, List<Amount> outputs) {
        public Recipe {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }
    }

    private WorkshopRecipes() {
    }

    public static Set<ItemSpec> products(ServerLevel level) {
        Set<ItemSpec> products = new LinkedHashSet<>();
        for (var item : BuiltInRegistries.ITEM) {
            if (!item.getDefaultInstance().isEmpty()) {
                products.add(Goods.specOf(item.getDefaultInstance()));
            }
        }
        for (var recipe : level.getRecipeManager().getRecipes()) {
            var result = recipe.value().getResultItem(level.registryAccess());
            if (!result.isEmpty()) {
                products.add(Goods.specOf(result));
            }
        }
        return Set.copyOf(products);
    }

    public static Recipe materials(Grown graph) {
        Map<ItemSpec, Long> inputs = new LinkedHashMap<>();
        Map<ItemSpec, Long> outputs = new LinkedHashMap<>();
        for (var node : graph.nodes()) {
            node.recipeInputs().forEach(need -> inputs.merge(need.spec(), need.count(), Long::sum));
            node.spec().gives().forEach(amount -> outputs.merge(amount.spec(), amount.count(), Long::sum));
            node.spec().leaves().forEach(amount -> outputs.merge(amount.spec(), amount.count(), Long::sum));
        }
        // Internal intermediates never cross the network boundary.
        for (var output : outputs.entrySet()) {
            for (var input : inputs.entrySet()) {
                if (Goods.includes(input.getKey(), output.getKey())) {
                    long internal = Math.min(input.getValue(), output.getValue());
                    input.setValue(input.getValue() - internal);
                    output.setValue(output.getValue() - internal);
                }
            }
        }
        List<Need> takes = new ArrayList<>();
        inputs.forEach((spec, count) -> {
            if (count > 0) {
                takes.add(new Need(spec, count));
            }
        });
        List<Amount> gives = new ArrayList<>();
        outputs.forEach((spec, count) -> {
            if (count > 0) {
                gives.add(new Amount(spec, count));
            }
        });
        return new Recipe(takes, gives);
    }

    public static List<Recipe> discover(Workshop workshop, Set<ItemSpec> products) {
        Set<Recipe> recipes = new LinkedHashSet<>();
        for (ItemSpec product : products) {
            Produce demand = Produce.of(workshop.id(), workshop.site(), product, 1,
                (at, inputs, yield, vocation, limit) -> 1);
            for (var option : workshop.options(demand, Grown.of(demand))) {
                Recipe recipe = materials(option.replacement());
                List<Need> takes = recipe.inputs();
                List<Amount> gives = recipe.outputs();
                if (!takes.isEmpty() && !gives.isEmpty()) {
                    recipes.add(new Recipe(takes, gives));
                }
            }
        }
        return List.copyOf(recipes);
    }
}
