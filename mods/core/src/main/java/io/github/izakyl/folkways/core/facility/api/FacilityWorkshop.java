package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

public record FacilityWorkshop(Trigger.Demand trigger, List<Recipe> explicit) {

    public FacilityWorkshop {
        explicit = List.copyOf(explicit);
    }

    public record Item(String item, int count) {
    }

    /** One batch; `scale` lets a single run take up to that many batches at once. */
    public record Recipe(List<Item> inputs, List<Item> outputs, int ticks, Optional<ResourceLocation> id, int scale) {
        public Recipe {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
            if (scale < 1) {
                throw new IllegalArgumentException("a recipe scales by at least one batch, not " + scale);
            }
        }

        public Recipe(List<Item> inputs, List<Item> outputs, int ticks, Optional<ResourceLocation> id) {
            this(inputs, outputs, ticks, id, 1);
        }
    }

    public record Batch(ResourceLocation id, List<Need> inputs, List<Amount> outputs, int ticks, int scale) {
        public Batch {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }

        public Batch(ResourceLocation id, List<Need> inputs, List<Amount> outputs, int ticks) {
            this(id, inputs, outputs, ticks, 1);
        }

        /** `runs` batches taken in one run: every input and output multiplied. */
        Batch times(long runs) {
            if (runs == 1) {
                return this;
            }
            return new Batch(id, inputs.stream().map(need -> new Need(need.spec(), need.count() * runs, need.carried()))
                .toList(), outputs.stream().map(amount -> new Amount(amount.spec(), amount.count() * runs)).toList(),
                ticks, 1);
        }
    }

    static List<Batch> batches(List<Recipe> recipes, ResourceLocation station) {
        return batches(recipes, station, Map.of());
    }

    static List<Batch> batches(List<Recipe> recipes, ResourceLocation station, Map<String, ItemSpec> nativeItems) {
        return java.util.stream.IntStream.range(0, recipes.size()).mapToObj(index -> {
            Recipe recipe = recipes.get(index);
            return new Batch(recipe.id().orElseGet(() -> station.withSuffix("/" + index)),
                recipe.inputs().stream().map(item -> new Need(spec(item.item(), true, nativeItems), item.count(), true)).toList(),
                recipe.outputs().stream().map(item -> new Amount(spec(item.item(), false, nativeItems), item.count())).toList(),
                recipe.ticks(), recipe.scale());
        }).toList();
    }

    static ItemSpec spec(String text, boolean tags, Map<String, ItemSpec> nativeItems) {
        ItemSpec nativeItem = nativeItems.get(text);
        return nativeItem != null && (tags || nativeItem.item().isPresent()) ? nativeItem : spec(text, tags);
    }

    static ItemSpec spec(String text, boolean tags) {
        boolean tag = text.startsWith("#");
        ResourceLocation id = ResourceLocation.parse(tag ? text.substring(1) : text);
        if (tag && tags) {
            return ItemSpec.of(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, id));
        }
        var registry = BuiltInRegistries.ITEM;
        if (tag || !registry.containsKey(id) || id.equals(registry.getDefaultKey())) {
            throw new IllegalArgumentException("invalid workshop item " + text);
        }
        return ItemSpec.of(id);
    }
}
