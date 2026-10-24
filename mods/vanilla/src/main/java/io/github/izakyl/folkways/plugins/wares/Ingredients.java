package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.IngredientSpecs;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.crafting.Ingredient;

final class Ingredients {

    private Ingredients() {
    }

    static Optional<ItemSpec> specOf(Ingredient ingredient) {
        return IngredientSpecs.of(ingredient);
    }

    static Optional<List<Demand>> demandsOf(List<Ingredient> grid) {
        List<Demand> demands = new ArrayList<>();
        for (Ingredient ingredient : grid) {
            if (ingredient.isEmpty()) {
                continue;
            }
            Optional<ItemSpec> spec = specOf(ingredient);
            if (spec.isEmpty()) {
                return Optional.empty();
            }
            merge(demands, spec.get());
        }
        return demands.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(demands));
    }

    record Demand(ItemSpec spec, long count) {
    }

    private static void merge(List<Demand> demands, ItemSpec spec) {
        for (int index = 0; index < demands.size(); index++) {
            if (demands.get(index).spec().equals(spec)) {
                demands.set(index, new Demand(spec, demands.get(index).count() + 1));
                return;
            }
        }
        demands.add(new Demand(spec, 1));
    }

}
