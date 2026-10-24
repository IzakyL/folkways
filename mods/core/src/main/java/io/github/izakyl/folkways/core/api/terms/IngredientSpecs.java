package io.github.izakyl.folkways.core.api.terms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.CompoundIngredient;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.common.crafting.DifferenceIngredient;
import net.neoforged.neoforge.common.crafting.IntersectionIngredient;

public final class IngredientSpecs {
    private IngredientSpecs() { }

    // Display stacks are exhaustive only for simple ingredients, not arbitrary stack predicates.
    public static Optional<ItemSpec> of(Ingredient ingredient) {
        if (ingredient.isEmpty()) return Optional.empty();
        var custom = ingredient.getCustomIngredient();
        if (custom instanceof DataComponentIngredient components) {
            if (components.items().size() == 0) return Optional.empty();
            if (components.isStrict()) {
                return union(Arrays.stream(ingredient.getItems()).filter(stack -> !stack.isEmpty())
                    .map(ItemSpec::exact).toList());
            }
            ItemSpec kinds = components.items().unwrapKey().map(ItemSpec::of).orElseGet(() ->
                ItemSpec.anyOf(components.items().stream().map(holder -> ItemSpec.of(holder.value())).toList()));
            return Optional.of(components.components().alwaysMatches() ? kinds : kinds.withComponents(components.components()));
        }
        if (custom instanceof CompoundIngredient compound) {
            List<ItemSpec> alternatives = new ArrayList<>();
            for (Ingredient child : compound.children()) {
                if (child.isEmpty()) continue;
                Optional<ItemSpec> spec = of(child);
                if (spec.isEmpty()) return Optional.empty();
                alternatives.add(spec.get());
            }
            return union(alternatives);
        }
        if (custom instanceof IntersectionIngredient intersection) {
            Optional<ItemSpec> result = of(intersection.children().getFirst());
            for (Ingredient child : intersection.children().subList(1, intersection.children().size())) {
                Optional<ItemSpec> next = of(child);
                if (result.isEmpty() || next.isEmpty()) return Optional.empty();
                result = intersect(result.get(), next.get());
            }
            return result;
        }
        if (custom instanceof DifferenceIngredient difference) {
            Optional<ItemSpec> base = of(difference.base());
            Optional<ItemSpec> removed = of(difference.subtracted());
            return base.isPresent() && removed.isPresent() ? subtract(base.get(), removed.get()) : Optional.empty();
        }
        if (ingredient.hasNoItems()) return Optional.empty();
        if (!ingredient.isCustom()) {
            List<ItemSpec> alternatives = new ArrayList<>();
            for (Ingredient.Value value : ingredient.getValues()) {
                if (value instanceof Ingredient.TagValue tag) alternatives.add(ItemSpec.of(tag.tag()));
                else if (value instanceof Ingredient.ItemValue item && !item.item().isEmpty()) {
                    alternatives.add(ItemSpec.of(item.item().getItem()));
                } else return Optional.empty();
            }
            return union(alternatives);
        }
        if (ingredient.isSimple()) {
            return union(Arrays.stream(ingredient.getItems()).filter(stack -> !stack.isEmpty())
                .map(stack -> ItemSpec.of(stack.getItem())).distinct().toList());
        }
        return Optional.empty();
    }

    /** One item per alternative, preserving the recipe's component constraints for stack-based machines. */
    public static List<ItemSpec> choices(Ingredient ingredient) {
        return of(ingredient).stream().flatMap(spec -> alternatives(spec).stream())
            .flatMap(spec -> Goods.members(spec).stream().map(item -> item(item, spec.components())))
            .distinct().toList();
    }

    private static List<ItemSpec> alternatives(ItemSpec spec) {
        return spec.anyOf().isEmpty() ? List.of(spec) : List.copyOf(spec.anyOf());
    }

    private static ItemSpec item(Item item, Optional<ItemComponents> components) {
        return new ItemSpec(Optional.of(BuiltInRegistries.ITEM.getKey(item)), Optional.empty(), Set.of(), components);
    }

    private static Optional<ItemSpec> intersect(ItemSpec one, ItemSpec other) {
        List<ItemSpec> result = new ArrayList<>();
        for (ItemSpec first : alternatives(one)) {
            for (ItemSpec second : alternatives(other)) {
                Optional<ItemComponents> components = first.components().isEmpty() ? second.components() : first.components();
                if (first.components().isPresent() && second.components().isPresent()) {
                    components = first.components().get().intersect(second.components().get());
                    if (components.isEmpty()) continue;
                }
                for (Item item : Goods.members(first)) {
                    if (Goods.members(second).contains(item)) result.add(item(item, components));
                }
            }
        }
        return union(result);
    }

    private static Optional<ItemSpec> subtract(ItemSpec base, ItemSpec removed) {
        List<ItemSpec> result = new ArrayList<>();
        for (ItemSpec alternative : alternatives(base)) {
            for (Item item : Goods.members(alternative)) {
                ItemSpec candidate = item(item, alternative.components());
                if (Goods.includes(removed, candidate)) continue;
                // A complement of component values cannot be expressed by a positive component constraint.
                if (Goods.overlap(removed, candidate)) return Optional.empty();
                result.add(candidate);
            }
        }
        return union(result);
    }

    private static Optional<ItemSpec> union(List<ItemSpec> alternatives) {
        return alternatives.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(alternatives));
    }
}
