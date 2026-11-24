package io.github.izakyl.folkways.vanilla.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.BambooTrayRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.ChoppingBoardRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexPotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.FlexStockpotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.MillstoneRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.PotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.StockpotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.TeapotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.soupbase.SoupBaseManager;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

final class KitchenMechanics {

    private KitchenMechanics() {
    }

    static void declare() {
        for (var station : KitchenStations.all()) {
            BuiltInRegistries.BLOCK.stream().filter(block -> station.matches(block.defaultBlockState()))
                    .forEach(block -> Mechanics.station(Mechanics.Station.block(block,
                            station instanceof io.github.izakyl.folkways.vanilla.cookery.stove.StoveStation
                                    ? CookeryContent.STOVE : CookeryContent.STATION,
                            WaresContent.CRAFTING, station.recipeTypes(), station.conditions())));
        }
        Mechanics.recipes(new Mechanics.Source() {
            public ResourceLocation id() {
                return CookeryContent.ID;
            }

            public List<Mechanics.Processing> recipes(RecipeManager recipes, HolderLookup.Provider registries) {
                List<Mechanics.Processing> result = new ArrayList<>();
                for (var holder : recipes.getRecipes()) {
                    if (FolkwaysVanillaConfig.refusedRecipes().contains(holder.id())) {
                        continue;
                    }
                    var type = Mechanics.recipeType(holder.value().getType());
                    for (var station : Mechanics.stations()) {
                        if (station.delegation().equals(CookeryContent.STATION)
                                && station.recipeTypes().contains(type)) {
                            describe(holder, station.id(), registries).ifPresent(result::add);
                        }
                    }
                }
                return List.copyOf(result);
            }
        });
    }

    private static Optional<Mechanics.Processing> describe(RecipeHolder<?> holder, ResourceLocation station,
                                                          HolderLookup.Provider registries) {
        List<Ingredient> ingredients = new ArrayList<>(holder.value().getIngredients());
        List<Need> extra = new ArrayList<>();
        List<Component> conditions = new ArrayList<>();
        List<ItemStack> outputs = List.of(holder.value().getResultItem(registries));
        Ingredient carrier = Ingredient.EMPTY;
        ResourceLocation soup = null;
        switch (holder.value()) {
            case PotRecipe recipe -> {
                carrier = recipe.carrier();
                extra.add(new Need(ItemSpec.of(TagMod.OIL), 1));
            }
            case FlexPotRecipe recipe -> {
                carrier = recipe.carrier();
                extra.add(new Need(ItemSpec.of(TagMod.OIL), 1));
            }
            case StockpotRecipe recipe -> {
                carrier = recipe.carrier();
                soup = recipe.soupBase();
            }
            case FlexStockpotRecipe recipe -> {
                carrier = recipe.carrier();
                soup = recipe.soupBase();
            }
            case ChoppingBoardRecipe recipe -> outputs = recipe.getResults();
            case MillstoneRecipe recipe -> {
                outputs = recipe.results().stream().filter(output -> output.chance() >= 1)
                        .map(output -> output.stack()).toList();
                conditions.add(Component.translatable("folkways.mechanics.byproducts"));
            }
            case TeapotRecipe recipe -> {
                if (recipe.ingredientCount() != 1) {
                    return Optional.empty();
                }
                ingredients = List.of(recipe.ingredient());
                var bucket = BuiltInRegistries.FLUID.get(recipe.teaFluid()).getBucket();
                if (bucket == Items.AIR) {
                    return Optional.empty();
                }
                extra.add(new Need(Goods.specOf(bucket.getDefaultInstance()), 1));
                outputs = List.of(recipe.result().copyWithCount(TeapotRecipe.OUTPUT_COUNT));
            }
            case BambooTrayRecipe recipe -> conditions.add(Component.translatable(
                    recipe.getSubtype() == BambooTrayRecipe.Subtype.WETTING
                            ? "folkways.mechanics.wetting" : "folkways.mechanics.drying"));
            default -> {
            }
        }
        if (soup != null) {
            var base = SoupBaseManager.getSoupBase(soup);
            if (base == null) {
                return Optional.empty();
            }
            Optional<ItemSpec> spec = Goods.accepting(base::isSoupBase);
            if (spec.isEmpty()) {
                return Optional.empty();
            }
            extra.add(new Need(spec.get(), 1));
        }
        for (Ingredient ingredient : ingredients) {
            if (!ingredient.isEmpty()) {
                Optional<ItemSpec> spec = Mechanics.ingredient(ingredient);
                if (spec.isEmpty()) {
                    return Optional.empty();
                }
                extra.add(new Need(spec.get(), 1));
            }
        }
        List<Amount> amounts = outputs.stream().filter(output -> !output.isEmpty())
                .map(output -> new Amount(Goods.specOf(output), output.getCount())).toList();
        if (amounts.isEmpty() || extra.isEmpty()) {
            return Optional.empty();
        }
        if (!carrier.isEmpty()) {
            Optional<ItemSpec> spec = Mechanics.ingredient(carrier);
            if (spec.isEmpty()) {
                return Optional.empty();
            }
            extra.add(new Need(spec.get(), amounts.getFirst().count()));
        }
        return Optional.of(new Mechanics.Processing(holder.id(), station, extra, amounts, conditions));
    }
}
