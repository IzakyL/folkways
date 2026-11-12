package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.Ingredient;
import com.refinedmods.refinedstorage.api.autocrafting.Pattern;
import com.refinedmods.refinedstorage.api.autocrafting.PatternLayout;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.crafting.ColonyRecipes;
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

    static Pattern of(ColonyRecipes.Entry entry) {
        var recipe = entry.recipe();
        List<Ingredient> ingredients = recipe.inputs().stream().map(need -> {
            List<ResourceKey> choices = new ArrayList<>();
            choices(need.spec(), choices);
            return new Ingredient(need.count(), choices);
        }).toList();
        var outputs = recipe.outputs().stream().map(output -> new ResourceAmount(
            ItemResource.ofItemStack(Goods.display(output.spec())), output.count())).toList();
        return new Pattern(UUID.nameUUIDFromBytes(entry.reference().getBytes(StandardCharsets.UTF_8)),
            PatternLayout.external(ingredients, outputs));
    }

    private static void choices(ItemSpec spec, List<ResourceKey> choices) {
        if (!spec.anyOf().isEmpty()) {
            spec.anyOf().forEach(alternative -> choices(alternative, choices));
            return;
        }
        for (var item : Goods.members(spec)) {
            var stack = Goods.display(new ItemSpec(Optional.of(BuiltInRegistries.ITEM.getKey(item)),
                Optional.empty(), Set.of(), spec.components()));
            if (Goods.matches(stack, spec)) {
                choices.add(ItemResource.ofItemStack(stack));
            }
        }
    }
}
