package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.WorkshopRecipes;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;

final class ColonyPattern {

    private ColonyPattern() {
    }

    static Optional<Pattern> of(String reference, WorkshopRecipes.Recipe recipe) {
        try {
            List<Ingredient> ingredients = new ArrayList<>();
            for (var need : recipe.inputs()) {
                List<ResourceKey> choices = new ArrayList<>();
                choices(need.spec(), choices);
                if (choices.isEmpty() || need.count() <= 0) {
                    return Optional.empty();
                }
                ingredients.add(new Ingredient(need.count(), choices));
            }
            List<ResourceAmount> outputs = new ArrayList<>();
            for (var output : recipe.outputs()) {
                var stack = Goods.display(output.spec());
                if (stack.isEmpty() || output.count() <= 0) {
                    return Optional.empty();
                }
                outputs.add(new ResourceAmount(ItemResource.ofItemStack(stack), output.count()));
            }
            if (outputs.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Pattern(UUID.nameUUIDFromBytes(reference.getBytes(StandardCharsets.UTF_8)),
                PatternLayout.external(ingredients, outputs)));
        } catch (RuntimeException invalid) {
            // Unsupported goods must not prevent other recipes from being offered.
            return Optional.empty();
        }
    }

    private static void choices(ItemSpec spec, List<ResourceKey> choices) {
        if (!spec.anyOf().isEmpty()) {
            spec.anyOf().forEach(alternative -> choices(alternative, choices));
            return;
        }
        for (var item : Goods.members(spec)) {
            try {
                var stack = Goods.display(new ItemSpec(Optional.of(BuiltInRegistries.ITEM.getKey(item)),
                    Optional.empty(), Set.of(), spec.components()));
                if (!stack.isEmpty() && Goods.matches(stack, spec)) {
                    choices.add(ItemResource.ofItemStack(stack));
                }
            } catch (RuntimeException invalid) {
                // Other members may still represent this input.
            }
        }
    }
}
