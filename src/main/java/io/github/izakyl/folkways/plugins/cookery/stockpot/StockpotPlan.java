package io.github.izakyl.folkways.plugins.cookery.stockpot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.recipe.soupbase.ISoupBase;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.StockpotRecipeSerializer;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.soupbase.FluidSoupBase;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.soupbase.MobSoupBase;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.soupbase.SoupBaseManager;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import com.github.ysbbbbbb.kaleidoscopecookery.util.ItemUtils;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.FolkwaysConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

record StockpotPlan(ResourceLocation id, StockpotRecipe recipe, Tag definition, ItemStack result,
                    int portions, Need soup, List<Need> ingredients, Optional<Need> carrier) {

    StockpotPlan {
        definition = definition.copy();
        result = result.copy();
        ingredients = List.copyOf(ingredients);
    }

    static Optional<RecipeHolder<StockpotRecipe>> find(ServerLevel level, ResourceLocation id) {
        return level.getRecipeManager().getAllRecipesFor(ModRecipes.STOCKPOT_RECIPE).stream()
            .filter(holder -> holder.id().equals(id)).findFirst();
    }
    static Optional<StockpotPlan> of(ServerLevel level, RecipeHolder<StockpotRecipe> holder) {
        if (FolkwaysConfig.refusedRecipes().contains(holder.id())) {
            return Optional.empty();
        }
        StockpotRecipe recipe = holder.value();
        ItemStack result = recipe.getResultItem(level.registryAccess());
        ISoupBase soup = SoupBaseManager.getSoupBase(recipe.soupBase());
        if (result.isEmpty() || soup == null) {
            return Optional.empty();
        }
        ItemStack display = soup.getDisplayStack();
        Optional<ItemSpec> soupItem = !display.isEmpty() && soup.isSoupBase(display)
            ? Optional.of(Goods.specOf(display)) : Goods.accepting(soup::isSoupBase);
        if (soupItem.isEmpty()) {
            return Optional.empty();
        }
        List<Need> ingredients = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            Optional<ItemSpec> item = specOf(ingredient);
            if (item.isEmpty()) {
                return Optional.empty();
            }
            ingredients.add(new Need(item.get(), 1));
        }
        if (ingredients.isEmpty()) {
            return Optional.empty();
        }
        int portions = Math.min(result.getCount(), StockpotBlockEntity.MAX_TAKEOUT_COUNT);
        Optional<ItemSpec> carrier = recipe.carrier().isEmpty() ? Optional.empty() : specOf(recipe.carrier());
        if (!recipe.carrier().isEmpty() && carrier.isEmpty()) {
            return Optional.empty();
        }
        Tag definition = StockpotRecipeSerializer.CODEC.codec()
            .encodeStart(RegistryOps.create(NbtOps.INSTANCE, level.registryAccess()), recipe).getOrThrow();
        return Optional.of(new StockpotPlan(holder.id(), recipe, definition, result.copyWithCount(portions),
            portions, new Need(soupItem.get(), 1), merged(ingredients),
            carrier.map(item -> new Need(item, portions))));
    }

    ItemSpec produces() {
        return Goods.specOf(result);
    }

    List<Need> loadingNeeds(boolean hasLid, ItemSpec lid) {
        List<Need> needs = new ArrayList<>(ingredients);
        needs.add(soup);
        carrier.ifPresent(needs::add);
        if (!hasLid) {
            needs.add(new Need(lid, 1));
        }
        return merged(needs);
    }

    List<Amount> prepared() {
        return carrier.map(need -> List.of(new Amount(need.spec(), need.count()))).orElseGet(List::of);
    }

    List<Predicate<ItemStack>> loadingSlots(boolean hasLid, ItemSpec lid) {
        List<Predicate<ItemStack>> slots = new ArrayList<>();
        ISoupBase base = SoupBaseManager.getSoupBase(recipe.soupBase());
        slots.add(base == null ? stack -> false : base::isSoupBase);
        recipe.getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).forEach(slots::add);
        if (!hasLid) {
            slots.add(stack -> Goods.matches(stack, lid));
        }
        for (int serving = 0; serving < (carrier.isPresent() ? portions : 0); serving++) {
            slots.add(recipe.carrier());
        }
        return List.copyOf(slots);
    }

    List<Need> perRunNeeds() {
        List<Need> needs = new ArrayList<>(ingredients);
        needs.add(soup);
        carrier.ifPresent(needs::add);
        return merged(needs).stream().map(need -> new Need(need.spec(), need.count(), true)).toList();
    }

    List<Need> totalNeeds(int runs, boolean hasLid, ItemSpec lid) {
        List<Need> needs = new ArrayList<>();
        perRunNeeds().forEach(need -> needs.add(new Need(need.spec(), need.count() * runs, true)));
        if (!hasLid) {
            needs.add(new Need(lid, 1, true));
        }
        return List.copyOf(needs);
    }

    List<Amount> workLeaves(int runs, boolean returnsLid) {
        List<Amount> leaves = new ArrayList<>(containers());
        leaves = leaves.stream().map(amount -> new Amount(amount.spec(), amount.count() * runs))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        leaves.addAll(servingLeaves((long) portions * runs, returnsLid));
        return Leaves.merged(leaves);
    }

    List<Amount> loadingLeaves() {
        List<Amount> leaves = new ArrayList<>(containers());
        leaves.addAll(prepared());
        return Leaves.merged(leaves);
    }

    List<Amount> servingLeaves(long count, boolean returnsLid) {
        List<Amount> leaves = new ArrayList<>();
        if (result.getMaxStackSize() < Goods.stackSize(produces())) {
            // ItemSpec identifies goods by item; a recipe can lower an individual result stack's own limit.
            leaves.addAll(Leaves.room((int) ((count + result.getMaxStackSize() - 1) / result.getMaxStackSize())));
        } else {
            leaves.add(new Amount(produces(), count));
        }
        if (returnsLid) {
            leaves.add(new Amount(StockpotLoad.lid(), 1));
        }
        return List.copyOf(leaves);
    }

    private List<Amount> containers() {
        List<Amount> containers = new ArrayList<>();
        ISoupBase base = SoupBaseManager.getSoupBase(recipe.soupBase());
        if (base != null && (base.getClass() == FluidSoupBase.class || base.getClass() == MobSoupBase.class)) {
            containers.add(new Amount(Goods.specOf(Items.BUCKET.getDefaultInstance()), 1));
        } else {
            containers.addAll(Leaves.room(1));
        }
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                continue;
            }
            List<Item> alternatives = java.util.Arrays.stream(ingredient.getItems())
                .map(ItemUtils::getContainerItem).filter(item -> item != Items.AIR).distinct().toList();
            if (alternatives.size() == 1) {
                containers.add(new Amount(Goods.specOf(alternatives.getFirst().getDefaultInstance()), 1));
            } else if (alternatives.size() > 1 || ingredient.isCustom()) {
                containers.addAll(Leaves.room(1));
            }
        }
        return Leaves.merged(containers);
    }
    private static Optional<ItemSpec> specOf(Ingredient ingredient) {
        if (ingredient.isEmpty() || ingredient.hasNoItems()) {
            return Optional.empty();
        }
        if (!ingredient.isCustom()) {
            List<ItemSpec> alternatives = new ArrayList<>();
            for (Ingredient.Value value : ingredient.getValues()) {
                if (value instanceof Ingredient.TagValue tag) {
                    alternatives.add(ItemSpec.of(tag.tag()));
                } else if (value instanceof Ingredient.ItemValue item && !item.item().isEmpty()) {
                    alternatives.add(Goods.specOf(item.item()));
                }
            }
            if (!alternatives.isEmpty()) {
                return Optional.of(ItemSpec.anyOf(alternatives));
            }
        }
        return Goods.accepting(ingredient);
    }

    private static List<Need> merged(List<Need> needs) {
        Map<ItemSpec, Long> count = new LinkedHashMap<>();
        needs.forEach(need -> count.merge(need.spec(), need.count(), Long::sum));
        // A narrow ingredient is drawn before an overlapping tag by the core as well as by the final matcher.
        return count.entrySet().stream().map(entry -> new Need(entry.getKey(), entry.getValue()))
            .sorted(Comparator.comparingInt(need -> Goods.members(need.spec()).size())).toList();
    }
}
