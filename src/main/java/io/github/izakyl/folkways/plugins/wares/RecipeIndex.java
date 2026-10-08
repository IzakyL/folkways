package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

final class RecipeIndex {

    private final RecipeManager source;
    private final ServerLevel level;

    private final Map<RecipeType<?>, Map<Item, List<RecipeHolder<?>>>> byTypeAndOutput =
        new ConcurrentHashMap<>();

    private RecipeIndex(ServerLevel level, RecipeManager source) {
        this.level = level;
        this.source = source;
    }

    static RecipeIndex over(ServerLevel level, RecipeManager source) {
        return new RecipeIndex(level, source);
    }

    boolean reads(RecipeManager manager) {
        return source == manager;
    }

    @SuppressWarnings("unchecked")
    <I extends RecipeInput, T extends Recipe<I>> List<RecipeHolder<T>> producing(RecipeType<T> type,
                                                                                ItemSpec goal) {
        Map<Item, List<RecipeHolder<?>>> byOutput =
            byTypeAndOutput.computeIfAbsent(type, ignored -> build(type));
        Set<Item> accepted = Goods.members(goal);
        if (accepted.size() == 1) {
            return (List<RecipeHolder<T>>) (List<?>)
                byOutput.getOrDefault(accepted.iterator().next(), List.of());
        }
        Set<RecipeHolder<?>> union = new LinkedHashSet<>();
        for (Item item : accepted) {
            union.addAll(byOutput.getOrDefault(item, List.of()));
        }
        List<RecipeHolder<?>> sorted = new ArrayList<>(union);
        sorted.sort(Comparator.comparing(holder -> holder.id().toString()));
        return (List<RecipeHolder<T>>) (List<?>) List.copyOf(sorted);
    }

    ItemStack resultOf(Recipe<?> recipe) {
        return recipe.getResultItem(level.registryAccess());
    }

    private <I extends RecipeInput, T extends Recipe<I>> Map<Item, List<RecipeHolder<?>>> build(
            RecipeType<T> type) {
        Map<Item, List<RecipeHolder<?>>> byOutput = new HashMap<>();
        for (RecipeHolder<T> holder : source.getAllRecipesFor(type)) {
            ItemStack result = holder.value().getResultItem(level.registryAccess());
            if (result.isEmpty()) {
                continue;
            }
            byOutput.computeIfAbsent(result.getItem(), ignored -> new ArrayList<>()).add(holder);
        }
        Map<Item, List<RecipeHolder<?>>> frozen = new HashMap<>();
        for (Map.Entry<Item, List<RecipeHolder<?>>> entry : byOutput.entrySet()) {
            List<RecipeHolder<?>> sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Comparator.comparing(holder -> holder.id().toString()));
            frozen.put(entry.getKey(), List.copyOf(sorted));
        }
        return frozen;
    }
}
