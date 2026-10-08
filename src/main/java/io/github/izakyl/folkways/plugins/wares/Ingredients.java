package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.crafting.Ingredient;

final class Ingredients {

    private Ingredients() {
    }

    static Optional<ItemSpec> specOf(Ingredient ingredient) {
        if (ingredient.isEmpty() || ingredient.hasNoItems()) {
            return Optional.empty();
        }
        if (!ingredient.isCustom()) {
            List<ItemSpec> alternatives = new ArrayList<>();
            for (Ingredient.Value value : ingredient.getValues()) {
                if (value instanceof Ingredient.TagValue tagValue) {
                    alternatives.add(ItemSpec.of(tagValue.tag()));
                } else if (value instanceof Ingredient.ItemValue itemValue && !itemValue.item().isEmpty()) {
                    alternatives.add(Goods.specOf(itemValue.item()));
                } else {
                    return acceptedStacks(ingredient);
                }
            }
            return alternatives.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(alternatives));
        }
        return acceptedStacks(ingredient);
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

    private static Optional<ItemSpec> acceptedStacks(Ingredient ingredient) {
        List<ItemSpec> alternatives = Arrays.stream(ingredient.getItems())
            .filter(stack -> !stack.isEmpty())
            .map(Goods::specOf)
            .distinct()
            .toList();
        return alternatives.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(alternatives));
    }
}
